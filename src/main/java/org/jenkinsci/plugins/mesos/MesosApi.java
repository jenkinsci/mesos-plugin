package org.jenkinsci.plugins.mesos;

import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.QueueOfferResult;
import akka.stream.javadsl.*;
import com.mesosphere.mesos.MasterDetector$;
import com.mesosphere.mesos.client.CredentialsProvider;
import com.mesosphere.mesos.client.DcosServiceAccountProvider;
import com.mesosphere.mesos.conf.MesosClientSettings;
import com.mesosphere.usi.core.conf.SchedulerSettings;
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
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;
import org.apache.mesos.v1.Protos;
import org.jenkinsci.plugins.mesos.MesosCloud.DcosAuthorization;
import org.jenkinsci.plugins.mesos.api.Session;
import org.jenkinsci.plugins.mesos.api.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.concurrent.ExecutionContext;

/**
 * Provides a simplified interface to Mesos through USI.
 *
 * <p>Each connection should be a singleton. New instance are create via {@link
 * MesosApi#getInstance(String, URL, String, String, String, String, Optional, Optional)}.
 */
public class MesosApi {

  private static final Logger logger = LoggerFactory.getLogger(MesosApi.class);

  static HashMap<String, MesosApi> sessions = new HashMap<>();

  /**
   * Fetching an existing connection or constructs a new one. See {@link MesosApi#MesosApi(String,
   * URL, String, String, String, String, Optional, Optional)} for parameters.
   */
  public static MesosApi getInstance(
      String master,
      URL jenkinsUrl,
      String agentUser,
      String frameworkName,
      String frameworkId,
      String role,
      Optional<String> sslCert,
      Optional<DcosAuthorization> authorization)
      throws ExecutionException, InterruptedException {
    if (!sessions.containsKey(frameworkId)) {
      final MesosApi session =
          new MesosApi(
              master,
              jenkinsUrl,
              agentUser,
              frameworkName,
              frameworkId,
              role,
              sslCert,
              authorization);
      logger.info("Initialized Mesos API object.");
      sessions.put(frameworkId, session);
      return session;
    } else {
      // Override Jenkins URL and agent user if they changed.
      MesosApi session = sessions.get(frameworkId);
      session.setJenkinsUrl(jenkinsUrl);
      session.setAgentUser(agentUser);
      return session;
    }
  }

  private final Settings operationalSettings;

  private final String frameworkName;
  private final Optional<String> frameworkPrincipal;
  private String role;
  private String agentUser;
  private final String frameworkId;
  private URL jenkinsUrl;
  private Duration agentTimeout;

  // Connection to Mesos through USI
  @Nonnull private final Session session;

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
   * @param master The Mesos master address to connect to. Should be one of host:port
   *     http://host:port zk://host1:port1,host2:port2,.../path
   *     zk://username:password@host1:port1,host2:port2,.../path
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
      String master,
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

    // Create actor system.
    this.system = ActorSystem.create("mesos-scheduler", conf, classLoader);
    this.materializer = ActorMaterializer.create(system);
    this.context = system.dispatcher();

    URL masterUrl =
        MasterDetector$.MODULE$
            .apply(master, Metrics.getInstance(frameworkName))
            .getMaster(context)
            .toCompletableFuture()
            .get();

    MesosClientSettings clientSettings =
        MesosClientSettings.load(classLoader).withMasters(Collections.singletonList(masterUrl));
    SchedulerSettings schedulerSettings = SchedulerSettings.load(classLoader);
    this.operationalSettings = Settings.load(classLoader);

    // Initialize state.
    this.stateMap = new ConcurrentHashMap<>();
    this.repository = new MesosPodRecordRepository();

    // Inject metrics and credentials provider.
    this.frameworkPrincipal = authorization.map(auth -> auth.getUid());
    Optional<CredentialsProvider> credentialsProvider =
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
    this.session =
        Session.create(
            buildFrameworkInfo(),
            clientSettings,
            credentialsProvider,
            schedulerSettings,
            repository,
            this.operationalSettings,
            this::updateState,
            null,
            context,
            system,
            materializer);

    this.agentTimeout = this.operationalSettings.getAgentTimeout();
  }

  private Protos.FrameworkInfo buildFrameworkInfo() {
    Protos.FrameworkID frameworkId =
        Protos.FrameworkID.newBuilder().setValue(this.frameworkId).build();
    Protos.FrameworkInfo.Builder frameworkInfoBuilder =
        Protos.FrameworkInfo.newBuilder()
            .setUser(this.agentUser)
            .setName(this.frameworkName)
            .setId(frameworkId)
            .addRoles(role)
            .addCapabilities(
                Protos.FrameworkInfo.Capability.newBuilder()
                    .setType(Protos.FrameworkInfo.Capability.Type.MULTI_ROLE))
            .addCapabilities(
                Protos.FrameworkInfo.Capability.newBuilder()
                    .setType(Protos.FrameworkInfo.Capability.Type.REGION_AWARE))
            .addCapabilities(
                Protos.FrameworkInfo.Capability.newBuilder()
                    .setType(Protos.FrameworkInfo.Capability.Type.PARTITION_AWARE))
            .setFailoverTimeout(this.operationalSettings.getFailoverTimeout().getSeconds());

    this.frameworkPrincipal.ifPresent(principal -> frameworkInfoBuilder.setPrincipal(principal));

    return frameworkInfoBuilder.build();
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
    logger.info("Kill agent {}.", podId.value());
    SchedulerCommand command = new KillPod(podId);
    return this.session
        .getCommands()
        .offer(command)
        .thenAccept(
            result -> {
              if (result == QueueOfferResult.dropped()) {
                logger.warn("USI command queue is full. Fail kill for {}", podId.value());
                throw new IllegalStateException(
                    String.format("Kill command for %s was dropped.", podId.value()));
              } else if (result == QueueOfferResult.enqueued()) {
                logger.debug("Successfully queued kill command for {}", podId.value());
              } else if (result instanceof QueueOfferResult.Failure) {
                final Throwable ex = ((QueueOfferResult.Failure) result).cause();
                throw new IllegalStateException("The USI stream failed or is closed.", ex);
              } else {
                throw new IllegalStateException(
                    String.format("Unknown queue result %s", result.toString()));
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
    LaunchPod launchCommand = spec.buildLaunchCommand(jenkinsUrl, name, this.role);

    stateMap.put(launchCommand.podId(), mesosJenkinsAgent);

    // async add agent to queue
    return this.session
        .getCommands()
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
              } else if (result instanceof QueueOfferResult.Failure) {
                final Throwable ex = ((QueueOfferResult.Failure) result).cause();
                throw new IllegalStateException("The USI stream failed or is closed.", ex);
              } else {
                throw new IllegalStateException(
                    String.format("Unknown queue result %s", result.toString()));
              }
            });
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

  // Setters

  public void setJenkinsUrl(URL jenkinsUrl) {
    this.jenkinsUrl = jenkinsUrl;
  }

  public void setAgentUser(String user) {
    this.agentUser = user;
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
