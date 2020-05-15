package org.jenkinsci.plugins.mesos;

import com.mesosphere.usi.core.models.commands.LaunchPod;
import com.mesosphere.usi.core.models.template.FetchUri;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.commons.lang.StringUtils;
import org.apache.mesos.v1.Protos.ContainerInfo.DockerInfo.Network;
import org.jenkinsci.plugins.mesos.api.LaunchCommandBuilder;
import org.jenkinsci.plugins.mesos.api.RunTemplateFactory.ContainerInfoTaskInfoBuilder;
import org.jenkinsci.plugins.mesos.config.models.faultdomain.DomainFilterModel;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Option;

/** This is the Mesos agent pod spec config set by a user. */
public class MesosAgentSpecTemplate extends AbstractDescribableImpl<MesosAgentSpecTemplate> {

  private static final Logger logger = LoggerFactory.getLogger(MesosAgentSpecTemplate.class);

  private final String label;
  private Set<LabelAtom> labelSet;

  private final Node.Mode mode;
  private final int idleTerminationMinutes;
  private final boolean reusable;
  private final double cpus;
  private final int mem;
  private final double disk;
  private final int minExecutors;
  private final int maxExecutors;
  private final String jnlpArgs;
  private final String agentAttributes;
  private final List<MesosSlaveInfo.URI> additionalURIs;
  private final LaunchCommandBuilder.AgentCommandStyle agentCommandStyle;
  private final ContainerInfo containerInfo;
  private final DomainFilterModel domainFilterModel;

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
      String agentAttributes,
      List<MesosSlaveInfo.URI> additionalURIs,
      ContainerInfo containerInfo,
      LaunchCommandBuilder.AgentCommandStyle agentCommandStyle,
      DomainFilterModel domainFilterModel) {
    this.label = label;
    this.mode = mode;
    this.idleTerminationMinutes = idleTerminationMinutes;
    this.reusable = false; // TODO: DCOS_OSS-5048.
    this.cpus = (cpus != null) ? Double.parseDouble(cpus) : 0.1;
    this.mem = Integer.parseInt(mem);
    this.minExecutors = minExecutors;
    this.maxExecutors = maxExecutors;
    this.disk = (disk != null) ? Double.parseDouble(disk) : 0.0;
    this.jnlpArgs = StringUtils.isNotBlank(jnlpArgs) ? jnlpArgs : "";
    this.agentAttributes = StringUtils.isNotBlank(agentAttributes) ? agentAttributes : "";
    this.additionalURIs = (additionalURIs != null) ? additionalURIs : Collections.emptyList();
    this.containerInfo = containerInfo;
    this.domainFilterModel = domainFilterModel;
    this.agentCommandStyle = agentCommandStyle;
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
   * @param role The Mesos role for the task.
   * @return a LaunchPod command to be passed to USI.
   * @throws MalformedURLException If a fetch URL is not well formed.
   * @throws URISyntaxException IF the fetch URL cannot be converted into a proper URI.
   */
  public LaunchPod buildLaunchCommand(URL jenkinsUrl, String name, String role)
      throws MalformedURLException, URISyntaxException {
    List<FetchUri> fetchUris =
        additionalURIs.stream()
            .map(
                uri -> {
                  try {
                    return new FetchUri(
                        new java.net.URI(uri.getValue()),
                        uri.isExtract(),
                        uri.isExecutable(),
                        false,
                        Option.empty());
                  } catch (URISyntaxException e) {
                    logger.warn(String.format("Could not migrate URI: %s", uri.getValue()), e);
                    return null;
                  }
                })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

    return new LaunchCommandBuilder()
        .withCpu(this.getCpus())
        .withMemory(this.getMem())
        .withDisk(this.getDisk())
        .withName(name)
        .withRole(role)
        .withJenkinsUrl(jenkinsUrl)
        .withContainerInfo(Optional.ofNullable(this.getContainerInfo()))
        .withDomainInfoFilter(
            Optional.ofNullable(this.getDomainFilterModel()).map(model -> model.getFilter()))
        .withJnlpArguments(this.getJnlpArgs())
        .withAgentAttribute(this.getAgentAttributes())
        .withAgentCommandStyle(Optional.ofNullable(this.agentCommandStyle))
        .withAdditionalFetchUris(fetchUris)
        .build();
  }

  public String getLabel() {
    return this.label;
  }

  public Set<LabelAtom> getLabelSet() {
    // Label.parse requires a Jenkins instance so we initialize it lazily
    if (this.labelSet == null) {
      this.labelSet = Label.parse(label);
    }
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

  public double getCpus() {
    return this.cpus;
  }

  public double getDisk() {
    return this.disk;
  }

  public int getMem() {
    return this.mem;
  }

  public int getIdleTerminationMinutes() {
    return this.idleTerminationMinutes;
  }

  public boolean getReusable() {
    return this.reusable;
  }

  public List<MesosSlaveInfo.URI> getAdditionalURIs() {
    return additionalURIs;
  }

  public int getMinExecutors() {
    return minExecutors;
  }

  public int getMaxExecutors() {
    return maxExecutors;
  }

  public LaunchCommandBuilder.AgentCommandStyle getAgentCommandStyle() {
    return this.agentCommandStyle;
  }

  public String getJnlpArgs() {
    return jnlpArgs;
  }

  public String getAgentAttributes() {
    return agentAttributes;
  }

  public ContainerInfo getContainerInfo() {
    return this.containerInfo;
  }

  public DomainFilterModel getDomainFilterModel() {
    return this.domainFilterModel;
  }

  public static class ContainerInfo extends AbstractDescribableImpl<ContainerInfo> {

    private final String type;
    private final String dockerImage;
    private final List<Volume> volumes;
    private final Network networking;
    private final boolean dockerPrivilegedMode;
    private final boolean dockerForcePullImage;
    private boolean isDind;

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
        List<Volume> volumes,
        Network networking) {
      this.type = type;
      this.dockerImage = dockerImage;
      this.dockerPrivilegedMode = dockerPrivilegedMode;
      this.dockerForcePullImage = dockerForcePullImage;
      this.volumes = volumes;
      this.isDind = isDind;
      this.networking =
          (networking != null) ? networking : ContainerInfoTaskInfoBuilder.DEFAULT_NETWORKING;
    }

    public boolean getIsDind() {
      return this.isDind;
    }

    public Network getNetworking() {
      return this.networking;
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
