package org.jenkinsci.plugins.mesos;

import hudson.model.Descriptor;
import hudson.slaves.CloudRetentionStrategy;
import hudson.slaves.RetentionStrategy;

/** A strategy to terminate idle {@link MesosComputer} */
public class MesosRetentionStrategy extends CloudRetentionStrategy {
  /**
   * Constructs a new {@link hudson.slaves.CloudRetentionStrategy}. This is called by {@link
   * MesosSlave()}.
   *
   * @param idleMinutes The number of minutes to wait before calling getNode().terminate() on an
   *     idle {@link MesosComputer}
   */
  public MesosRetentionStrategy(int idleMinutes) {
    super(idleMinutes);
  }

  public static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {
    @Override
    public String getDisplayName() {
      return "Mesos Retention Strategy";
    }
  }
}
