package org.jenkinsci.plugins.mesos;

import akka.NotUsed;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import com.mesosphere.usi.core.models.Goal;
import com.mesosphere.usi.core.models.PodSpec;
import com.mesosphere.usi.core.models.PodStatus;
import com.mesosphere.usi.core.models.PodStatusUpdated;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.EphemeralNode;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.NodeProperty;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import jenkins.model.Jenkins;
import org.apache.mesos.v1.Protos.TaskState;
import org.jenkinsci.plugins.mesos.api.MesosSlavePodSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Representation of a Jenkins node on Mesos. */
public class MesosJenkinsAgent extends AbstractCloudSlave implements EphemeralNode {

  private static final Logger logger = LoggerFactory.getLogger(MesosJenkinsAgent.class);

  // Holds the current USI status for this agent.
  Optional<PodStatus> currentStatus = Optional.empty();

  private final Boolean reusable;

  private final MesosCloud cloud;

  private final String podId;

  private final URL jenkinsUrl;

  private final MesosAgentSpecTemplate spec;

  public MesosJenkinsAgent(
      MesosCloud cloud,
      String name,
      MesosAgentSpecTemplate spec,
      String nodeDescription,
      URL jenkinsUrl,
      List<? extends NodeProperty<?>> nodeProperties)
      throws Descriptor.FormException, IOException {
    super(
        name,
        nodeDescription,
        "jenkins",
        1,
        spec.getMode(),
        spec.getLabel(),
        new JNLPLauncher(),
        null,
        nodeProperties);
    // pass around the MesosApi connection via MesosCloud
    this.cloud = cloud;
    this.reusable = true;
    this.podId = name;
    this.jenkinsUrl = jenkinsUrl;
    this.spec = spec;
  }

  /**
   * Polls the agent until it is online. Note: This is a non-blocking call in contrast to the
   * blocking {@link AbstractCloudComputer#waitUntilOnline}.
   *
   * @return The future agent that will come online.
   */
  public CompletableFuture<Node> waitUntilOnlineAsync() {
    return Source.tick(Duration.ofSeconds(0), Duration.ofSeconds(1), NotUsed.notUsed())
        .completionTimeout(Duration.ofMinutes(5))
        .filter(ignored -> this.isOnline())
        .map(ignored -> this.asNode())
        .runWith(Sink.head(), this.getCloud().getMesosApi().getMaterializer())
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

  /** @return whether the Jenkins agent connected and is online. */
  public synchronized boolean isOnline() {
    return this.toComputer().isOnline();
  }

  /** @return whether the agent is launching and not connected yet. */
  public synchronized boolean isPending() {
    return (!isKilled() && !isOnline());
  }

  public PodSpec getPodSpec(Goal goal) throws MalformedURLException, URISyntaxException {
    return MesosSlavePodSpec.builder()
        .withCpu(this.spec.getCpu())
        .withMemory(this.spec.getMemory())
        .withName(this.name)
        .withJenkinsUrl(this.jenkinsUrl)
        .withGoal(goal)
        .build();
  }

  /**
   * Updates the state of the slave.
   *
   * @param event The state event from USI which informs about the task status.
   */
  public synchronized void update(PodStatusUpdated event) {
    if (event.newStatus().isDefined()) {
      logger.info("Received new status for {}", event.id().value());
      this.currentStatus = Optional.of(event.newStatus().get());
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
      logger.info("killing task {}", this.podId);
      // create a terminating spec for this pod
      Jenkins.getInstanceOrNull().removeNode(this);
      this.getCloud().getMesosApi().killAgent(this.podId);
    } catch (Exception ex) {
      logger.warn("error when killing task {}", this.podId, ex);
    }
  }

  public Boolean getReusable() {
    // TODO: implement reusable slaves
    return reusable;
  }

  public MesosCloud getCloud() {
    return cloud;
  }

  /** get the podId tied to this task. */
  public String getPodId() {
    return podId;
  }
}
