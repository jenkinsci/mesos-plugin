package org.jenkinsci.plugins.mesos;

import hudson.model.Descriptor.FormException;

import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import javax.annotation.CheckForNull;

import hudson.model.Label;
import hudson.model.Node.Mode;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;

import org.apache.commons.lang.StringUtils;
import org.apache.mesos.Protos.ContainerInfo.DockerInfo.Network;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

public class MesosSlaveInfo {
  public static final int UNLIMITED_MAX_NODES = -1;
  private static final String DEFAULT_LABEL_NAME = "mesos";
  private static final String DEFAULT_JVM_ARGS = "-Xms16m -XX:+UseConcMarkSweepGC -Djava.net.preferIPv4Stack=true";
  private static final String JVM_ARGS_PATTERN = "-Xmx.+ ";
  private final double slaveCpus;
  private final int slaveMem; // MB.
  private final double executorCpus;
  private final int maxExecutors;
  private final int executorMem; // MB.
  private final String remoteFSRoot;
  private final int idleTerminationMinutes;
  private final String jvmArgs;
  private final String jnlpArgs;
  // Slave attributes JSON representation.
  private final JSONObject slaveAttributes;
  private final ExternalContainerInfo externalContainerInfo;
  private final ContainerInfo containerInfo;
  private final List<URI> additionalURIs;
  private final Mode mode;

  @CheckForNull
  private String labelString = DEFAULT_LABEL_NAME;

  private static final Logger LOGGER = Logger.getLogger(MesosSlaveInfo.class
      .getName());

  @DataBoundConstructor
  public MesosSlaveInfo(
      String labelString,
      Mode mode,
      String slaveCpus,
      String slaveMem,
      String maxExecutors,
      String executorCpus,
      String executorMem,
      String remoteFSRoot,
      String idleTerminationMinutes,
      String slaveAttributes,
      String jvmArgs,
      String jnlpArgs,
      ExternalContainerInfo externalContainerInfo,
      ContainerInfo containerInfo,
      List<URI> additionalURIs)
      throws NumberFormatException {
    this.slaveCpus = Double.parseDouble(slaveCpus);
    this.slaveMem = Integer.parseInt(slaveMem);
    this.maxExecutors = Integer.parseInt(maxExecutors);
    this.executorCpus = Double.parseDouble(executorCpus);
    this.executorMem = Integer.parseInt(executorMem);
    this.remoteFSRoot = StringUtils.isNotBlank(remoteFSRoot) ? remoteFSRoot
        .trim() : "jenkins";
    this.idleTerminationMinutes = Integer.parseInt(idleTerminationMinutes);
    this.labelString = StringUtils.isNotBlank(labelString) ? labelString : null;
    this.mode = mode != null ? mode : Mode.NORMAL;
    this.jvmArgs = StringUtils.isNotBlank(jvmArgs) ? cleanseJvmArgs(jvmArgs)
        : DEFAULT_JVM_ARGS;
    this.jnlpArgs = StringUtils.isNotBlank(jnlpArgs) ? jnlpArgs : "";
    this.externalContainerInfo = externalContainerInfo;
    this.containerInfo = containerInfo;
    this.additionalURIs = additionalURIs;

    // Parse the attributes provided from the cloud config
    JSONObject jsonObject = null;
    if (StringUtils.isNotBlank(slaveAttributes)) {
        try {
            jsonObject = (JSONObject) JSONSerializer.toJSON(slaveAttributes);
        } catch (JSONException e) {
            LOGGER.warning("Ignoring Mesos slave attributes JSON due to parsing error : "
                           + slaveAttributes);
        }
    }
    this.slaveAttributes = jsonObject;
  }

  @CheckForNull
  public String getLabelString() {
    return labelString;
  }

  public Mode getMode() {
    return mode;
  }

  public double getExecutorCpus() {
    return executorCpus;
  }

  public double getSlaveCpus() {
    return slaveCpus;
  }

  public int getSlaveMem() {
    return slaveMem;
  }

  public int getMaxExecutors() {
    return maxExecutors;
  }

  public int getExecutorMem() {
    return executorMem;
  }

  public String getRemoteFSRoot() {
    return remoteFSRoot;
  }

  public int getIdleTerminationMinutes() {
    return idleTerminationMinutes;
  }

  public JSONObject getSlaveAttributes() {
    return slaveAttributes;
  }

  public String getJvmArgs() {
    return jvmArgs;
  }

  public String getJnlpArgs() {
    return jnlpArgs;
  }

  public ExternalContainerInfo getExternalContainerInfo() {
    return externalContainerInfo;
  }

  public ContainerInfo getContainerInfo() {
    return containerInfo;
  }

  public List<URI> getAdditionalURIs() {
    return additionalURIs;
  }

  /**
   * Removes any additional {@code -Xmx} JVM args from the provided JVM
   * arguments. This is to ensure that the logic that sets the maximum heap
   * sized based on the memory available to the slave is not overriden by a
   * value provided via the configuration that may not work with the current
   * slave's configuration.
   *
   * @param jvmArgs
   *          the string of JVM arguments.
   * @return The cleansed JVM argument string.
   */
  private String cleanseJvmArgs(final String jvmArgs) {
    return jvmArgs.replaceAll(JVM_ARGS_PATTERN, "");
  }

  /**
   * Check if the label in the slave matches the provided label, either both are null or are the same.
   * 
   * @param label
   * @return Whether the slave label matches.
   */
  public boolean matchesLabel(@CheckForNull Label label) {
    return ((label == null) && (getLabelString() == null))
      || (getLabelString() != null && label != null && label.matches(Label.parse(getLabelString())));
  }

  public static class ExternalContainerInfo {
    private final String image;
    private final String options;

    @DataBoundConstructor
    public ExternalContainerInfo(String image, String options) {
      this.image = image;
      this.options = options;
    }

    public String getOptions() {
      return options;
    }

    public String getImage() {
      return image;
    }
  }

  public static class ContainerInfo {
    private final String type;
    private final String dockerImage;
    private final List<Volume> volumes;
    private final List<Parameter> parameters;
    private final String networking;
    private static final String DEFAULT_NETWORKING = Network.BRIDGE.name();
    private final List<PortMapping> portMappings;
    private final boolean useCustomDockerCommandShell;
    private final String customDockerCommandShell;
    private final Boolean dockerPrivilegedMode;
    private final Boolean dockerForcePullImage;

    @DataBoundConstructor
    public ContainerInfo(String type,
                         String dockerImage,
                         Boolean dockerPrivilegedMode,
                         Boolean dockerForcePullImage,
                         boolean useCustomDockerCommandShell,
                         String customDockerCommandShell,
                         List<Volume> volumes,
                         List<Parameter> parameters,
                         String networking,
                         List<PortMapping> portMappings) throws FormException {
      this.type = type;
      this.dockerImage = dockerImage;
      this.dockerPrivilegedMode = dockerPrivilegedMode;
      this.dockerForcePullImage = dockerForcePullImage;
      this.useCustomDockerCommandShell = useCustomDockerCommandShell;
      this.customDockerCommandShell = customDockerCommandShell;
      this.volumes = volumes;
      this.parameters = parameters;

      if (networking == null) {
          this.networking = DEFAULT_NETWORKING;
      } else {
          this.networking = networking;
      }

      if (Network.HOST.equals(Network.valueOf(networking))) {
          this.portMappings = Collections.emptyList();
      } else {
          this.portMappings = portMappings;
      }
    }

    public String getType() {
      return type;
    }

    public String getDockerImage() {
      return dockerImage;
    }

    public List<Volume> getVolumes() {
      return volumes;
    }

    public List<Parameter> getParameters() {
      return parameters;
    }

    public String getNetworking() {
      if (networking != null) {
        return networking;
      } else {
        return DEFAULT_NETWORKING;
      }
    }

    public List<PortMapping> getPortMappings() {
      if (portMappings != null) {
        return portMappings;
      } else {
        return Collections.emptyList();
      }
    }

    public boolean hasPortMappings() {
      return portMappings != null && !portMappings.isEmpty();
    }

    public Boolean getDockerPrivilegedMode() {
      return dockerPrivilegedMode;
    }

    public Boolean getDockerForcePullImage() {
      return dockerForcePullImage;
    }

    public boolean getUseCustomDockerCommandShell() {  return useCustomDockerCommandShell; }

    public String getCustomDockerCommandShell() {  return customDockerCommandShell; }

    public PortMapping getPortMapping(int containerPort) {
      for (PortMapping portMapping : portMappings) {
          if (portMapping.getContainerPort() == containerPort) {
              return portMapping;
          }
      }
    
      return null;
    }

  }

  public static class Parameter {
    private final String key;
    private final String value;

    @DataBoundConstructor
    public Parameter(String key, String value) {
      this.key = key;
      this.value = value;
    }

    public String getKey() {
      return key;
    }

    public String getValue() {
      return value;
    }
  }

  public static class PortMapping {

    private final Integer containerPort; // TODO validate 1 to 65535
    private final Integer hostPort;      // TODO validate 1 to 65535
    private final String protocol;
    private final String description;
    private final String urlFormat;

    @DataBoundConstructor
    public PortMapping(Integer containerPort, Integer hostPort, String protocol, String description, String urlFormat) {
        this.containerPort = containerPort;
        this.hostPort = hostPort;
        this.protocol = protocol;
        this.description = description;
        this.urlFormat = urlFormat;
    }

    public Integer getContainerPort() {
        return containerPort;
    }

    public Integer getHostPort() {
        return hostPort;
    }

    public String getProtocol() {
        return protocol;
    }

    public String getDescription() {
        return description;
    }

    public String getUrlFormat() {
        return urlFormat;
    }

    public boolean hasUrlFormat() {
        return urlFormat != null;
    }

    public String getFormattedUrl(String hostname, Integer hostPort) {
        String[] searchList = {"{hostname}", "{hostPort}"};
        String[] replaceList = {hostname, String.valueOf(hostPort)};
        return StringUtils.replaceEach(urlFormat, searchList, replaceList);

    }

  }

  public static class Volume {
    private final String containerPath;
    private final String hostPath;
    private final boolean readOnly;

    @DataBoundConstructor
    public Volume(String containerPath, String hostPath, boolean readOnly) {
      this.containerPath = containerPath;
      this.hostPath = hostPath;
      this.readOnly = readOnly;
    }

    public String getContainerPath() {
      return containerPath;
    }

    public String getHostPath() {
      return hostPath;
    }

    public boolean isReadOnly() {
      return readOnly;
    }
  }

  public static class URI {
    private final String value;
    private final boolean executable;
    private final boolean extract;

    @DataBoundConstructor
    public URI(String value, boolean executable, boolean extract) {
      this.value = value;
      this.executable = executable;
      this.extract = extract;
    }

    public String getValue() {
      return value;
    }

    public boolean isExecutable() {
      return executable;
    }

    public boolean isExtract() {
      return extract;
    }
  }
}
