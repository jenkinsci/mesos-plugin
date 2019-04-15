package org.jenkinsci.plugins.mesos;

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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.apache.commons.lang.NotImplementedException;
import org.apache.mesos.v1.Protos.TaskState;
import org.jenkinsci.plugins.mesos.api.MesosSlavePodSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Representation of a Jenkins node on Mesos. */
public class MesosSlave extends AbstractCloudSlave implements EphemeralNode {

  private static final Logger logger = LoggerFactory.getLogger(MesosSlave.class);

  // Holds the current USI status for this agent.
  Optional<PodStatus> currentStatus = Optional.empty();

  private final URL jenkinsUrl;

  public MesosSlave(
      String name,
      String nodeDescription,
      URL jenkinsUrl,
      String labelString,
      List<? extends NodeProperty<?>> nodeProperties)
      throws Descriptor.FormException, IOException {
    super(
        name,
        nodeDescription,
        null,
        1,
        null,
        labelString,
        new JNLPLauncher(),
        null,
        nodeProperties);
    this.jenkinsUrl = jenkinsUrl;
  }

  /**
   * Polls the agent until it is online. Note: This is a non-blocking call in contrast to the
   * blocking {@link AbstractCloudComputer#waitUntilOnline}.
   *
   * @return This agent when it's online.
   */
  public CompletableFuture<MesosSlave> waitUntilOnlineAsync() {
    throw new NotImplementedException();
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

  public PodSpec getPodSpec(Double cpu, int memory)
      throws MalformedURLException, URISyntaxException {
    return MesosSlavePodSpec.builder()
        .withCpu(cpu)
        .withMemory(memory)
        .withName(this.name)
        .withJenkinsUrl(this.jenkinsUrl)
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
    throw new NotImplementedException();
  }
}
