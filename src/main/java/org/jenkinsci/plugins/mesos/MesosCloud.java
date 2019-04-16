package org.jenkinsci.plugins.mesos;

import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.AbstractCloudImpl;
import hudson.slaves.NodeProvisioner;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Jenkins Cloud implementation for Mesos.
 *
 * <p>The layout is inspired by the Nomad Plugin.
 *
 * @see https://github.com/jenkinsci/nomad-plugin
 */
public class MesosCloud extends AbstractCloudImpl {

  private static final Logger logger = LoggerFactory.getLogger(MesosCloud.class);

  private MesosApi mesos;

  private final String frameworkName = "JenkinsMesos";

  private final String slavesUser = System.getProperty("user.name");

  private final URL jenkinsUrl;

  @DataBoundConstructor
  public MesosCloud(String name, String mesosUrl, String jenkinsUrl)
      throws InterruptedException, ExecutionException, MalformedURLException {
    super(name, null);

    this.jenkinsUrl = new URL(jenkinsUrl);

    mesos = new MesosApi(mesosUrl, this.jenkinsUrl, slavesUser, frameworkName);
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

    while (excessWorkload > 0) {
      try {
        logger.info(
            "Excess workload of "
                + excessWorkload
                + ", provisioning new Jenkins slave on Mesos cluster");
        String slaveName = "undefined";

        nodes.add(new NodeProvisioner.PlannedNode(slaveName, startAgent(), 1));
        excessWorkload--;
      } catch (Exception ex) {
        logger.warn("could not create planned Node");
      }
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
  @Override
  public boolean canProvision(Label label) {
    // TODO: implement executor limits
    return true;
  }

  public MesosApi getMesosClient() {
    return this.mesos;
  }

  /**
   * Start a Jenkins agent.jar on Mesos.
   *
   * <p>Provide a callback for Jenkins to start a Node.
   *
   * @return A future reference to the launched node.
   */
  public Future<Node> startAgent() throws Exception {
    return mesos
        .enqueueAgent(this, 0.1, 32)
        .thenCompose(
            mesosSlave -> {
              try {
                Jenkins.getInstanceOrNull().addNode(mesosSlave);
                logger.info("waiting for slave to come online...");
                return mesosSlave.waitUntilOnlineAsync();
              } catch (Exception ex) {
                throw new CompletionException(ex);
              }
            })
        .toCompletableFuture();
  }
}
