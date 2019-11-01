package org.jenkinsci.plugins.mesos;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.OverflowStrategy;
import akka.stream.QueueOfferResult;
import akka.stream.javadsl.*;
import com.mesosphere.mesos.client.CredentialsProvider;
import com.mesosphere.mesos.client.DcosServiceAccountProvider;
import com.mesosphere.mesos.client.MesosClient;
import com.mesosphere.mesos.client.MesosClient$;
import com.mesosphere.mesos.conf.MesosClientSettings;
import com.mesosphere.usi.core.conf.SchedulerSettings;
import com.mesosphere.usi.core.japi.Scheduler;
import com.mesosphere.usi.core.models.*;
import com.mesosphere.usi.core.models.commands.KillPod;
import com.mesosphere.usi.core.models.commands.LaunchPod;
import com.mesosphere.usi.core.models.commands.SchedulerCommand;
import com.mesosphere.usi.repository.PodRecordRepository;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;
import hudson.model.Descriptor.FormException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;
import org.apache.mesos.v1.Protos;
import org.jenkinsci.plugins.mesos.MesosCloud.DcosAuthorization;
import org.jenkinsci.plugins.mesos.api.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.compat.java8.OptionConverters;
import scala.concurrent.ExecutionContext;

public class MesosApi {

  private static final Logger logger = LoggerFactory.getLogger(MesosApi.class);

  private final Settings operationalSettings;

  private final String frameworkName;
  private final String role;
  private final String agentUser;
  private final String frameworkId;
  private final URL jenkinsUrl;
  private Duration agentTimeout;

  // Interface to USI.
  @Nonnull private final SourceQueueWithComplete<SchedulerCommand> commands;

  // Internal state.
  @Nonnull private final ConcurrentHashMap<PodId, MesosJenkinsAgent> stateMap;
  @Nonnull private final PodRecordRepository repository;

  // Actor system.
  @Nonnull private final ActorSystem system;
  @Nonnull private final ActorMaterializer materializer;
  @Nonnull private final ExecutionContext context;

  /**
   * Establishes a connection to Mesos and provides a simple interface to start and stop {@link
   * MesosJenkinsAgent} instances.
   *
   * @param masterUrl The Mesos master address to connect to.
   * @param jenkinsUrl The Jenkins address to fetch the agent jar from.
   * @param agentUser The username used for executing Mesos tasks.
   * @param frameworkName The name of the framework the Mesos client should register as.
   * @param frameworkId The id of the framework the Mesos client should register for.
   * @param role The Mesos role to assume.
   * @param sslCert An optional custom SSL certificate to secure the connection to Mesos.
   * @param authorization An optional {@link CredentialsProvider} used to authorize with Mesos.
   * @throws InterruptedException
   * @throws ExecutionException
   */
  public MesosApi(
      URL masterUrl,
      URL jenkinsUrl,
      String agentUser,
      String frameworkName,
      String frameworkId,
      String role,
      Optional<String> sslCert,
      Optional<DcosAuthorization> authorization)
      throws InterruptedException, ExecutionException {
    this.frameworkName = frameworkName;
    this.frameworkId = frameworkId;
    this.role = role;
    this.agentUser = agentUser;
    this.jenkinsUrl = jenkinsUrl;

    // Load settings.
    final ClassLoader classLoader = Jenkins.get().pluginManager.uberClassLoader;

    @Nonnull Config conf;
    if (sslCert.isPresent()) {
      conf =
          ConfigFactory.parseString(
                  "akka.ssl-config.trustManager.stores = [{ type: \"PEM\", data: ${cert.pem} }]")
              .withValue("cert.pem", ConfigValueFactory.fromAnyRef(sslCert.get()))
              .resolve()
              .withFallback(ConfigFactory.load(classLoader));
    } else {
      conf = ConfigFactory.load(classLoader);
    }

    MesosClientSettings clientSettings =
        MesosClientSettings.load(classLoader).withMasters(Collections.singletonList(masterUrl));
    SchedulerSettings schedulerSettings = SchedulerSettings.load(classLoader);
    this.operationalSettings = Settings.load(classLoader);

    // Create actor system.
    this.system = ActorSystem.create("mesos-scheduler", conf, classLoader);
    this.materializer = ActorMaterializer.create(system);
    this.context = system.dispatcher();

    // Initialize state.
    this.stateMap = new ConcurrentHashMap<>();
    this.repository = new MesosPodRecordRepository();

    Optional<CredentialsProvider> provider =
        authorization.map(
            auth -> {
              try {
                CredentialsProvider p =
                    new DcosServiceAccountProvider(
                        auth.getUid(),
                        auth.getSecret(),
                        new URL("https://master.mesos"), // TODO: do not hardcode DC/OS URL.
                        this.system,
                        this.materializer,
                        this.context);
                return p;
              } catch (MalformedURLException e) {
                throw new RuntimeException("DC/OS URL validation failed", e);
              }
            });

    // Initialize scheduler flow.
    logger.info("Starting USI scheduler flow.");
    commands =
        connectClient(clientSettings, provider)
            .thenCompose(client -> Scheduler.fromClient(client, repository, schedulerSettings))
            .thenApply(builder -> runScheduler(builder.getFlow(), materializer))
            .get();

    this.agentTimeout = this.operationalSettings.getAgentTimeout();
  }

  /**
   * Internal constructor for testing the API.
   *
   * @param jenkinsUrl The Jenkins address to fetch the agent jar from.
   * @param agentUser The username used for executing Mesos tasks.
   * @param frameworkName The name of the framework the Mesos client should register as.
   * @param frameworkId Unique identifier of the framework in Mesos.
   * @param role The Mesos role to assume.
   * @param schedulerFlow The USI scheduler flow constructed by {@link Scheduler#fromClient()}
   * @param operationalSettings Operation settings for this plugin.
   * @param clientSettings The settings used to connect to USI.
   * @param schedulerSettings The settings used to created the scheduler flow.
   * @param system The Akka actor system to use.
   * @param materializer The Akka stream materializer to use.
   */
  public MesosApi(
      URL jenkinsUrl,
      String agentUser,
      String frameworkName,
      String frameworkId,
      String role,
      Flow<SchedulerCommand, StateEvent, NotUsed> schedulerFlow,
      Settings operationalSettings,
      ActorSystem system,
      ActorMaterializer materializer) {
    this.frameworkName = frameworkName;
    this.frameworkId = frameworkId;
    this.role = role;
    this.agentUser = agentUser;
    this.jenkinsUrl = jenkinsUrl;

    this.operationalSettings = operationalSettings;

    this.stateMap = new ConcurrentHashMap<>();
    this.repository = new MesosPodRecordRepository();

    this.commands = runScheduler(schedulerFlow, materializer);
    this.context = system.dispatcher();
    this.system = system;
    this.materializer = materializer;
  }

  /**
   * Constructs a queue of {@link SchedulerCommand}. All state events are processed by {@link
   * MesosApi#updateState(StateEventOrSnapshot)}.
   *
   * @param schedulerFlow The scheduler flow from commands to events provided by USI.
   * @param materializer The {@link ActorMaterializer} used for the source queue.
   * @return A running source queue.
   */
  private SourceQueueWithComplete<SchedulerCommand> runScheduler(
      Flow<SchedulerCommand, StateEvent, NotUsed> schedulerFlow, ActorMaterializer materializer) {
    return Source.<SchedulerCommand>queue(
            operationalSettings.getCommandQueueBufferSize(), OverflowStrategy.dropNew())
        .via(schedulerFlow)
        .toMat(Sink.foreach(this::updateState), Keep.left())
        .run(materializer);
  }

  /**
   * Enqueue spec for a Jenkins event, passing a non-null existing podId will trigger a kill for
   * that pod
   *
   * @return a {@link MesosJenkinsAgent} once it's queued for running.
   */
  public CompletionStage<Void> killAgent(String id) {
    return killAgent(new PodId(id));
  }

  /**
   * Enqueue spec for a Jenkins event, passing a non-null existing podId will trigger a kill for
   * that pod
   *
   * @return a {@link MesosJenkinsAgent} once it's queued for running.
   */
  public CompletionStage<Void> killAgent(PodId podId) {
    SchedulerCommand command = new KillPod(podId);
    return commands
        .offer(command)
        .thenAccept(
            result -> {
              if (result == QueueOfferResult.dropped()) {
                logger.warn("USI command queue is full. Fail kill for {}", podId.value());
                throw new IllegalStateException(
                    String.format("Kill command for %s was dropped.", podId.value()));
              } else {
                // TODO: Call crash strategy DCOS_OSS-5055
                throw new IllegalStateException("The USI stream failed or is closed.");
              }
            });
  }

  /**
   * Enqueue launch command for a new Jenkins agent.
   *
   * @return a {@link MesosJenkinsAgent} once it's queued for running.
   */
  public CompletionStage<MesosJenkinsAgent> enqueueAgent(String name, MesosAgentSpecTemplate spec)
      throws IOException, FormException, URISyntaxException {

    MesosJenkinsAgent mesosJenkinsAgent =
        new MesosJenkinsAgent(
            this,
            name,
            spec,
            "Mesos Jenkins Slave",
            jenkinsUrl,
            spec.getIdleTerminationMinutes(),
            spec.getReusable(),
            Collections.emptyList(),
            this.agentTimeout);
    LaunchPod launchCommand = spec.buildLaunchCommand(jenkinsUrl, name);

    stateMap.put(launchCommand.podId(), mesosJenkinsAgent);

    // async add agent to queue
    return commands
        .offer(launchCommand)
        .thenApply(
            result -> {
              if (result == QueueOfferResult.enqueued()) {
                logger.info("Queued new agent {}", name);
                return mesosJenkinsAgent;
              } else if (result == QueueOfferResult.dropped()) {
                logger.warn("USI command queue is full. Fail provisioning for {}", name);
                throw new IllegalStateException(
                    String.format("Launch command for %s was dropped.", name));
              } else {
                // TODO: Call crash strategy DCOS_OSS-5055
                throw new IllegalStateException("The USI stream failed or is closed.");
              }
            });
  }

  /** Establish a connection to Mesos via the v1 client. */
  private CompletableFuture<MesosClient> connectClient(
      MesosClientSettings clientSettings, Optional<CredentialsProvider> authorization) {
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
            .setFailoverTimeout(this.operationalSettings.getFailoverTimeout().getSeconds())
            .build();

    return MesosClient$.MODULE$
        .apply(
            clientSettings,
            frameworkInfo,
            OptionConverters.toScala(authorization),
            system,
            materializer)
        .runWith(Sink.head(), materializer)
        .toCompletableFuture();
  }

  public ActorMaterializer getMaterializer() {
    return materializer;
  }

  /**
   * Callback for USI to process state events.
   *
   * <p>This method will filter out {@link PodStatusUpdatedEvent} and pass them on to their {@link
   * MesosJenkinsAgent}. It should be threadsafe.
   *
   * @param event The {@link PodStatusUpdatedEvent} for a USI pod.
   */
  private void updateState(StateEventOrSnapshot event) {
    if (event instanceof PodStatusUpdatedEvent) {
      PodStatusUpdatedEvent podStateEvent = (PodStatusUpdatedEvent) event;
      logger.info("Got status update for pod {}", podStateEvent.id().value());
      MesosJenkinsAgent updated =
          stateMap.computeIfPresent(
              podStateEvent.id(),
              (id, agent) -> {
                agent.update(podStateEvent);
                return agent;
              });

      // The agent, ie the pod, is not terminal and unknown to us. Kill it.
      boolean terminal = podStateEvent.newStatus().forall(PodStatus::isTerminalOrUnreachable);
      if (updated == null && !terminal) {
        killAgent(podStateEvent.id());
      }
      if (terminal) {
        stateMap.remove(podStateEvent.id());
      }
    }
  }

  /** test method to set the agent timeout duration */
  public void setAgentTimeout(Duration agentTimeout) {
    this.agentTimeout = agentTimeout;
  }

  // Getters

  /** @return the name of the registered Mesos framework. */
  public String getFrameworkName() {
    return this.frameworkName;
  }

  /** @return the id of the registered Mesos framework. */
  public String getFrameworkId() {
    return this.frameworkId;
  }

  /** @return the role of the registered Mesos framework. */
  public String getRole() {
    return this.role;
  }

  /** @return the current state map. */
  public Map<PodId, MesosJenkinsAgent> getState() {
    return Collections.unmodifiableMap(this.stateMap);
  }
}
