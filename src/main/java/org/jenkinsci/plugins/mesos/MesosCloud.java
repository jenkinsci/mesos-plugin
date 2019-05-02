package org.jenkinsci.plugins.mesos;

import static java.lang.Math.toIntExact;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Descriptor.FormException;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.AbstractCloudImpl;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import jenkins.model.Jenkins;
import org.apache.commons.lang.NotImplementedException;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
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

  private final URL mesosMasterUrl;
  private MesosApi mesosApi;

  private final String agentUser;

  private final URL jenkinsUrl;

  private final List<MesosAgentSpecTemplate> mesosAgentSpecTemplates;

  @DataBoundConstructor
  public MesosCloud(
      String mesosMasterUrl,
      String frameworkName,
      String role,
      String agentUser,
      String jenkinsUrl,
      List<MesosAgentSpecTemplate> mesosAgentSpecTemplates)
      throws InterruptedException, ExecutionException, MalformedURLException {
    super("MesosCloud", null);

    this.mesosMasterUrl = new URL(mesosMasterUrl);
    this.jenkinsUrl = new URL(jenkinsUrl);
    this.agentUser = agentUser; // TODO: default to system user
    this.mesosAgentSpecTemplates = mesosAgentSpecTemplates;

    mesosApi = new MesosApi(this.mesosMasterUrl, this.jenkinsUrl, agentUser, frameworkName, role);
  }

  /**
   * Provision one or more Jenkins nodes on Mesos.
   *
   * <p>The provisioning follows the Nomad plugin. The Jenkins agents is started as a Mesos task and
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
    final MesosAgentSpecTemplate spec =
        getSpecForLabel(label).get(); // TODO: handle case when optional is empty.

    while (excessWorkload > 0) {
      try {
        int minExecutors = spec.getMinExecutors();
        int maxExecutors = spec.getMaxExecutors();
        int numExecutors = Math.max(minExecutors, Math.min(excessWorkload, maxExecutors));
        logger.info(
            "Excess workload of {} provisioning new Jenkins agent on Mesos cluster with {} executors",
            excessWorkload,
            numExecutors);
        final String agentName = spec.getName();
        nodes.add(
            new NodeProvisioner.PlannedNode(agentName, startAgent(agentName, spec), numExecutors));
        excessWorkload -= numExecutors;
      } catch (Exception ex) {
        logger.warn("could not create planned node", ex);
      }
    }

    logger.info("Done queuing {} nodes", nodes.size());

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
    return getSpecForLabel(label).isPresent();
  }

  /** @return the {@link MesosAgentSpecTemplate} for passed label or empty optional. */
  private Optional<MesosAgentSpecTemplate> getSpecForLabel(Label label) {
    for (MesosAgentSpecTemplate spec : this.mesosAgentSpecTemplates) {
      if (label.matches(spec.getLabelSet())) {
        return Optional.of(spec);
      }
    }
    return Optional.empty();
  }

  /**
   * Start a Jenkins agent.jar on Mesos.
   *
   * <p>Provide a callback for Jenkins to start a Node.
   *
   * @param name Name of the Jenkins name and Mesos task.
   * @param spec The {@link MesosAgentSpecTemplate} that was configured for the Jenkins node.
   * @return A future reference to the launched node.
   */
  public Future<Node> startAgent(String name, MesosAgentSpecTemplate spec)
      throws IOException, FormException, URISyntaxException {
    return mesosApi
        .enqueueAgent(this, name, spec)
        .thenCompose(
            mesosAgent -> {
              try {
                Jenkins.get().addNode(mesosAgent);
                logger.info("waiting for node to come online...");
                return mesosAgent
                    .waitUntilOnlineAsync()
                    .thenApply(
                        node -> {
                          logger.info("Agent {} is online", name);
                          return node;
                        });
              } catch (Exception ex) {
                throw new CompletionException(ex);
              }
            })
        .toCompletableFuture();
  }

  @Extension
  public static class DescriptorImpl extends Descriptor<Cloud> {

    public DescriptorImpl() {
      load();
    }

    @Override
    public String getDisplayName() {
      return "Mesos Cloud";
    }

    // TODO: validate URLs

    /** Test connection from configuration page. */
    public FormValidation doTestConnection(
        @QueryParameter("mesosMasterUrl") String mesosMasterUrl) {
      throw new NotImplementedException("Connection testing is not supported yet.");
    }
  }

  // Getters

  public String getMesosMasterUrl() {
    return this.mesosMasterUrl.toString();
  }

  public String getFrameworkName() {
    return this.mesosApi.getFrameworkName();
  }

  public String getJenkinsUrl() {
    return this.jenkinsUrl.toString();
  }

  public String getAgentUser() {
    return "kjeschkies";
  }

  public String getRole() {
    return "*";
  }

  /** @return Number of launching agents that are not connected yet. */
  public synchronized int getPending() {
    return toIntExact(
        mesosApi.getState().values().stream().filter(MesosJenkinsAgent::isPending).count());
  }

  public MesosApi getMesosApi() {
    return this.mesosApi;
  }
}
