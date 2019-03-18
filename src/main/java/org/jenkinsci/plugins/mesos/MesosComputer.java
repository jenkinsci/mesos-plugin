package org.jenkinsci.plugins.mesos;

import hudson.model.Executor;
import hudson.model.Queue;
import hudson.slaves.AbstractCloudComputer;
import org.apache.commons.lang.NotImplementedException;

/** The running state of a {@link hudson.model.Node} or rather {@link MesosSlave} in our case. */
public class MesosComputer extends AbstractCloudComputer<MesosSlave> {

  /**
   * Constructs a new computer. This is called by {@link MesosSlave#createComputer()}.
   *
   * @param slave The {@link hudson.model.Node} this computer belongs to.
   */
  public MesosComputer(MesosSlave slave) {
    super(slave);
    throw new NotImplementedException();
  }

  @Override
  public void taskAccepted(Executor executor, Queue.Task task) {
    throw new NotImplementedException();
  }

  @Override
  public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
    throw new NotImplementedException();
  }

  @Override
  public void taskCompletedWithProblems(
      Executor executor, Queue.Task task, long durationMS, Throwable problems) {
    throw new NotImplementedException();
  }

  @Override
  public String toString() {
    return String.format("%s (slave: %s)", getName(), getNode());
  }
}
