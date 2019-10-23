package org.jenkinsci.plugins.mesos;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.model.Node;
import java.util.List;
import org.jenkinsci.plugins.mesos.MesosAgentSpecTemplate.ContainerInfo;

/**
 * This POJO describes a Jenkins agent for Mesos on 0.x and 1.x of the plugin. It is used to migrate
 * older configurations to {@link MesosAgentSpecTemplate} during deserialization. See {@link
 * MesosCloud#readResolve()} for the full migration.
 */
public class MesosSlaveInfo {

  //  ???       <nodeProperties/>
  private transient Node.Mode mode;
  private transient String labelString;
  private transient Double slaveCpus;
  private transient Double diskNeeded;
  private transient int slaveMem;

  @SuppressFBWarnings("UUF_UNUSED_FIELD")
  private transient Double executorCpus;

  @SuppressFBWarnings("UUF_UNUSED_FIELD")
  private transient int executorMem;

  private transient int minExecutors;
  private transient int maxExecutors;

  @SuppressFBWarnings("UUF_UNUSED_FIELD")
  private transient String remoteFSRoot;

  private transient int idleTerminationMinutes;

  @SuppressFBWarnings("UUF_UNUSED_FIELD")
  private transient String jvmArgs;

  private transient String jnlpArgs;

  @SuppressFBWarnings("UUF_UNUSED_FIELD")
  private transient boolean defaultSlave;

  @SuppressFBWarnings("UUF_UNUSED_FIELD")
  private transient List<URI> additionalURIs;

  private transient ContainerInfo containerInfo;

  /**
   * Resolves the old agent configuration after deserialization.
   *
   * @return the agent configt as a {@link MesosAgentSpecTemplate}.
   */
  private Object readResolve() {

    // Migrate to 2.x spec template
    return new MesosAgentSpecTemplate(
        this.labelString,
        this.mode,
        this.slaveCpus.toString(),
        Integer.toString(this.slaveMem),
        this.idleTerminationMinutes,
        this.minExecutors,
        this.maxExecutors,
        this.diskNeeded.toString(),
        this.jnlpArgs,
        "", // TODO: support additional URIs in MesosAgentSpecTemplate see
        // https://github.com/mesosphere/jenkins-mesos-plugin/pull/70
        this.containerInfo);
  }

  public static class URI {

    private final String value;
    private final boolean executable;
    private final boolean extract;

    public URI(String value, boolean executable, boolean extract) {
      this.value = value;
      this.executable = executable;
      this.extract = extract;
    }

    public String getValue() {
      return value;
    }
  }
}
