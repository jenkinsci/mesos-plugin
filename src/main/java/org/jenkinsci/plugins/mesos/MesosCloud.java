package org.jenkinsci.plugins.mesos;

import static hudson.init.InitMilestone.PLUGINS_STARTED;
import static java.lang.Math.toIntExact;

import com.codahale.metrics.Timer;
import com.mesosphere.mesos.MasterDetector$;
import hudson.Extension;
import hudson.Util;
import hudson.init.Initializer;
import hudson.logging.LogRecorder;
import hudson.logging.LogRecorderManager;
import hudson.model.Descriptor;
import hudson.model.Descriptor.FormException;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.AbstractCloudImpl;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.FormValidation.Kind;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.Level;
import javax.annotation.CheckForNull;
import jenkins.metrics.api.Metrics;
import jenkins.model.Jenkins;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.concurrent.ExecutionContext.Implicits$;

/**
 * Jenkins Cloud implementation for Mesos.
 *
 * <p>The layout is inspired by the <a href="https://github.com/jenkinsci/nomad-plugin">Nomad
 * Plugin</a>.
 */
public class MesosCloud extends AbstractCloudImpl {

  private static final Logger logger = LoggerFactory.getLogger(MesosCloud.class.getName());

  private String master;

  private final String frameworkName;
  private String frameworkId;

  private String agentUser;
  private final String role;

  private final URL jenkinsURL;

  private transient Optional<String> sslCert;
  private transient Optional<DcosAuthorization> dcosAuthorization;

  private List<? extends MesosAgentSpecTemplate> mesosAgentSpecTemplates;

  public static class DcosAuthorization {

    private String secret;
    private String uid;

    public DcosAuthorization(String uid, String secret) {
      this.uid = uid;
      this.secret = secret;
    }

    public String getSecret() {
      return this.secret;
    }

    public String getUid() {
      return this.uid;
    }
  }

  // Legacy 1.x fields required for backwards compatibility
  private transient String nativeLibraryPath;
  private transient String description;
  private transient String slavesUser;
  private transient String credentialsId;
  private transient String cloudID;
  private transient boolean checkpoint;
  private transient boolean onDemandRegistration;
  private transient int declineOfferDuration;
  private transient List<MesosAgentSpecTemplate> slaveInfos;

  @DataBoundConstructor
  public MesosCloud(
      String mesosMasterUrl,
      String frameworkName,
      String frameworkId,
      String role,
      String agentUser,
      String jenkinsURL,
      List<? extends MesosAgentSpecTemplate> mesosAgentSpecTemplates)
      throws InterruptedException, ExecutionException, IOException {
    super("MesosCloud", null);

    try {
      this.master = mesosMasterUrl;
      this.jenkinsURL = new URL(jenkinsURL);
    } catch (MalformedURLException e) {
      throw new RuntimeException(
          String.format("Mesos Cloud URL validation failed for Jenkins %s", jenkinsURL), e);
    }

    if (selfIsMesosTask()) {
      String mesosSandbox = System.getenv("MESOS_SANDBOX");
      this.sslCert = Optional.ofNullable(loadDcosCert(mesosSandbox));
      this.dcosAuthorization = Optional.ofNullable(loadDcosAuthorization());
    } else {
      this.sslCert = Optional.empty();
      this.dcosAuthorization = Optional.empty();
    }

    this.agentUser = agentUser;
    this.role = role;
    this.mesosAgentSpecTemplates = Util.fixNull(mesosAgentSpecTemplates);
    this.frameworkName = frameworkName;

    this.frameworkId = frameworkId;
    if (StringUtils.isEmpty(this.frameworkId)) {
      this.frameworkId = UUID.randomUUID().toString();
    }

    logger.info(
        "Created Mesos cloud instance for framework {} and endpoint {}",
        this.frameworkId,
        this.master);
  }

  private Object readResolve() throws IOException {

    // Migration from 1.x
    if (this.agentUser == null && this.slavesUser != null) {
      this.agentUser = this.slavesUser;
    } else if (this.agentUser == null) {
      this.agentUser = "nobody";
    }

    if (this.frameworkId == null) {
      this.frameworkId = UUID.randomUUID().toString();
    }

    if (this.mesosAgentSpecTemplates == null && this.slaveInfos != null) {
      this.mesosAgentSpecTemplates = this.slaveInfos;
    } else if (this.mesosAgentSpecTemplates == null) {
      this.mesosAgentSpecTemplates = new ArrayList<>();
    }

    // Load details if we are running in DC/OS.
    if (selfIsMesosTask()) {
      String mesosSandbox = System.getenv("MESOS_SANDBOX");
      this.sslCert = Optional.ofNullable(loadDcosCert(mesosSandbox));
      this.dcosAuthorization = Optional.ofNullable(loadDcosAuthorization());
    } else {
      this.sslCert = Optional.empty();
      this.dcosAuthorization = Optional.empty();
    }

    return this;
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
    Metrics.metricRegistry()
        .meter(getMetricName(label, "provision", "request"))
        .mark(excessWorkload);

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
    if (label == null) return Optional.empty();

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
      throws InterruptedException, ExecutionException, IOException, FormException,
          URISyntaxException {
    final MesosApi mesosApi = MesosApi.getInstance(this);
    return mesosApi
        .enqueueAgent(name, spec)
        .thenCompose(
            mesosAgent -> {
              try {
                Jenkins.get().addNode(mesosAgent);
                logger.info("waiting for node {} to come online...", mesosAgent.getNodeName());

                Timer.Context provisionToReady =
                    Metrics.metricRegistry()
                        .timer(getMetricName(spec.getLabel(), "provision", "ready"))
                        .time();

                return mesosAgent
                    .waitUntilOnlineAsync(mesosApi.getMaterializer())
                    .thenApply(
                        node -> {
                          logger.info("Agent {} is online", name);
                          provisionToReady.stop();

                          return node;
                        })
                    .exceptionally(
                        e -> {
                          logger.info("Agent {} failed to come online", name);
                          provisionToReady.stop();

                          mesosApi.killAgent(name);
                          throw new CompletionException(e);
                        });
              } catch (Exception ex) {
                throw new CompletionException(ex);
              }
            })
        .toCompletableFuture();
  }

  /**
   * Checks whether the Jenkins master itself is running as a Mesos task and thus has the env var
   * MESOS_SANDBOX defined.
   *
   * @return
   */
  private static boolean selfIsMesosTask() {
    return System.getenv("MESOS_SANDBOX") != null;
  }

  /**
   * Constructs a metrics string.
   *
   * @param label The label of a node to launch.
   * @param method The method called.
   * @param metric The metric of the method.
   * @return The metric string for this cloud plugin.
   */
  private String getMetricName(Label label, String method, String metric) {
    String labelText = (label == null) ? "nolabel" : label.getDisplayName();
    return getMetricName(labelText, method, metric);
  }

  private String getMetricName(String label, String method, String metric) {
    return String.format("mesos.cloud.%s.%s.%s", label, method, metric);
  }

  /**
   * Loads the DC/OS SSL certificate in a Mesos task on an enterprise cluster.
   *
   * @param mesosSandbox The resolved MESOS_SANDBOX environment variable.
   * @return The certificate or null if the file does not exist.
   * @throws IOException
   */
  @CheckForNull
  private static String loadDcosCert(String mesosSandbox) throws IOException {
    final File sslCertFile = new File(mesosSandbox, ".ssl/ca-bundle.crt");
    if (sslCertFile.exists()) {
      return FileUtils.readFileToString(sslCertFile, StandardCharsets.US_ASCII);
    } else {
      return null;
    }
  }

  @CheckForNull
  private static DcosAuthorization loadDcosAuthorization() throws IOException {
    final String user = System.getenv("DCOS_SERVICE_ACCOUNT");
    final String privateKey = System.getenv("DCOS_SERVICE_ACCOUNT_PRIVATE_KEY");
    if (user != null && privateKey != null) {
      return new DcosAuthorization(user, privateKey);
    } else {
      return null;
    }
  }

  @Extension
  public static final class DescriptorImpl extends Descriptor<Cloud> {

    @Override
    public String getDisplayName() {
      return "Mesos Cloud";
    }

    private static final String LOG_RECORDER_NAME = "Mesos Cloud";

    public String getLogRecorderName() {
      return LOG_RECORDER_NAME;
    }

    /**
     * Preconfigure logger for easier debugging. This is a fork of the Azure Plugin.
     *
     * @see <a
     *     href="https://github.com/jenkinsci/azure-vm-agents-plugin/blob/master/src/main/java/com/microsoft/azure/vmagent/AzureVMCloud.java#L1024">Azure
     *     Plugin</a>
     * @param h The Jenkins instance.
     * @throws IOException
     */
    @Initializer(before = PLUGINS_STARTED)
    public static void addLogRecorder(Jenkins h) throws IOException {
      // avoid the failure in dynamic loading.
      if (!h.hasPermission(h.ADMINISTER)) {
        return;
      }
      LogRecorderManager manager = h.getLog();
      Map<String, LogRecorder> logRecorders = manager.logRecorders;
      if (!logRecorders.containsKey(LOG_RECORDER_NAME)) {
        LogRecorder recorder = new LogRecorder(LOG_RECORDER_NAME);
        recorder.targets.add(new LogRecorder.Target("org.jenkinsci.plugins.mesos", Level.ALL));
        recorder.targets.add(new LogRecorder.Target("com.mesosphere", Level.ALL));
        recorder.targets.add(new LogRecorder.Target("akka", Level.ALL));
        logRecorders.put(LOG_RECORDER_NAME, recorder);
        recorder.save();
      }
    }

    /**
     * Validates that the Mesos master URL is a valid URL.
     *
     * @param mesosMasterUrl The Mesos master URL supplied by the user.
     * @return Whether the URL is valid or not.
     */
    public FormValidation doCheckMesosMasterUrl(@QueryParameter String mesosMasterUrl) {
      if (MasterDetector$.MODULE$
          .apply(mesosMasterUrl, org.jenkinsci.plugins.mesos.Metrics.getInstance("no_name"))
          .isValid()) {
        return FormValidation.ok();
      } else {
        return FormValidation.error(
            mesosMasterUrl + " is not a valid URL or Zookeeper connection string.");
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
    public FormValidation doTestConnection(@QueryParameter("mesosMasterUrl") String mesosMasterUrl)
        throws ExecutionException, InterruptedException {
      FormValidation urlValidation = doCheckMesosMasterUrl(mesosMasterUrl);
      if (urlValidation.kind == Kind.ERROR) {
        return urlValidation;
      }

      mesosMasterUrl = mesosMasterUrl.trim();
      @CheckForNull HttpURLConnection urlConn = null;
      try {
        URL masterUrl =
            MasterDetector$.MODULE$
                .apply(mesosMasterUrl, org.jenkinsci.plugins.mesos.Metrics.getInstance("no_name"))
                .getMaster(Implicits$.MODULE$.global())
                .toCompletableFuture()
                .get();
        urlConn = (HttpURLConnection) masterUrl.openConnection();
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
    return Collections.unmodifiableList(this.mesosAgentSpecTemplates);
  }

  public String getMesosMasterUrl() {
    return this.master;
  }

  public String getFrameworkId() {
    return this.frameworkId;
  }

  public String getFrameworkName() {
    return this.frameworkName;
  }

  public String getJenkinsURL() {
    return this.jenkinsURL.toString();
  }

  public String getAgentUser() {
    return this.agentUser;
  }

  public String getRole() {
    return this.role;
  }

  public Optional<DcosAuthorization> getAuthorization() {
    return dcosAuthorization;
  }

  public Optional<String> getSslCert() {
    return sslCert;
  }

  /** @return Number of launching agents that are not connected yet. */
  public synchronized int getPending() throws InterruptedException, ExecutionException {
    final MesosApi mesosApi = MesosApi.getInstance(this);
    return toIntExact(
        mesosApi.getState().values().stream().filter(MesosJenkinsAgent::isPending).count());
  }
}
