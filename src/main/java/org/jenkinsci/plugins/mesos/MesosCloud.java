/*
 * Copyright 2013 Twitter, Inc. and other contributors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jenkinsci.plugins.mesos;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;

import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.model.Item;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Node.Mode;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;

import javax.servlet.ServletException;

import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.apache.mesos.MesosNativeLibrary;
import org.apache.mesos.Protos.ContainerInfo.DockerInfo.Network;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

public class MesosCloud extends Cloud {
  private static final String DEFAULT_DECLINE_OFFER_DURATION = "600000"; // 10 mins.
  private String nativeLibraryPath;
  private String master;
  private String description;
  private String frameworkName;
  private String role;
  private String slavesUser;
  private String credentialsId;
  /**
   * @deprecated Create credentials then use credentialsId instead.
   */
  @Deprecated
  private transient String principal;
  /**
   * @deprecated Create credentials then use credentialsId instead.
   */
  @Deprecated
  private transient String secret;
  private final boolean checkpoint; // Set true to enable checkpointing. False by default.
  private boolean onDemandRegistration; // If set true, this framework disconnects when there are no builds in the queue and re-registers when there are.
  private String jenkinsURL;
  private String declineOfferDuration;

  // Find the default values for these variables in
  // src/main/resources/org/jenkinsci/plugins/mesos/MesosCloud/config.jelly.
  private List<MesosSlaveInfo> slaveInfos;

  private static String staticMaster;

  private static final Logger LOGGER = Logger.getLogger(MesosCloud.class.getName());

  private static volatile boolean nativeLibraryLoaded = false;

  /**
   * We want to start the Mesos scheduler as part of the initialization of Jenkins
   * and after the cloud class values have been restored from persistence.If this is
   * the very first time, this method will be NOOP as MesosCloud is not registered yet.
   */

  @Initializer(after=InitMilestone.JOB_LOADED)
  public static void init() {
    Jenkins jenkins = Jenkins.getInstance();
    List<Node> slaves = jenkins.getNodes();

    // Turning the AUTOMATIC_SLAVE_LAUNCH flag off because the below slave removals
    // causes computer launch in other slaves that have not been removed yet.
    // To study how a slave removal updates the entire list, one can refer to
    // Hudson NodeProvisioner class and follow this method chain removeNode() ->
    // setNodes() -> updateComputerList() -> updateComputer().
    Jenkins.AUTOMATIC_SLAVE_LAUNCH = false;
    for (Node n : slaves) {
      //Remove all slaves that were persisted when Jenkins shutdown.
      if (n instanceof MesosSlave) {
        ((MesosSlave)n).terminate();
      }
    }

    // Turn it back on for future real slaves.
    Jenkins.AUTOMATIC_SLAVE_LAUNCH = true;

    for (Cloud c : jenkins.clouds) {
      if( c instanceof MesosCloud) {
        // Register mesos framework on init, if on demand registration is not enabled.
        if (!((MesosCloud) c).isOnDemandRegistration()) {
          ((MesosCloud)c).restartMesos();
        }
      }
    }
  }

  @DataBoundConstructor
  public MesosCloud(
      String nativeLibraryPath,
      String master,
      String description,
      String frameworkName,
      String role,
      String slavesUser,
      String principal,
      String secret,
      List<MesosSlaveInfo> slaveInfos,
      boolean checkpoint,
      boolean onDemandRegistration,
      String jenkinsURL,
      String declineOfferDuration) throws NumberFormatException {
    this("MesosCloud", nativeLibraryPath, master, description, frameworkName, role,
         slavesUser, principal, secret, slaveInfos, checkpoint, onDemandRegistration,
         jenkinsURL);
  }

  /**
   * Constructor, which also allows to specify a custom name.
   * @throws NumberFormatException Numeric parameter parsing error
   * @since 0.9.0
   */
  protected MesosCloud(
      String cloudName,
      String nativeLibraryPath,
      String master,
      String description,
      String frameworkName,
      String role,
      String slavesUser,
      String principal,
      String secret,
      List<MesosSlaveInfo> slaveInfos,
      boolean checkpoint,
      boolean onDemandRegistration,
      String jenkinsURL) throws NumberFormatException {
    super(cloudName);

    this.nativeLibraryPath = nativeLibraryPath;
    this.master = master;
    this.description = description;
    this.frameworkName = frameworkName;
    this.role = role;
    this.slavesUser = slavesUser;
    this.principal = principal;
    this.secret = secret;
    migrateToCredentials();
    this.slaveInfos = slaveInfos;
    this.checkpoint = checkpoint;
    this.onDemandRegistration = onDemandRegistration;
    this.setJenkinsURL(jenkinsURL);
    this.setDeclineOfferDuration(declineOfferDuration);
    if(!onDemandRegistration) {
	    JenkinsScheduler.SUPERVISOR_LOCK.lock();
	    try {
	      restartMesos();
	    } finally {
	      JenkinsScheduler.SUPERVISOR_LOCK.unlock();
	    }
    }
  }

  /**
   * Copy constructor.
   * Allows to create copies of the original mesos cloud. Since it's a singleton
   * by design, this method also allows specifying a new name.
   * @param name Name of the cloud to be created
   * @param source Source Mesos cloud implementation
   * @since 0.9.0
   */
  public MesosCloud(@Nonnull String name, @Nonnull MesosCloud source) {
      this(name, source.nativeLibraryPath, source.master, source.description, source.frameworkName,
           source.role, source.slavesUser, source.principal, source.secret, source.slaveInfos,
           source.checkpoint, source.onDemandRegistration, source.jenkinsURL);
      this.credentialsId = source.getCredentialsId();
  }

  // Since MesosCloud is used as a key to a Hashmap, we need to set equals/hashcode
  // or lookups won't work if any fields are changed.  Use master string as the key since
  // the rest of this code assumes it is unique among the Cloud objects.
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    MesosCloud that = (MesosCloud) o;

    if (master != null ? !master.equals(that.master) : that.master != null) return false;

    return true;
  }

  @Override
  public int hashCode() {
    return master != null ? master.hashCode() : 0;
  }

  public void restartMesos() {

    if(!nativeLibraryLoaded) {
      // First, we attempt to load the library from the given path.
      // If unsuccessful, we attempt to load using 'MesosNativeLibrary.load()'.
      try {
          MesosNativeLibrary.load(nativeLibraryPath);
      } catch (UnsatisfiedLinkError error) {
          LOGGER.warning("Failed to load native Mesos library from '" + nativeLibraryPath +
                         "': " + error.getMessage());
          MesosNativeLibrary.load();
      }
      nativeLibraryLoaded = true;
    }

    // Default to root URL in Jenkins global configuration.
    String jenkinsRootURL = Jenkins.getInstance().getRootUrl();

    // If 'jenkinsURL' parameter is provided in mesos plugin configuration, then that should take precedence.
    if(StringUtils.isNotBlank(jenkinsURL)) {
      jenkinsRootURL = jenkinsURL;
    }

    // Restart the scheduler if the master has changed or a scheduler is not up.
    if (!master.equals(staticMaster) || !Mesos.getInstance(this).isSchedulerRunning()) {
      if (!master.equals(staticMaster)) {
        LOGGER.info("Mesos master changed, restarting the scheduler");
        staticMaster = master;
      } else {
        LOGGER.info("Scheduler was down, restarting the scheduler");
      }

      Mesos.getInstance(this).stopScheduler();
      Mesos.getInstance(this).startScheduler(jenkinsRootURL, this);
    } else {
      Mesos.getInstance(this).updateScheduler(jenkinsRootURL, this);
      LOGGER.info("Mesos master has not changed, leaving the scheduler running");
    }

  }

  /**
   * Returns the credentials object associated with the stored credentialsId.
   *
   * @return The credentials object associated with the stored credentialsId. May be null if credentialsId is null or
   * if there is no credentials associated with the given id.
   */
  public StandardUsernamePasswordCredentials getCredentials() {
    if (credentialsId == null) {
      return null;
    } else {
      List<DomainRequirement> domainRequirements = (master == null) ? Collections.<DomainRequirement>emptyList()
              : URIRequirementBuilder.fromUri(master.trim()).build();
      Jenkins jenkins = Jenkins.getInstance();
      return CredentialsMatchers.firstOrNull(CredentialsProvider
                      .lookupCredentials(StandardUsernamePasswordCredentials.class, jenkins, ACL.SYSTEM, domainRequirements),
              CredentialsMatchers.withId(credentialsId)
      );
    }
  }

  @Override
  public Collection<PlannedNode> provision(Label label, int excessWorkload) {
    List<PlannedNode> list = new ArrayList<PlannedNode>();
    final MesosSlaveInfo slaveInfo = getSlaveInfo(slaveInfos, label);

    try {
      while (excessWorkload > 0 && !Jenkins.getInstance().isQuietingDown()) {
        // Start the scheduler if it's not already running.
        if (onDemandRegistration) {
          JenkinsScheduler.SUPERVISOR_LOCK.lock();
          try {
            LOGGER.fine("Checking if scheduler is running");
            if (!Mesos.getInstance(this).isSchedulerRunning()) {
              restartMesos();
            }
          } finally {
            JenkinsScheduler.SUPERVISOR_LOCK.unlock();
          }
        }
        final int numExecutors = Math.min(excessWorkload, slaveInfo.getMaxExecutors());
        excessWorkload -= numExecutors;
        LOGGER.info("Provisioning Jenkins Slave on Mesos with " + numExecutors +
                    " executors. Remaining excess workload: " + excessWorkload + " executors)");
        list.add(new PlannedNode(this.getDisplayName(), Computer.threadPoolForRemoting
            .submit(new Callable<Node>() {
              public Node call() throws Exception {
                MesosSlave s = doProvision(numExecutors, slaveInfo);
                // We do not need to explicitly add the Node here because that is handled by
                // hudson.slaves.NodeProvisioner::update() that checks the result from the
                // Future and adds the node. Though there is duplicate node addition check
                // because of this early addition there is difference in job scheduling and
                // best to avoid it.
                return s;
              }
            }), numExecutors));
      }
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Failed to create instances on Mesos", e);
      return Collections.emptyList();
    }

    return list;
  }

  private MesosSlave doProvision(int numExecutors, MesosSlaveInfo slaveInfo) throws Descriptor.FormException, IOException {
    return new MesosSlave(this, MesosUtils.buildNodeName(slaveInfo.getLabelString()), numExecutors, slaveInfo);
  }

  public List<MesosSlaveInfo> getSlaveInfos() {
    return slaveInfos;
  }

  public void setSlaveInfos(List<MesosSlaveInfo> slaveInfos) {
    this.slaveInfos = slaveInfos;
  }

  @Override
  public boolean canProvision(Label label) {
    // Provisioning is simply creating a task for a jenkins slave.
    // We can provision a Mesos slave as long as the job's label matches any
    // item in the list of configured Mesos labels.
    // TODO(vinod): The framework may not have the resources necessary
    // to start a task when it comes time to launch the slave.
    if (slaveInfos != null) {
      for (MesosSlaveInfo slaveInfo : slaveInfos) {
        if (slaveInfo.matchesLabel(label)) {
          return true;
        }
      }
    }
    return false;
  }

  public String getNativeLibraryPath() {
    return this.nativeLibraryPath;
  }

  public void setNativeLibraryPath(String nativeLibraryPath) {
    this.nativeLibraryPath = nativeLibraryPath;
  }

  public String getMaster() {
    return this.master;
  }

  public void setMaster(String master) {
    this.master = master;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getFrameworkName() {
    return frameworkName;
  }

  public void setFrameworkName(String frameworkName) {
    this.frameworkName = frameworkName;
  }

  public String getRole() {
    return role;
  }

  public void setRole(String role) {
    this.role = role;
  }

  public String getSlavesUser() {
    return slavesUser;
  }

  public void setSlavesUser(String slavesUser) {
    this.slavesUser = slavesUser;
  }

  /**
   * @deprecated Use MesosCloud#getCredentials().getUsername() instead.
   * @return
   */
  @Deprecated
  public String getPrincipal() {
    StandardUsernamePasswordCredentials credentials = getCredentials();
    return credentials == null ? "jenkins" : credentials.getUsername();
  }

  /**
   * @deprecated Define credentials and use MesosCloud#setCredentialsId instead.
   * @param principal
   */
  @Deprecated
  public void setPrincipal(String principal) {
    this.principal = principal;
  }

  /**
   * @return The credentialsId to use for this mesos cloud
   */
  public String getCredentialsId() {
    return credentialsId;
  }

  @DataBoundSetter
  public void setCredentialsId(String credentialsId) {
    this.credentialsId = credentialsId;
  }

  /**
   * @deprecated Use MesosCloud#getCredentials().getPassword() instead.
   * @return
   */
  @Deprecated
  public String getSecret() {
    StandardUsernamePasswordCredentials credentials = getCredentials();
    return credentials == null ? "" : Secret.toString(credentials.getPassword());
  }

  /**
   * @deprecated Define credentials and use MesosCloud#setCredentialsId instead.
   * @param secret
   */
  @Deprecated
  public void setSecret(String secret) {
    this.secret = secret;
  }

  public boolean isOnDemandRegistration() {
    return onDemandRegistration;
  }

  public void setOnDemandRegistration(boolean onDemandRegistration) {
    this.onDemandRegistration = onDemandRegistration;
  }

  @Override
  public DescriptorImpl getDescriptor() {
    return (DescriptorImpl) super.getDescriptor();
  }

  public static MesosCloud get() {
    return Hudson.getInstance().clouds.get(MesosCloud.class);
  }

  /**
  * @return the checkpoint
  */
  public boolean isCheckpoint() {
    return checkpoint;
  }

  private MesosSlaveInfo getSlaveInfo(List<MesosSlaveInfo> slaveInfos,
      Label label) {
    for (MesosSlaveInfo slaveInfo : slaveInfos) {
      if (slaveInfo.matchesLabel(label)) {
        return slaveInfo;
      }
    }
    return null;
  }

  /**
  * Retrieves the slaveattribute corresponding to label name.
  *
  * @param labelName The Jenkins label name.
  * @return slaveattribute as a JSONObject.
  */

  public JSONObject getSlaveAttributeForLabel(String labelName) {
    for (MesosSlaveInfo slaveInfo : slaveInfos) {
      if(labelName != null) {
        if (labelName.equals(slaveInfo.getLabelString())) {
          return slaveInfo.getSlaveAttributes();
        }
      } else {
        if (slaveInfo.getLabelString() == null) {
          return slaveInfo.getSlaveAttributes();
        }
      }
    }
    return null;
  }

  protected Object readResolve() {
    migrateToCredentials();
    if (role == null) {
      role = "*";
    }
    return this;
  }

  /**
   * Migrate principal/secret to credentials
   */
  private void migrateToCredentials() {
    if (principal != null) {
      List<DomainRequirement> domainRequirements = (master == null) ? Collections.<DomainRequirement>emptyList()
        : URIRequirementBuilder.fromUri(master.trim()).build();
      Jenkins jenkins = Jenkins.getInstance();
      // Look up existing credentials with the same username.
      List<StandardUsernamePasswordCredentials> credentials = CredentialsMatchers.filter(CredentialsProvider
        .lookupCredentials(StandardUsernamePasswordCredentials.class, jenkins, ACL.SYSTEM, domainRequirements),
        CredentialsMatchers.withUsername(principal)
      );
      for (StandardUsernamePasswordCredentials cred: credentials) {
        if (StringUtils.equals(secret, Secret.toString(cred.getPassword()))) {
          // If some credentials have the same username/password, use those.
          this.credentialsId = cred.getId();
          break;
        }
      }
      if (credentialsId == null) {
        // If we couldn't find any existing credentials,
        // create new credentials with the principal and secret and use it.
        StandardUsernamePasswordCredentials newCredentials = new UsernamePasswordCredentialsImpl(
          CredentialsScope.SYSTEM, null, null, principal, secret);
        SystemCredentialsProvider.getInstance().getCredentials().add(newCredentials);
        this.credentialsId = newCredentials.getId();
      }
      principal = null;
      secret = null;
    }
  }

  public String getJenkinsURL() {
	return jenkinsURL;
}

public void setJenkinsURL(String jenkinsURL) {
	this.jenkinsURL = jenkinsURL;
}

  public String getDeclineOfferDuration() {
    if (declineOfferDuration == null) {
      return DEFAULT_DECLINE_OFFER_DURATION;
    } else {
      return declineOfferDuration;
    }
  }

  public double getDeclineOfferDurationDouble() {
    return Double.parseDouble(getDeclineOfferDuration());
  }

  public void setDeclineOfferDuration(String declineOfferDuration) {
    try {
      if (declineOfferDuration == null) {
        LOGGER.info("Missing declineOfferDuration. Using default " + DEFAULT_DECLINE_OFFER_DURATION + " ms.");
        this.declineOfferDuration = DEFAULT_DECLINE_OFFER_DURATION;
      } else {
        double duration = Double.parseDouble(declineOfferDuration);
        if (duration >= 1000) {
          this.declineOfferDuration = declineOfferDuration;
        } else {
          LOGGER.warning("Minimum declineOfferDuration (1000) > " + declineOfferDuration
              + ". Using default " + DEFAULT_DECLINE_OFFER_DURATION + " ms.");
          this.declineOfferDuration = DEFAULT_DECLINE_OFFER_DURATION;
        }
      }
    } catch (NumberFormatException e) {
      LOGGER.warning("Unable to parse declineOfferDuration: " + declineOfferDuration
          + ". Using default " + DEFAULT_DECLINE_OFFER_DURATION + " ms.");
      this.declineOfferDuration = DEFAULT_DECLINE_OFFER_DURATION;
    }
  }

@Extension
  public static class DescriptorImpl extends Descriptor<Cloud> {
    private String nativeLibraryPath;
    private String master;
    private String description;
    private String frameworkName;
    private String role;
    private String slavesUser;
    private String principal;
    private String secret;
    private String slaveAttributes;
    private boolean checkpoint;
    private String jenkinsURL;
    private List<MesosSlaveInfo> slaveInfos;

    @Override
    public String getDisplayName() {
      return "Mesos Cloud";
    }

    @Restricted(DoNotUse.class) // Stapler only.
    @SuppressWarnings("unused") // Used by stapler.
    public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item item, @QueryParameter String master) {
      List<DomainRequirement> domainRequirements = (master == null) ? Collections.<DomainRequirement>emptyList()
        : URIRequirementBuilder.fromUri(master.trim()).build();
      return new StandardListBoxModel().withEmptySelection().withMatching(
        CredentialsMatchers.instanceOf(UsernamePasswordCredentials.class),
        CredentialsProvider.lookupCredentials(StandardUsernamePasswordCredentials.class, item, null, domainRequirements)
      );
    }

    @Override
    public boolean configure(StaplerRequest request, JSONObject object)
        throws FormException {
      LOGGER.info(object.toString());
      nativeLibraryPath = object.getString("nativeLibraryPath");
      master = object.getString("master");
      description = object.getString("description");
      frameworkName = object.getString("frameworkName");
      role = object.getString("role");
      principal = object.getString("principal");
      secret = object.getString("secret");
      slaveAttributes = object.getString("slaveAttributes");
      checkpoint = object.getBoolean("checkpoint");
      jenkinsURL = object.getString("jenkinsURL");
      slavesUser = object.getString("slavesUser");
      slaveInfos = new ArrayList<MesosSlaveInfo>();
      JSONArray labels = object.getJSONArray("slaveInfos");
      if (labels != null) {
        for (int i = 0; i < labels.size(); i++) {
          JSONObject label = labels.getJSONObject(i);
          if (label != null) {
            MesosSlaveInfo.ExternalContainerInfo externalContainerInfo = null;
            if (label.has("externalContainerInfo")) {
              JSONObject externalContainerInfoJson = label
                  .getJSONObject("externalContainerInfo");
              externalContainerInfo = new MesosSlaveInfo.ExternalContainerInfo(
                  externalContainerInfoJson.getString("image"),
                  externalContainerInfoJson.getString("options"));
            }

            MesosSlaveInfo.ContainerInfo containerInfo = null;
            if (label.has("containerInfo")) {
              JSONObject containerInfoJson = label
                  .getJSONObject("containerInfo");
              List<MesosSlaveInfo.Volume> volumes = new ArrayList<MesosSlaveInfo.Volume>();
              if (containerInfoJson.has("volumes")) {
                JSONArray volumesJson = containerInfoJson
                    .getJSONArray("volumes");
                for (Object obj : volumesJson) {
                  JSONObject volumeJson = (JSONObject) obj;
                  volumes
                      .add(new MesosSlaveInfo.Volume(volumeJson
                          .getString("containerPath"), volumeJson
                          .getString("hostPath"), volumeJson
                          .getBoolean("readOnly")));
                }
              }

              List<MesosSlaveInfo.Parameter> parameters = new ArrayList<MesosSlaveInfo.Parameter>();

              if (containerInfoJson.has("parameters")) {
                JSONArray parametersJson = containerInfoJson.getJSONArray("parameters");
                for (Object obj : parametersJson) {
                  JSONObject parameterJson = (JSONObject) obj;
                  parameters.add(new MesosSlaveInfo.Parameter(parameterJson.getString("key"), parameterJson.getString("value")));
                }
              }

              List<MesosSlaveInfo.PortMapping> portMappings = new ArrayList<MesosSlaveInfo.PortMapping>();

              final String networking = containerInfoJson.getString("networking");
              if (Network.BRIDGE.equals(Network.valueOf(networking)) && containerInfoJson.has("portMappings")) {
                JSONArray portMappingsJson = containerInfoJson
                    .getJSONArray("portMappings");
                for (Object obj : portMappingsJson) {
                  JSONObject portMappingJson = (JSONObject) obj;
                  portMappings.add(new MesosSlaveInfo.PortMapping(
                          portMappingJson.getInt("containerPort"),
                          portMappingJson.getInt("hostPort"),
                          portMappingJson.getString("protocol")));
                }
              }

              containerInfo = new MesosSlaveInfo.ContainerInfo(
                  containerInfoJson.getString("type"),
                  containerInfoJson.getString("dockerImage"),
                  containerInfoJson.getBoolean("dockerPrivilegedMode"),
                  containerInfoJson.getBoolean("dockerForcePullImage"),
                  containerInfoJson.getBoolean("useCustomDockerCommandShell"),
                  containerInfoJson.getString ("customDockerCommandShell"),
                  volumes,
                  parameters,
                  networking,
                  portMappings);
            }

            List<MesosSlaveInfo.URI> additionalURIs = new ArrayList<MesosSlaveInfo.URI>();
            if (label.has("additionalURIs")) {
              JSONArray additionalURIsJson = label.getJSONArray("additionalURIs");
              for (Object obj : additionalURIsJson) {
                JSONObject URIJson = (JSONObject) obj;
                additionalURIs.add(new MesosSlaveInfo.URI(
                    URIJson.getString("value"),
                    URIJson.getBoolean("executable"),
                    URIJson.getBoolean("extract")));
              }
            }
            MesosSlaveInfo slaveInfo = new MesosSlaveInfo(
                object.getString("labelString"),
                (Mode) object.get("mode"),
                object.getString("slaveCpus"),
                object.getString("slaveMem"),
                object.getString("maxExecutors"),
                object.getString("executorCpus"),
                object.getString("executorMem"),
                object.getString("remoteFSRoot"),
                object.getString("idleTerminationMinutes"),
                object.getString("slaveAttributes"),
                object.getString("jvmArgs"),
                object.getString("jnlpArgs"),
                externalContainerInfo,
                containerInfo,
                additionalURIs);
            slaveInfos.add(slaveInfo);
          }
        }
      }
      save();
      return super.configure(request, object);
    }

    /**
     * Test connection from configuration page.
     */
    public FormValidation doTestConnection(
        @QueryParameter("master") String master,
        @QueryParameter("nativeLibraryPath") String nativeLibraryPath)
        throws IOException, ServletException {
      master = master.trim();

      if (master.equals("local")) {
        return FormValidation.warning("'local' creates a local mesos cluster");
      }

      if (master.startsWith("zk://")) {
        return FormValidation.warning("Zookeeper paths can be used, but the connection cannot be " +
            "tested prior to saving this page.");
      }

      if (master.startsWith("http://")) {
        return FormValidation.error("Please omit 'http://'.");
      }

      if (!nativeLibraryPath.startsWith("/")) {
        return FormValidation.error("Please provide an absolute path");
      }

      try {
        // URL requires the protocol to be explicitly specified.
        HttpURLConnection urlConn =
          (HttpURLConnection) new URL("http://" + master).openConnection();
        urlConn.connect();
        int code = urlConn.getResponseCode();
        urlConn.disconnect();

        if (code == 200) {
          return FormValidation.ok("Connected to Mesos successfully");
        } else {
          return FormValidation.error("Status returned from url was " + code);
        }
      } catch (IOException e) {
        LOGGER.log(Level.WARNING, "Failed to connect to Mesos " + master, e);
        return FormValidation.error(e.getMessage());
      }
    }

    public FormValidation doCheckSlaveCpus(@QueryParameter String value) {
      return doCheckCpus(value);
    }

    public FormValidation doCheckExecutorCpus(@QueryParameter String value) {
      return doCheckCpus(value);
    }

    private FormValidation doCheckCpus(@QueryParameter String value) {
      boolean valid = true;
      String errorMessage = "Invalid CPUs value, it should be a positive decimal.";

      if (StringUtils.isBlank(value)) {
        valid = false;
      } else {
        try {
          if (Double.parseDouble(value) < 0) {
            valid = false;
          }
        } catch (NumberFormatException e) {
          valid = false;
        }
      }
      return valid ? FormValidation.ok() : FormValidation.error(errorMessage);
    }

    public FormValidation doCheckRemoteFSRoot(@QueryParameter String value) {
      String errorMessage = "Invalid Remote FS Root - should be non-empty. It will be defaulted to \"jenkins\".";

      return StringUtils.isNotBlank(value) ? FormValidation.ok() : FormValidation.error(errorMessage);
    }
  }
}
