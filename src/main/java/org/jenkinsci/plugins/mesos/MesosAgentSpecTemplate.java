package org.jenkinsci.plugins.mesos;

import com.mesosphere.usi.core.models.LaunchPod;
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
  private final int executorMem;
  private final String remoteFsRoot;
  private final String jvmArgs;
  private final String jnlpArgs;
  private final boolean defaultAgent;
  private String agentAttributes;
  private final String additionalURIs;
  private String containerImage;

  @DataBoundConstructor
  public MesosAgentSpecTemplate(
      String label,
      Node.Mode mode,
      String cpus,
      String mem,
      String idleTerminationMinutes,
      String minExecutors,
      String maxExecutors,
      String disk,
      String executorMem,
      String remoteFsRoot,
      String agentAttributes,
      String jvmArgs,
      String jnlpArgs,
      String defaultAgent,
      String additionalURIs,
      String containerImage) {
    this.label = label;
    this.labelSet = Label.parse(label);
    this.mode = mode;
    this.idleTerminationMinutes = Integer.parseInt(idleTerminationMinutes);
    this.reusable = false; // TODO: DCOS_OSS-5048.
    this.cpus = Double.parseDouble(cpus);
    this.mem = Integer.parseInt(mem);
    this.minExecutors = Integer.parseInt(minExecutors) < 1 ? 1 : Integer.parseInt(minExecutors);
    this.maxExecutors = Integer.parseInt(maxExecutors);
    this.disk = Double.parseDouble(disk);
    this.executorMem = Integer.parseInt(executorMem);
    this.remoteFsRoot = StringUtils.isNotBlank(remoteFsRoot) ? remoteFsRoot.trim() : "jenkins";
    this.jnlpArgs = StringUtils.isNotBlank(jnlpArgs) ? jnlpArgs : "";
    this.defaultAgent = Boolean.valueOf(defaultAgent);
    this.agentAttributes = agentAttributes != null ? agentAttributes.toString() : null;
    this.jvmArgs = jvmArgs;
    this.additionalURIs = additionalURIs;
    this.containerImage = containerImage;
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
        .withImage(Optional.ofNullable(this.containerImage).filter(s -> !s.isEmpty()))
        .withJnlpArguments(this.jnlpArgs)
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

  public String getAgentAttributes() {
    return agentAttributes;
  }

  public boolean isDefaultAgent() {
    return defaultAgent;
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

  public int getExecutorMem() {
    return executorMem;
  }

  public String getRemoteFsRoot() {
    return remoteFsRoot;
  }

  public String getJvmArgs() {
    return jvmArgs;
  }

  public String getJnlpArgs() {
    return jnlpArgs;
  }

  public String getContainerImage() {
    return this.containerImage;
  }
}
