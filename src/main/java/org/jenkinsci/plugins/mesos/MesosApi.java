package org.jenkinsci.plugins.mesos;

import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.OverflowStrategy;
import akka.stream.javadsl.*;
import com.mesosphere.mesos.client.MesosClient;
import com.mesosphere.mesos.client.MesosClient$;
import com.mesosphere.mesos.conf.MesosClientSettings;
import com.mesosphere.usi.core.japi.Scheduler;
import com.mesosphere.usi.core.models.*;
import com.thoughtworks.xstream.annotations.XStreamOmitField;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;
import hudson.model.Descriptor.FormException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import jenkins.model.Jenkins;
import org.apache.mesos.v1.Protos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Option;
import scala.concurrent.ExecutionContext;

public class MesosApi {

  private static final Logger logger = LoggerFactory.getLogger(MesosApi.class);

  private final String frameworkName;
  private final String role;
  private final String agentUser;
  private final String frameworkId;
  private final URL jenkinsUrl;

  @XStreamOmitField private final MesosClient client;

  @XStreamOmitField private final SourceQueueWithComplete<SpecUpdated> updates;

  private final ConcurrentHashMap<PodId, MesosJenkinsAgent> stateMap;

  @XStreamOmitField private final ActorSystem system;

  @XStreamOmitField private final ActorMaterializer materializer;

  @XStreamOmitField private final ExecutionContext context;

  /**
   * Establishes a connection to Mesos and provides a simple interface to start and stop {@link
   * MesosJenkinsAgent} instances.
   *
   * @param masterUrl The Mesos master address to connect to.
   * @param jenkinsUrl The Jenkins address to fetch the agent jar from.
   * @param agentUser The username used for executing Mesos tasks.
   * @param frameworkName The name of the framework the Mesos client should register as.
   * @param role The Mesos role to assume.
   * @throws InterruptedException
   * @throws ExecutionException
   */
  public MesosApi(
      URL masterUrl, URL jenkinsUrl, String agentUser, String frameworkName, String role)
      throws InterruptedException, ExecutionException {
    this.frameworkName = frameworkName;
    this.frameworkId = UUID.randomUUID().toString();
    this.role = role;
    this.agentUser = agentUser;
    this.jenkinsUrl = jenkinsUrl;

    ClassLoader classLoader = Jenkins.getInstanceOrNull().pluginManager.uberClassLoader;

    Config conf = ConfigFactory.load(classLoader);
    Config clientConf =
        conf.getConfig("mesos-client")
            .withValue("master-url", ConfigValueFactory.fromAnyRef(masterUrl.toString()));

    logger.info("Config: {}", conf);
    MesosClientSettings clientSettings = MesosClientSettings.fromConfig(clientConf);
    system = ActorSystem.create("mesos-scheduler", conf, classLoader);
    context = system.dispatcher();
    materializer = ActorMaterializer.create(system);

    client = connectClient(clientSettings).get();

    stateMap = new ConcurrentHashMap<>();

    logger.info("Starting USI scheduler flow.");
    updates = runScheduler(SpecsSnapshot.empty(), client, materializer).get();
  }

  /**
   * Constructs a queue of {@link SpecUpdated} and passes the specs snapshot as the first item. All
   * updates are processed by {@link MesosApi#updateState(StateEvent)}.
   *
   * @param specsSnapshot The initial set of pod specs.
   * @param client The Mesos client that is used.
   * @param materializer The {@link ActorMaterializer} used for the source queue.
   * @return A running source queue.
   */
  private CompletableFuture<SourceQueueWithComplete<SpecUpdated>> runScheduler(
      SpecsSnapshot specsSnapshot, MesosClient client, ActorMaterializer materializer) {
    return Scheduler.asFlow(specsSnapshot, client, materializer)
        .thenApply(
            builder -> {
              // We create a SourceQueue and assume that the very first item is a spec snapshot.
              SourceQueueWithComplete<SpecUpdated> queue =
                  Source.<SpecUpdated>queue(256, OverflowStrategy.fail())
                      .via(builder.getFlow())
                      .toMat(Sink.foreach(this::updateState), Keep.left())
                      .run(materializer);

              return queue;
            });
  }

  /**
   * Enqueue spec for a Jenkins event, passing a non-null existing podId will trigger a kill for
   * that pod
   *
   * @return a {@link MesosJenkinsAgent} once it's queued for running.
   */
  public CompletionStage<Void> killAgent(String id) throws Exception {
    PodSpec spec = stateMap.get(new PodId(id)).getPodSpec(Goal.Terminal$.MODULE$);
    SpecUpdated update = new PodSpecUpdated(spec.id(), Option.apply(spec));
    return updates.offer(update).thenRun(() -> {});
  }

  /**
   * Enqueue spec for a Jenkins event, passing a non-null existing podId will trigger a kill for
   * that pod
   *
   * @return a {@link MesosJenkinsAgent} once it's queued for running.
   */
  public CompletionStage<MesosJenkinsAgent> enqueueAgent(
      MesosCloud cloud, String name, MesosAgentSpecTemplate spec)
      throws IOException, FormException, URISyntaxException {

    MesosJenkinsAgent mesosJenkinsAgent =
        new MesosJenkinsAgent(
            cloud, name, spec, "Mesos Jenkins Slave", jenkinsUrl, Collections.emptyList());
    PodSpec podSpec = mesosJenkinsAgent.getPodSpec(Goal.Running$.MODULE$);
    SpecUpdated update = new PodSpecUpdated(podSpec.id(), Option.apply(podSpec));

    stateMap.put(podSpec.id(), mesosJenkinsAgent);
    // async add agent to queue
    return updates
        .offer(update)
        .thenApply(result -> mesosJenkinsAgent); // TODO: handle QueueOfferResult.
  }

  /** Establish a connection to Mesos via the v1 client. */
  private CompletableFuture<MesosClient> connectClient(MesosClientSettings clientSettings) {
    Protos.FrameworkID frameworkId =
        Protos.FrameworkID.newBuilder().setValue(this.frameworkId).build();
    Protos.FrameworkInfo frameworkInfo =
        Protos.FrameworkInfo.newBuilder()
            .setUser(this.agentUser)
            .setName(this.frameworkName)
            .setId(frameworkId)
            .addRoles(role)
            .addCapabilities(
                Protos.FrameworkInfo.Capability.newBuilder()
                    .setType(Protos.FrameworkInfo.Capability.Type.MULTI_ROLE))
            .setFailoverTimeout(0d) // Use config from current Mesos plugin.
            .build();

    return MesosClient$.MODULE$
        .apply(clientSettings, frameworkInfo, system, materializer)
        .runWith(Sink.head(), materializer)
        .toCompletableFuture();
  }

  public ActorMaterializer getMaterializer() {
    return materializer;
  }

  /**
   * Callback for USI to process state events.
   *
   * <p>This method will filter out {@link PodStatusUpdated} and pass them on to their {@link
   * MesosJenkinsAgent}. It should be threadsafe.
   *
   * @param event The {@link PodStatusUpdated} for a USI pod.
   */
  public void updateState(StateEvent event) {
    if (event instanceof PodStatusUpdated) {
      PodStatusUpdated podStateEvent = (PodStatusUpdated) event;
      logger.info("Got status update for pod {}", podStateEvent.id().value());
      stateMap.computeIfPresent(
          podStateEvent.id(),
          (id, slave) -> {
            slave.update(podStateEvent);
            return slave;
          });
    }
  }

  // Getters

  /** @return the name of the registered Mesos framework. */
  public String getFrameworkName() {
    return this.frameworkName;
  }

  /** @return the current state map. */
  public Map<PodId, MesosJenkinsAgent> getState() {
    return Collections.unmodifiableMap(this.stateMap);
  }
}
