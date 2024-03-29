package org.jenkinsci.plugins.mesos;

import com.google.common.annotations.VisibleForTesting;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Node;
import java.util.Iterator;
import java.util.List;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.mesos.MesosAgentSpecTemplate.ContainerInfo;
import org.kohsuke.stapler.DataBoundConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This POJO describes a Jenkins agent for Mesos on 0.x and 1.x of the plugin. It is used to migrate
 * older configurations to {@link MesosAgentSpecTemplate} during deserialization. See {@link
 * MesosCloud#readResolve()} for the full migration.
 */
public class MesosSlaveInfo {

  private static final Logger logger = LoggerFactory.getLogger(MesosSlaveInfo.class);

  private transient Node.Mode mode;
  private transient String labelString;
  private transient Double slaveCpus;
  private transient Double diskNeeded;
  private transient int slaveMem;
  private transient List<URI> additionalURIs;
  private transient ContainerInfo containerInfo;
  private transient String jnlpArgs;
  private transient String agentAttributes;

  // The following fields are dropped during the migration.
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

  @SuppressFBWarnings("UUF_UNUSED_FIELD")
  private transient boolean defaultSlave;

  @SuppressFBWarnings("UUF_UNUSED_FIELD")
  private transient String slaveAttributesString; // Agent attributes JSON representation.

  /**
   * Resolves the old agent configuration after deserialization.
   *
   * @return the agent config as a {@link MesosAgentSpecTemplate}.
   */
  private Object readResolve() {
    this.agentAttributes =
        this.migrateAttributeFilter(this.parseSlaveAttributes(this.slaveAttributesString));

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
        this.agentAttributes,
        this.additionalURIs,
        this.containerInfo,
        null,
        null);
  }

  @VisibleForTesting
  JSONObject parseSlaveAttributes(String slaveAttributes) {
    if (StringUtils.isNotBlank(slaveAttributes)) {
      try {
        return (JSONObject) JSONSerializer.toJSON(slaveAttributes);
      } catch (JSONException e) {
        logger.warn(
            "Ignoring Mesos agent attributes JSON due to parsing error : " + slaveAttributes);
      }
    }

    return null;
  }

  @VisibleForTesting
  String migrateAttributeFilter(JSONObject slaveAttributes) {
    if (slaveAttributes == null) {
      return "";
    }

    StringBuilder attributes = new StringBuilder();
    // Filtering code from JenkinsScheduler#slaveAttributesMatch in 1.x.
    final Iterator iterator = slaveAttributes.keys();
    while (iterator.hasNext()) {
      final String key = (String) iterator.next();
      final String value = slaveAttributes.getString(key);
      if (attributes.length() > 0) {
        attributes.append(",");
      }
      attributes.append(key).append(":").append(value);
    }

    return attributes.toString();
  }

  public static class URI extends AbstractDescribableImpl<URI> {
    @Extension
    public static class DescriptorImpl extends Descriptor<URI> {
      public String getDisplayName() {
        return "";
      }
    }

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
