package org.jenkinsci.plugins.mesos;

import hudson.model.Executor;
import hudson.model.Queue;
import hudson.slaves.AbstractCloudComputer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The running state of a {@link hudson.model.Node} or rather {@link MesosSlave} in our case. */
public class MesosComputer extends AbstractCloudComputer<MesosSlave> {

  private static final Logger logger = LoggerFactory.getLogger(MesosComputer.class);

  private final Boolean reusable;
  /**
   * Constructs a new computer. This is called by {@link MesosSlave#createComputer()}.
   *
   * @param slave The {@link hudson.model.Node} this computer belongs to.
   */
  public MesosComputer(MesosSlave slave) {
    super(slave);
    this.reusable = slave.getReusable();
  }

  @Override
  public void taskAccepted(Executor executor, Queue.Task task) {
    super.taskAccepted(executor, task);
    logger.info(" Computer " + this + ": task accepted");
  }

  @Override
  public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
    super.taskCompleted(executor, task, durationMS);
    logger.info(" Computer " + this + ": task completed");
  }

  @Override
  public void taskCompletedWithProblems(
      Executor executor, Queue.Task task, long durationMS, Throwable problems) {
    super.taskCompletedWithProblems(executor, task, durationMS, problems);
    logger.warn(" Computer " + this + " task completed with problems");
  }

  @Override
  public String toString() {
    return String.format("%s (slave: %s)", getName(), getNode());
  }
}
