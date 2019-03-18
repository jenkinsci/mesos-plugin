package org.jenkinsci.plugins.mesos;

import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.EphemeralNode;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import org.apache.commons.lang.NotImplementedException;

/** Representation of a Jenkins node on Mesos. */
public class MesosSlave extends AbstractCloudSlave implements EphemeralNode {

  public MesosSlave() throws Descriptor.FormException, IOException {
    super(null, null, null, null, null, null, null, null, null);
    throw new NotImplementedException();
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
