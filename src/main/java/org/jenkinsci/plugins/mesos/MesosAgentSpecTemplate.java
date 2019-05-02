package org.jenkinsci.plugins.mesos;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;

/** This is the Mesos agent pod spec config set by a user. */
public class MesosAgentSpecTemplate extends AbstractDescribableImpl<MesosAgentSpecTemplate> {

  private final String label;
  private final Set<LabelAtom> labelSet;

  private final Node.Mode mode;
  private final int idleTerminationMinutes;
  private final Boolean reusable;
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
  private String nodeProperties;

  @DataBoundConstructor
  public MesosAgentSpecTemplate(
      String label,
      Node.Mode mode,
      String cpus,
      String mem,
      String idleTerminationMinutes,
      Boolean reusable,
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
      String nodeProperties) {
    this.label = label;
    this.labelSet = Label.parse(label);
    this.mode = mode;
    this.idleTerminationMinutes = Integer.parseInt(idleTerminationMinutes);
    this.reusable = reusable;
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
    this.nodeProperties = nodeProperties;
    validate();
  }

  private void validate() {}

  @Extension
  public static final class DescriptorImpl extends Descriptor<MesosAgentSpecTemplate> {

    public DescriptorImpl() {
      load();
    }
  }

  // Getters

  public String getLabel() {
    return this.label;
  }

  public Set<LabelAtom> getLabelSet() {
    return this.labelSet;
  }

  public Node.Mode getMode() {
    return this.mode;
  }

  public String getName() {
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

  public Boolean getReusable() {
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

  public String getNodeProperties() {
    return nodeProperties;
  }
}
