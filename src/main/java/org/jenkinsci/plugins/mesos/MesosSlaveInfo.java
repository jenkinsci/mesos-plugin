package org.jenkinsci.plugins.mesos;

import hudson.Util;
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

public class MesosSlaveInfo {
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
  private final boolean defaultSlave;
  // Slave attributes JSON representation.
  private final JSONObject slaveAttributes;
  private final ContainerInfo containerInfo;
  private final List<URI> additionalURIs;
  private final Mode mode;

  @CheckForNull
  private String labelString;

  private static final Logger LOGGER = Logger.getLogger(MesosSlaveInfo.class
      .getName());

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    MesosSlaveInfo that = (MesosSlaveInfo) o;

    if (Double.compare(that.slaveCpus, slaveCpus) != 0) return false;
    if (slaveMem != that.slaveMem) return false;
    if (Double.compare(that.executorCpus, executorCpus) != 0) return false;
    if (maxExecutors != that.maxExecutors) return false;
    if (executorMem != that.executorMem) return false;
    if (idleTerminationMinutes != that.idleTerminationMinutes) return false;
    if (remoteFSRoot != null ? !remoteFSRoot.equals(that.remoteFSRoot) : that.remoteFSRoot != null) return false;
    if (jvmArgs != null ? !jvmArgs.equals(that.jvmArgs) : that.jvmArgs != null) return false;
    if (jnlpArgs != null ? !jnlpArgs.equals(that.jnlpArgs) : that.jnlpArgs != null) return false;
    if (slaveAttributes != null ? !slaveAttributes.equals(that.slaveAttributes) : that.slaveAttributes != null)
      return false;
    if (containerInfo != null ? !containerInfo.equals(that.containerInfo) : that.containerInfo != null) return false;
    if (additionalURIs != null ? !additionalURIs.equals(that.additionalURIs) : that.additionalURIs != null)
      return false;
    if (mode != that.mode) return false;
    return labelString != null ? labelString.equals(that.labelString) : that.labelString == null;

  }

  @Override
  public int hashCode() {
    int result;
    long temp;
    temp = Double.doubleToLongBits(slaveCpus);
    result = (int) (temp ^ (temp >>> 32));
    result = 31 * result + slaveMem;
    temp = Double.doubleToLongBits(executorCpus);
    result = 31 * result + (int) (temp ^ (temp >>> 32));
    result = 31 * result + maxExecutors;
    result = 31 * result + executorMem;
    result = 31 * result + (remoteFSRoot != null ? remoteFSRoot.hashCode() : 0);
    result = 31 * result + idleTerminationMinutes;
    result = 31 * result + (jvmArgs != null ? jvmArgs.hashCode() : 0);
    result = 31 * result + (jnlpArgs != null ? jnlpArgs.hashCode() : 0);
    result = 31 * result + (slaveAttributes != null ? slaveAttributes.hashCode() : 0);
    result = 31 * result + (containerInfo != null ? containerInfo.hashCode() : 0);
    result = 31 * result + (additionalURIs != null ? additionalURIs.hashCode() : 0);
    result = 31 * result + (mode != null ? mode.hashCode() : 0);
    result = 31 * result + (labelString != null ? labelString.hashCode() : 0);
    return result;
  }

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
      String defaultSlave,
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
    this.labelString = Util.fixEmptyAndTrim(labelString);
    this.mode = mode != null ? mode : Mode.NORMAL;
    this.jvmArgs = StringUtils.isNotBlank(jvmArgs) ? cleanseJvmArgs(jvmArgs)
        : DEFAULT_JVM_ARGS;
    this.jnlpArgs = StringUtils.isNotBlank(jnlpArgs) ? jnlpArgs : "";
    this.defaultSlave = Boolean.valueOf(defaultSlave);
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

  public boolean isDefaultSlave() {
    return defaultSlave;
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

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      ExternalContainerInfo that = (ExternalContainerInfo) o;

      if (image != null ? !image.equals(that.image) : that.image != null) return false;
      return options != null ? options.equals(that.options) : that.options == null;

    }

    @Override
    public int hashCode() {
      int result = image != null ? image.hashCode() : 0;
      result = 31 * result + (options != null ? options.hashCode() : 0);
      return result;
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
                         List<PortMapping> portMappings) {
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

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      ContainerInfo that = (ContainerInfo) o;

      if (useCustomDockerCommandShell != that.useCustomDockerCommandShell) return false;
      if (type != null ? !type.equals(that.type) : that.type != null) return false;
      if (dockerImage != null ? !dockerImage.equals(that.dockerImage) : that.dockerImage != null) return false;
      if (volumes != null ? !volumes.equals(that.volumes) : that.volumes != null) return false;
      if (parameters != null ? !parameters.equals(that.parameters) : that.parameters != null) return false;
      if (networking != null ? !networking.equals(that.networking) : that.networking != null) return false;
      if (portMappings != null ? !portMappings.equals(that.portMappings) : that.portMappings != null) return false;
      if (customDockerCommandShell != null ? !customDockerCommandShell.equals(that.customDockerCommandShell) : that.customDockerCommandShell != null)
        return false;
      if (dockerPrivilegedMode != null ? !dockerPrivilegedMode.equals(that.dockerPrivilegedMode) : that.dockerPrivilegedMode != null)
        return false;
      return dockerForcePullImage != null ? dockerForcePullImage.equals(that.dockerForcePullImage) : that.dockerForcePullImage == null;

    }

    @Override
    public int hashCode() {
      int result = type != null ? type.hashCode() : 0;
      result = 31 * result + (dockerImage != null ? dockerImage.hashCode() : 0);
      result = 31 * result + (volumes != null ? volumes.hashCode() : 0);
      result = 31 * result + (parameters != null ? parameters.hashCode() : 0);
      result = 31 * result + (networking != null ? networking.hashCode() : 0);
      result = 31 * result + (portMappings != null ? portMappings.hashCode() : 0);
      result = 31 * result + (useCustomDockerCommandShell ? 1 : 0);
      result = 31 * result + (customDockerCommandShell != null ? customDockerCommandShell.hashCode() : 0);
      result = 31 * result + (dockerPrivilegedMode != null ? dockerPrivilegedMode.hashCode() : 0);
      result = 31 * result + (dockerForcePullImage != null ? dockerForcePullImage.hashCode() : 0);
      return result;
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

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      Parameter parameter = (Parameter) o;

      if (key != null ? !key.equals(parameter.key) : parameter.key != null) return false;
      return value != null ? value.equals(parameter.value) : parameter.value == null;

    }

    @Override
    public int hashCode() {
      int result = key != null ? key.hashCode() : 0;
      result = 31 * result + (value != null ? value.hashCode() : 0);
      return result;
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

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      PortMapping that = (PortMapping) o;

      if (containerPort != null ? !containerPort.equals(that.containerPort) : that.containerPort != null) return false;
      if (hostPort != null ? !hostPort.equals(that.hostPort) : that.hostPort != null) return false;
      return protocol != null ? protocol.equals(that.protocol) : that.protocol == null;

    }

    @Override
    public int hashCode() {
      int result = containerPort != null ? containerPort.hashCode() : 0;
      result = 31 * result + (hostPort != null ? hostPort.hashCode() : 0);
      result = 31 * result + (protocol != null ? protocol.hashCode() : 0);
      return result;
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

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      Volume volume = (Volume) o;

      if (readOnly != volume.readOnly) return false;
      if (containerPath != null ? !containerPath.equals(volume.containerPath) : volume.containerPath != null)
        return false;
      return hostPath != null ? hostPath.equals(volume.hostPath) : volume.hostPath == null;

    }

    @Override
    public int hashCode() {
      int result = containerPath != null ? containerPath.hashCode() : 0;
      result = 31 * result + (hostPath != null ? hostPath.hashCode() : 0);
      result = 31 * result + (readOnly ? 1 : 0);
      return result;
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

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      URI uri = (URI) o;

      if (executable != uri.executable) return false;
      if (extract != uri.extract) return false;
      return value != null ? value.equals(uri.value) : uri.value == null;

    }

    @Override
    public int hashCode() {
      int result = value != null ? value.hashCode() : 0;
      result = 31 * result + (executable ? 1 : 0);
      result = 31 * result + (extract ? 1 : 0);
      return result;
    }
  }
}
