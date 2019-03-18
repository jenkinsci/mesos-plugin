package org.jenkinsci.plugins.mesos;

import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.AbstractCloudImpl;
import hudson.slaves.NodeProvisioner;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.apache.commons.lang.NotImplementedException;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Jenkins Cloud implementation for Mesos.
 *
 * <p>The layout is inspired by the Nomad Plugin.
 *
 * @see https://github.com/jenkinsci/nomad-plugin
 */
class MesosCloud extends AbstractCloudImpl {

  private MesosApi mesos;
  private final String frameworkName = "JenkinsMesos";
  private final String slavesUser = "example";

  @DataBoundConstructor
  public MesosCloud(String name) throws InterruptedException, ExecutionException {
    super(name, null);

    String masterUrl = null;
    mesos = new MesosApi(masterUrl, slavesUser, frameworkName);
    throw new NotImplementedException();
  }

  /**
   * Provision one or more Jenkins nodes on Mesos.
   *
   * <p>The provisioning follows the Nomad plugin. The Jenkins agnets is started as a Mesos task and
   * added to the available Jenkins nodes. This differs from the old plugin when the provision
   * method would return immediately.
   *
   * @param label
   * @param excessWorkload
   * @return A collection of future nodes.
   */
  @Override
  public Collection<NodeProvisioner.PlannedNode> provision(Label label, int excessWorkload) {
    List<NodeProvisioner.PlannedNode> nodes = new ArrayList<>();

    String slaveName = "undefined";
    while (excessWorkload > 0) {
      nodes.add(new NodeProvisioner.PlannedNode(slaveName, startAgent(), 1));
      excessWorkload--;
    }

    return nodes;
  }

  /**
   * Start a Jenkins agent.jar on Mesos.
   *
   * <p>The future completes when the agent.jar is running on Mesos and the agent became online.
   *
   * @return A future reference to the launched node.
   */
  private CompletableFuture<Node> startAgent() {
    mesos.startAgent().thenCompose(mesosSlave -> mesosSlave.waitUntilOnlineAsync());
    throw new NotImplementedException();
  }

  @Override
  public boolean canProvision(Label label) {
    throw new NotImplementedException();
  }
}
