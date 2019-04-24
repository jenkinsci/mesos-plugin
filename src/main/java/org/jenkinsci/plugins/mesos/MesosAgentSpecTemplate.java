package org.jenkinsci.plugins.mesos;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import java.util.Set;
import java.util.UUID;
import org.kohsuke.stapler.DataBoundConstructor;

/** This is the Mesos agent pod spec config set by a user. */
public class MesosAgentSpecTemplate extends AbstractDescribableImpl<MesosAgentSpecTemplate> {

  private final String label;
  private final Set<LabelAtom> labelSet;

  private final Node.Mode mode;

  @DataBoundConstructor
  public MesosAgentSpecTemplate(String label, Node.Mode mode) {
    this.label = label;
    this.labelSet = Label.parse(label);
    this.mode = mode;
  }

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
    return 0.1;
  }

  public int getMemory() {
    return 32;
  }
}
