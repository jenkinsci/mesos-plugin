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
import hudson.util.FormValidation.Kind;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import javax.annotation.CheckForNull;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
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
      throws InterruptedException, ExecutionException {
    super("MesosCloud", null);

    try {
      this.mesosMasterUrl = new URL(mesosMasterUrl);
      this.jenkinsUrl = new URL(jenkinsUrl);
    } catch (MalformedURLException e) {
      throw new RuntimeException("Mesos Cloud URL validation failed", e);
    }

    this.agentUser = agentUser;
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
        final String agentName = spec.generateName();
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
                logger.info("waiting for node {} to come online...", mesosAgent.getNodeName());
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

    /**
     * Validates that the Mesos master URL is a valid URL.
     *
     * @param mesosMasterUrl The Mesos master URL supplied by the user.
     * @return Whether the URL is valid or not.
     */
    public FormValidation doCheckMesosMasterUrl(@QueryParameter String mesosMasterUrl) {
      // This will change with https://jira.mesosphere.com/browse/DCOS-53671.
      if (isValidUrl(mesosMasterUrl)) {
        return FormValidation.ok();
      } else {
        return FormValidation.error(mesosMasterUrl + " is not a valid URL.");
      }
    }

    /**
     * Validates that the framework name is not empty.
     *
     * @param frameworkName The framework name set by the user.
     * @return Whether the framework name is empty or not.
     */
    public FormValidation doCheckFrameworkName(@QueryParameter String frameworkName) {
      frameworkName = frameworkName.trim();
      if (StringUtils.isEmpty(frameworkName)) {
        return FormValidation.error("The framework name must not be empty.");
      } else {
        return FormValidation.ok();
      }
    }

    /**
     * Validates that the role is valid.
     *
     * @see <a href="http://mesos.apache.org/documentation/latest/roles/#invalid-role-names">Mesos
     *     Roles</a>
     * @param role The Mesos role supplied by the user.
     * @return Whether the role is invalid or not.
     */
    public FormValidation doCheckRole(@QueryParameter String role) {
      if (StringUtils.isEmpty(role)) {
        return FormValidation.error("The role must not be empty.");
      } else if (".".equals(role) || "..".equals(role)) {
        return FormValidation.error("The role must not be '.' or '..'.");
      } else if (role.startsWith("-")) {
        return FormValidation.error("The role must not start with '-'.");
      } else if (role.matches(".*(\\s+|/+|\\\\+).*")) {
        return FormValidation.error(
            "The role must not contain any slash, backslash, or whitespace character.");
      } else {
        return FormValidation.ok();
      }
    }

    /**
     * Validates that the agent user is not empty and a valid UNIX user name.
     *
     * @see <a href="https://www.unix.com/man-page/linux/8/useradd/">man useradd(8)</a>
     * @param agentUser The agent user set by the user.
     * @return Whether the agent user is empty or not.
     */
    public FormValidation doCheckAgentUser(@QueryParameter String agentUser) {
      if (StringUtils.isEmpty(agentUser)) {
        return FormValidation.error("The agent user must not be empty.");
      } else if (!agentUser.matches("[a-z_][a-z0-9_-]*[$]?")) {
        return FormValidation.error("The agent user must be a valid UNIX user name.");
      } else {
        return FormValidation.ok();
      }
    }

    /**
     * Validates that the Jenkins URL is a valid URL.
     *
     * @param jenkinsUrl The Jenkins URL supplied by the user.
     * @return Whether the Jenkins URL is valid or not.
     */
    public FormValidation doCheckJenkinsUrl(@QueryParameter String jenkinsUrl) {
      if (isValidUrl(jenkinsUrl)) {
        return FormValidation.ok();
      } else {
        return FormValidation.error(jenkinsUrl + " is not a valid URL.");
      }
    }

    /**
     * Test connection from configuration page.
     *
     * @param mesosMasterUrl The Mesos master URL set by the user.
     * @return Whether the URL is correct and reachable or a validation error.
     */
    public FormValidation doTestConnection(
        @QueryParameter("mesosMasterUrl") String mesosMasterUrl) {
      FormValidation urlValidation = doCheckMesosMasterUrl(mesosMasterUrl);
      if (urlValidation.kind == Kind.ERROR) {
        return urlValidation;
      }

      mesosMasterUrl = mesosMasterUrl.trim();
      @CheckForNull HttpURLConnection urlConn = null;
      try {
        urlConn = (HttpURLConnection) new URL(mesosMasterUrl).openConnection();
        urlConn.connect();
        int code = urlConn.getResponseCode();

        // Response is OK or redirect.
        if (code <= 400) {
          return FormValidation.ok("Connected to Mesos successfully.");
        } else {
          return FormValidation.error("Status returned from url was: " + code);
        }
      } catch (IOException e) {
        logger.warn("Failed to connect to Mesos at {}", mesosMasterUrl, e);
        return FormValidation.error(e.getMessage());
      } finally {
        if (urlConn != null) {
          urlConn.disconnect();
        }
      }
    }

    /**
     * Validate that given string is a proper URL.
     *
     * @param url The URL as a string.
     * @return true if the string is a valid URL, false otherwise
     */
    private boolean isValidUrl(String url) {
      try {
        new URL(url);
        return true;
      } catch (MalformedURLException e) {
        return false;
      }
    }
  }

  // Getters
  public List<MesosAgentSpecTemplate> getMesosAgentSpecTemplates() {
    return this.mesosAgentSpecTemplates;
  }

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
