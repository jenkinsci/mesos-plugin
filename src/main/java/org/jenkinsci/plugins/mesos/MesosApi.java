package org.jenkinsci.plugins.mesos;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.KillSwitch;
import akka.stream.KillSwitches;
import akka.stream.OverflowStrategy;
import akka.stream.javadsl.*;
import com.mesosphere.mesos.client.MesosClient;
import com.mesosphere.mesos.client.MesosClient$;
import com.mesosphere.mesos.conf.MesosClientSettings;
import com.mesosphere.usi.core.japi.Scheduler;
import com.mesosphere.usi.core.models.*;
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
import org.apache.mesos.v1.Protos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Option;
import scala.concurrent.ExecutionContext;

public class MesosApi {

  private static final Logger logger = LoggerFactory.getLogger(MesosApi.class);

  private final String slavesUser;
  private final String frameworkName;
  private final URL jenkinsUrl;
  private final Protos.FrameworkID frameworkId;
  private final MesosClientSettings clientSettings;
  private final MesosClient client;

  private final SourceQueueWithComplete<SpecUpdated> updates;
  private final ConcurrentHashMap<PodId, MesosSlave> stateMap;

  private final ActorSystem system;
  private final ActorMaterializer materializer;
  private final ExecutionContext context;

  /**
   * Establishes a connection to Mesos and provides a simple interface to start and stop {@link
   * MesosSlave} instances.
   *
   * @param masterUrl The Mesos master address to connect to.
   * @param jenkinsUrl The Jenkins address to fetch the agent jar from.
   * @param user The username used for executing Mesos tasks.
   * @param frameworkName The name of the framework the Mesos client should register as.
   * @throws InterruptedException
   * @throws ExecutionException
   */
  public MesosApi(String masterUrl, URL jenkinsUrl, String user, String frameworkName)
      throws InterruptedException, ExecutionException {
    this.frameworkName = frameworkName;
    this.frameworkId =
        Protos.FrameworkID.newBuilder().setValue(UUID.randomUUID().toString()).build();
    this.slavesUser = user;
    this.jenkinsUrl = jenkinsUrl;

    Config conf =
        ConfigFactory.load()
            .getConfig("mesos-client")
            .withValue("master-url", ConfigValueFactory.fromAnyRef(masterUrl));
    this.clientSettings = MesosClientSettings.fromConfig(conf);
    system = ActorSystem.create("mesos-scheduler");
    context = system.dispatcher();
    materializer = ActorMaterializer.create(system);

    client = connectClient().get();

    stateMap = new ConcurrentHashMap<>();

    logger.info("Starting USI scheduler flow.");
    updates = runUsi(SpecsSnapshot.empty(), client, materializer);
  }

  /**
   * Helper method that terminates the completes scheduler flow if on internal source or flow stops.
   * See {@link MesosApi#runUsi(SpecsSnapshot, MesosClient, ActorMaterializer)}.
   *
   * @param input Source of state events.
   * @param killSwitch The kill switch that should be triggered.
   * @return A new source with state events and a kill switch in place.
   */
  private Source<StateEvent, NotUsed> triggerKillSwitch(
      Source<StateEvent, Object> input, KillSwitch killSwitch) {
    return input.watchTermination(
        (mat, future) -> {
          future.whenComplete(
              (success, failure) -> {
                if (success != null) {
                  killSwitch.shutdown();
                }
                if (failure != null) {
                  killSwitch.abort(failure);
                }
              });
          return NotUsed.notUsed();
        });
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
  private SourceQueueWithComplete<SpecUpdated> runUsi(
      SpecsSnapshot specsSnapshot, MesosClient client, ActorMaterializer materializer) {
    var schedulerFlow = Scheduler.fromSnapshot(specsSnapshot, client);

    var killSwitch = KillSwitches.shared("mesos-jenkins-plugin");

    // We create a SourceQueue and assume that the very first item is a spec snapshot.
    var queue =
        Source.<SpecUpdated>queue(256, OverflowStrategy.fail())
            .via(killSwitch.flow())
            .via(schedulerFlow)
            .flatMapConcat( // Ignore state snapshot for now.
                pair -> triggerKillSwitch(pair.second(), killSwitch))
            .via(killSwitch.flow())
            .toMat(Sink.foreach(this::updateState), Keep.left())
            .run(materializer);

    return queue;
  }

  /**
   * Enqueue spec for a Jenkins agent that will eventually come online.
   *
   * @return a {@link MesosSlave} once it's queued for running.
   */
  public CompletionStage<MesosSlave> enqueueAgent()
      throws IOException, FormException, URISyntaxException {
    var name = String.format("jenkins-test-%s", UUID.randomUUID().toString());
    MesosSlave mesosSlave =
        new MesosSlave(name, "Mesos Jenkins Slave", jenkinsUrl, "label", List.of());
    PodSpec spec = mesosSlave.getPodSpec(0.1, 32);
    SpecUpdated update = new PodSpecUpdated(spec.id(), Option.apply(spec));

    stateMap.put(spec.id(), mesosSlave);

    // async add agent to queue
    return updates.offer(update).thenApply(result -> mesosSlave); // TODO: handle QueueOfferResult.
  }

  /** Establish a connection to Mesos via the v1 client. */
  private CompletableFuture<MesosClient> connectClient() {
    Protos.FrameworkInfo frameworkInfo =
        Protos.FrameworkInfo.newBuilder()
            .setUser(slavesUser)
            .setName(frameworkName)
            .setId(frameworkId)
            .addRoles("test")
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

  /**
   * Callback for USI to process state events.
   *
   * <p>This method will filter out {@link PodStatusUpdated} and pass them on to their {@link
   * MesosSlave}. It should be threadsafe.
   *
   * @param event The {@link PodStatusUpdated} for a USI pod.
   */
  public void updateState(StateEvent event) {
    if (event instanceof PodStatusUpdated) {
      var podStateEvent = (PodStatusUpdated) event;
      logger.info("Got status update for pod {}", podStateEvent.id().value());
      stateMap.computeIfPresent(
          podStateEvent.id(),
          (id, slave) -> {
            slave.update(podStateEvent);
            return slave;
          });
    }
  }
}
