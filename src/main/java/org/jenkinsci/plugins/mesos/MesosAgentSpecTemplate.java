package org.jenkinsci.plugins.mesos;

import com.mesosphere.usi.core.models.commands.LaunchPod;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.util.FormValidation;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.mesos.api.LaunchCommandBuilder;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

/** This is the Mesos agent pod spec config set by a user. */
public class MesosAgentSpecTemplate extends AbstractDescribableImpl<MesosAgentSpecTemplate> {

  private final String label;
  private final Set<LabelAtom> labelSet;

  private final Node.Mode mode;
  private final int idleTerminationMinutes;
  private final boolean reusable;
  private final double cpus;
  private final int mem;
  private final double disk;
  private final int minExecutors;
  private final int maxExecutors;
  private final String jnlpArgs;
  private final String additionalURIs;
  private final ContainerInfo containerInfo;

  @DataBoundConstructor
  public MesosAgentSpecTemplate(
      String label,
      Node.Mode mode,
      String cpus,
      String mem,
      int idleTerminationMinutes,
      int minExecutors,
      int maxExecutors,
      String disk,
      String jnlpArgs,
      String additionalURIs,
      ContainerInfo containerInfo) {
    this.label = label;
    this.labelSet = Label.parse(label);
    this.mode = mode;
    this.idleTerminationMinutes = idleTerminationMinutes;
    this.reusable = false; // TODO: DCOS_OSS-5048.
    this.cpus = Double.parseDouble(cpus);
    this.mem = Integer.parseInt(mem);
    this.minExecutors = minExecutors;
    this.maxExecutors = maxExecutors;
    this.disk = Double.parseDouble(disk);
    this.jnlpArgs = StringUtils.isNotBlank(jnlpArgs) ? jnlpArgs : "";
    this.additionalURIs = additionalURIs;
    this.containerInfo = containerInfo;
    validate();
  }

  private void validate() {}

  @Extension
  public static final class DescriptorImpl extends Descriptor<MesosAgentSpecTemplate> {

    public DescriptorImpl() {
      load();
    }

    /**
     * Validate that CPUs is a positive double.
     *
     * @param cpus The number of CPUs to user for agent.
     * @return Whether the supplied CPUs is valid.
     */
    public FormValidation doCheckCpus(@QueryParameter String cpus) {
      try {
        if (Double.valueOf(cpus) > 0.0) {
          return FormValidation.ok();
        } else {
          return FormValidation.error(cpus + " must be a positive floating-point-number.");
        }
      } catch (NumberFormatException e) {
        return FormValidation.error(cpus + " must be a positive floating-point-number.");
      }
    }
  }

  /**
   * Creates a LaunchPod command to to create a new Jenkins agent via USI
   *
   * @param jenkinsUrl the URL of the jenkins master.
   * @param name The name of the node to launch.
   * @return a LaunchPod command to be passed to USI.
   */
  public LaunchPod buildLaunchCommand(URL jenkinsUrl, String name)
      throws MalformedURLException, URISyntaxException {
    return new LaunchCommandBuilder()
        .withCpu(this.getCpu())
        .withMemory(this.getMemory())
        .withDisk(this.getDisk())
        .withName(name)
        .withJenkinsUrl(jenkinsUrl)
        .withContainerInfo(Optional.ofNullable(this.getContainerInfo()))
        .withJnlpArguments(this.getJnlpArgs())
        .build();
  }

  public String getLabel() {
    return this.label;
  }

  public Set<LabelAtom> getLabelSet() {
    return this.labelSet;
  }

  public Node.Mode getMode() {
    return this.mode;
  }

  /**
   * Generate a new unique name for a new agent. Note: multiple calls will yield different names.
   *
   * @return A new unique name for an agent.
   */
  public String generateName() {
    return String.format("jenkins-agent-%s-%s", this.label, UUID.randomUUID().toString());
  }

  public double getCpu() {
    return this.cpus;
  }

  public double getDisk() {
    return this.disk;
  }

  public int getMemory() {
    return this.mem;
  }

  public int getIdleTerminationMinutes() {
    return this.idleTerminationMinutes;
  }

  public boolean getReusable() {
    return this.reusable;
  }

  public String getAdditionalURIs() {
    return additionalURIs;
  }

  public int getMinExecutors() {
    return minExecutors;
  }

  public int getMaxExecutors() {
    return maxExecutors;
  }

  public String getJnlpArgs() {
    return jnlpArgs;
  }

  public ContainerInfo getContainerInfo() {
    return this.containerInfo;
  }

  public static class ContainerInfo extends AbstractDescribableImpl<ContainerInfo> {

    private final String type;
    private final String dockerImage;
    private final List<Volume> volumes;
    private final boolean dockerPrivilegedMode;
    private final boolean dockerForcePullImage;
    private boolean isDind;

    @SuppressFBWarnings("UUF_UNUSED_FIELD")
    private transient String networking;

    @SuppressFBWarnings("UUF_UNUSED_FIELD")
    private transient List<Object> portMappings;

    @SuppressFBWarnings("UUF_UNUSED_FIELD")
    private transient boolean dockerImageCustomizable;

    @SuppressFBWarnings("UUF_UNUSED_FIELD")
    private transient List<Object> parameters;

    @SuppressFBWarnings("UUF_UNUSED_FIELD")
    private transient List<Object> networkInfos;

    @SuppressFBWarnings("UUF_UNUSED_FIELD")
    private transient boolean useCustomDockerCommandShell;

    @SuppressFBWarnings("UUF_UNUSED_FIELD")
    private transient String customDockerCommandShell;

    @DataBoundConstructor
    public ContainerInfo(
        String type,
        String dockerImage,
        boolean isDind,
        boolean dockerPrivilegedMode,
        boolean dockerForcePullImage,
        List<Volume> volumes) {
      this.type = type;
      this.dockerImage = dockerImage;
      this.dockerPrivilegedMode = dockerPrivilegedMode;
      this.dockerForcePullImage = dockerForcePullImage;
      this.volumes = volumes;
      this.isDind = isDind;
    }

    public boolean getIsDind() {
      return this.isDind;
    }

    public String getType() {
      return type;
    }

    public String getDockerImage() {
      return dockerImage;
    }

    public boolean getDockerPrivilegedMode() {
      return dockerPrivilegedMode;
    }

    public boolean getDockerForcePullImage() {
      return dockerForcePullImage;
    }

    public List<Volume> getVolumes() {
      return volumes;
    }

    public List<Volume> getVolumesOrEmpty() {
      return (this.volumes != null) ? this.volumes : Collections.emptyList();
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<ContainerInfo> {

      public DescriptorImpl() {
        load();
      }
    }
  }

  public static class Volume extends AbstractDescribableImpl<Volume> {

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

    @Extension
    public static final class DescriptorImpl extends Descriptor<Volume> {

      public DescriptorImpl() {
        load();
      }
    }
  }
}
