package org.jenkinsci.plugins.mesos;

import java.util.logging.Logger;

import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;

public class MesosSlaveInfo {
  private static final String DEFAULT_LABEL_NAME = "mesos";
  private final double slaveCpus;
  private final int slaveMem; // MB.
  private final double executorCpus;
  private final int maxExecutors;
  private final int executorMem; // MB.
  private final int idleTerminationMinutes;
  private final JSONObject slaveAttributes; // Slave attributes JSON representation.

  private String labelString = DEFAULT_LABEL_NAME;
  
  private static final Logger LOGGER = Logger.getLogger(MesosSlaveInfo.class.getName());

  @DataBoundConstructor
  public MesosSlaveInfo(String labelString, String slaveCpus, String slaveMem,
      String maxExecutors, String executorCpus, String executorMem,
      String idleTerminationMinutes, String slaveAttributes) throws NumberFormatException {
    this.slaveCpus = Double.parseDouble(slaveCpus);
    this.slaveMem = Integer.parseInt(slaveMem);
    this.maxExecutors = Integer.parseInt(maxExecutors);
    this.executorCpus = Double.parseDouble(executorCpus);
    this.executorMem = Integer.parseInt(executorMem);
    this.idleTerminationMinutes = Integer.parseInt(idleTerminationMinutes);
    this.labelString = StringUtils.isNotBlank(labelString) ? labelString
        : DEFAULT_LABEL_NAME;
    
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

  public int getIdleTerminationMinutes() {
    return idleTerminationMinutes;
  }

  public JSONObject getSlaveAttributes() {
    return slaveAttributes;
  }
}
