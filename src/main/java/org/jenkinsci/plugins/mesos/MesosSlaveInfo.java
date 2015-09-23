package org.jenkinsci.plugins.mesos;

import hudson.model.Descriptor.FormException;

import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import hudson.model.Node;
import hudson.model.Node.Mode;

import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;

import org.apache.commons.lang.StringUtils;
import org.apache.mesos.Protos.ContainerInfo.DockerInfo.Network;
import org.kohsuke.stapler.DataBoundConstructor;

public class MesosSlaveInfo {
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
  private final RunAsUserInfo runAsUserInfo;

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
      List<URI> additionalURIs,
      RunAsUserInfo runAsUserInfo)
      throws NumberFormatException {
    this.slaveCpus = Double.parseDouble(slaveCpus);
    this.slaveMem = Integer.parseInt(slaveMem);
    this.maxExecutors = Integer.parseInt(maxExecutors);
    this.executorCpus = Double.parseDouble(executorCpus);
    this.executorMem = Integer.parseInt(executorMem);
    this.remoteFSRoot = StringUtils.isNotBlank(remoteFSRoot) ? remoteFSRoot
        .trim() : "jenkins";
    this.idleTerminationMinutes = Integer.parseInt(idleTerminationMinutes);
    this.labelString = StringUtils.isNotBlank(labelString) ? labelString
        : DEFAULT_LABEL_NAME;
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
    this.runAsUserInfo = runAsUserInfo;
  }

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

  public RunAsUserInfo getRunAsUserInfo() {
    return runAsUserInfo;
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

  public static class RunAsUserInfo {
      public  final static String TOKEN_USERNAME = "{USERNAME}";
      public  final static String TOKEN_SLAVE_COMMAND = "{SLAVE_CMD}";

      private final String username;
      private final String command;

      @DataBoundConstructor
      public RunAsUserInfo(String username, String command) {
          this.username = username;
          this.command = command;
    }

    public String getUsername() {
        return username;
    }

      public String getCommand() {
          return command;
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

    // TODO validate 1 to 65535
    private final Integer containerPort;
    private final Integer hostPort;
    private final String protocol;

    @DataBoundConstructor
    public PortMapping(Integer containerPort, Integer hostPort, String protocol) {
        this.containerPort = containerPort;
        this.hostPort = hostPort;
        this.protocol = protocol;
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

    @Override
    public String toString() {
        return (hostPort == null ? 0 : hostPort) + ":" + containerPort;
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
