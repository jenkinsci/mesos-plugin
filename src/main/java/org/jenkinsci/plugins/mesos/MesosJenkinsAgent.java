package org.jenkinsci.plugins.mesos;

import akka.NotUsed;
import akka.stream.ActorMaterializer;
import akka.stream.KillSwitches;
import akka.stream.SharedKillSwitch;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import com.mesosphere.usi.core.models.*;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.*;
import java.io.IOException;
import java.io.NotSerializableException;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import jenkins.metrics.api.Metrics;
import org.apache.mesos.v1.Protos.TaskState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Representation of a Jenkins node on Mesos. */
public class MesosJenkinsAgent extends AbstractCloudSlave implements EphemeralNode {

  private static final Logger logger = LoggerFactory.getLogger(MesosJenkinsAgent.class);

  private final Duration onlineTimeout;

  // Holds the current USI status for this agent.
  Optional<PodStatus> currentStatus = Optional.empty();

  private final boolean reusable;

  private final MesosApi api;

  private final String podId;

  private final URL jenkinsUrl;

  private final SharedKillSwitch waitUntilOnlineKillSwitch;

  public MesosJenkinsAgent(
      MesosApi api,
      String name,
      MesosAgentSpecTemplate spec,
      String nodeDescription,
      URL jenkinsUrl,
      Integer idleTerminationInMinutes,
      boolean reusable,
      List<? extends NodeProperty<?>> nodeProperties,
      Duration agentTimeout)
      throws Descriptor.FormException, IOException {
    super(
        name,
        nodeDescription,
        "jenkins",
        1,
        spec.getMode(),
        spec.getLabel(),
        new JNLPLauncher(),
        new MesosRetentionStrategy(idleTerminationInMinutes),
        nodeProperties);
    // pass around the MesosApi connection
    this.api = api;
    this.reusable = reusable;
    this.podId = name;
    this.jenkinsUrl = jenkinsUrl;
    this.onlineTimeout = agentTimeout;

    this.waitUntilOnlineKillSwitch =
        KillSwitches.shared(String.format("wait-until-online-{}", name));
  }

  /**
   * Polls the agent until it is online. Note: This is a non-blocking call in contrast to the
   * blocking {@link AbstractCloudComputer#waitUntilOnline}.
   *
   * @return The future agent that will come online.
   */
  public CompletableFuture<Node> waitUntilOnlineAsync(ActorMaterializer materializer) {
    return Source.tick(Duration.ofSeconds(0), Duration.ofSeconds(1), NotUsed.notUsed())
        .via(this.waitUntilOnlineKillSwitch.flow())
        .completionTimeout(onlineTimeout)
        .filter(ignored -> this.isOnline())
        .map(ignored -> this.asNode())
        .runWith(Sink.head(), materializer)
        .toCompletableFuture();
  }

  /** @return whether the agent is running or not. */
  public synchronized boolean isRunning() {
    if (currentStatus.isPresent()) {
      return currentStatus
          .get()
          .taskStatuses()
          .values()
          .forall(taskStatus -> taskStatus.getState() == TaskState.TASK_RUNNING);
    } else {
      return false;
    }
  }

  /** @return whether the agent is killed or not. */
  public synchronized boolean isKilled() {
    if (currentStatus.isPresent()) {
      return currentStatus
          .get()
          .taskStatuses()
          .values()
          .forall(taskStatus -> taskStatus.getState() == TaskState.TASK_KILLED);
    } else {
      return false;
    }
  }

  /** @return whether the agent is terminal or unreachable. */
  public synchronized boolean isTerminalOrUnreachable() {
    return currentStatus.map(PodStatus::isTerminalOrUnreachable).orElse(false);
  }

  /** @return whether the Jenkins agent connected and is online. */
  public synchronized boolean isOnline() {
    final Computer computer = this.toComputer();
    if (computer != null) {
      return computer.isOnline();
    } else {
      logger.warn("No computer for node {}.", getNodeName());
      return false;
    }
  }

  /** @return whether the agent is launching and not connected yet. */
  public synchronized boolean isPending() {
    return (!isTerminalOrUnreachable() && !isOnline());
  }

  /**
   * Updates the state of the slave and takes action on certain events.
   *
   * @param event The state event from USI which informs about the task status.
   */
  public synchronized void update(PodStatusUpdatedEvent event) {
    if (event.newStatus().isDefined()) {
      logger.info("Received new status for {}", event.id().value());
      this.currentStatus = Optional.of(event.newStatus().get());

      // Handle state change.
      if (this.isTerminalOrUnreachable()) {
        Metrics.metricRegistry().meter("mesos.agent.terminal").mark();
        String message =
            String.format(
                "Agent %s became %s: %s",
                this.getNodeName(),
                this.currentStatus.get().taskStatuses().values().head().getState(),
                this.currentStatus.get().taskStatuses().values().head().getMessage());
        waitUntilOnlineKillSwitch.abort(new IllegalStateException(message));
      }
    }
  }

  @Override
  public Node asNode() {
    return this;
  }

  @Override
  public AbstractCloudComputer createComputer() {
    return new MesosComputer(this);
  }

  @Override
  protected void _terminate(TaskListener listener) {
    try {
      logger.info("Killing task {}", this.podId);
      this.api.killAgent(this.podId);
    } catch (Exception ex) {
      logger.warn("Error when killing task {}", this.podId, ex);
    }
  }

  public boolean getReusable() {
    // TODO: implement reusable slaves DCOS_OSS-5048
    return reusable;
  }

  /** get the podId tied to this task. */
  public String getPodId() {
    return podId;
  }

  // Mark as not serializable.

  private void writeObject(java.io.ObjectOutputStream stream) throws IOException {
    throw new NotSerializableException();
  }

  private void readObject(java.io.ObjectInputStream stream)
      throws IOException, ClassNotFoundException {
    throw new NotSerializableException();
  }
}
