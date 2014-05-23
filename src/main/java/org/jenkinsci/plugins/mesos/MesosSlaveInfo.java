package org.jenkinsci.plugins.mesos;

import java.util.logging.Logger;

import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;

import org.apache.commons.lang.StringUtils;
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
  private final String customRemoteFSRoot;
  private final int idleTerminationMinutes;
  private final String jvmArgs;
  private final JSONObject slaveAttributes; // Slave attributes JSON representation.

  private String labelString = DEFAULT_LABEL_NAME;

  private static final Logger LOGGER = Logger.getLogger(MesosSlaveInfo.class.getName());

  @DataBoundConstructor
  public MesosSlaveInfo(String labelString, String slaveCpus, String slaveMem,
      String maxExecutors, String executorCpus, String executorMem, JSONObject hasCustomRemoteFSRoot,
      String idleTerminationMinutes, String slaveAttributes, String jvmArgs) throws NumberFormatException {
    this.slaveCpus = Double.parseDouble(slaveCpus);
    this.slaveMem = Integer.parseInt(slaveMem);
    this.maxExecutors = Integer.parseInt(maxExecutors);
    this.executorCpus = Double.parseDouble(executorCpus);
    this.executorMem = Integer.parseInt(executorMem);

    this.customRemoteFSRoot =
      (hasCustomRemoteFSRoot == null ||
        !hasCustomRemoteFSRoot.has("customRemoteFSRoot") ||
        StringUtils.isBlank(hasCustomRemoteFSRoot.getString("customRemoteFSRoot")))
        ? null : hasCustomRemoteFSRoot.getString("customRemoteFSRoot").trim();

    this.idleTerminationMinutes = Integer.parseInt(idleTerminationMinutes);
    this.labelString = StringUtils.isNotBlank(labelString) ? labelString
        : DEFAULT_LABEL_NAME;
    this.jvmArgs = StringUtils.isNotBlank(jvmArgs) ? cleanseJvmArgs(jvmArgs)
        : DEFAULT_JVM_ARGS;

    // Parse the attributes provided from the cloud config
    JSONObject jsonObject = null;
    try {
      jsonObject = (JSONObject) JSONSerializer.toJSON(slaveAttributes);
    } catch (JSONException e) {
      LOGGER.warning("Ignoring Mesos slave attributes JSON due to parsing error : " + slaveAttributes);
    }
    this.slaveAttributes = jsonObject;
  }

  public String getLabelString() {
    return labelString;
  }

  public void setLabelString(String labelString) {
    this.labelString = labelString;
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

  public String getCustomRemoteFSRoot() {
        return customRemoteFSRoot;
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

  /**
   * Removes any additional {@code -Xmx} JVM args from the
   * provided JVM arguments.  This is to ensure that the logic
   * that sets the maximum heap sized based on the memory available
   * to the slave is not overriden by a value provided via the configuration
   * that may not work with the current slave's configuration.
   * @param jvmArgs the string of JVM arguments.
   * @returns The cleansed JVM argument string.
   */
  private String cleanseJvmArgs(final String jvmArgs) {
    return jvmArgs.replaceAll(JVM_ARGS_PATTERN, "");
  }
}
