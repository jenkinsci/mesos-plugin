/*
 * Copyright 2013 Twitter, Inc. and other contributors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jenkinsci.plugins.mesos;

import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.apache.mesos.MesosNativeLibrary;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

public class MesosCloud extends Cloud {
  private static final String DEFAULT_FRAMEWORK_NAME = "Jenkins Framework";
  
  private String nativeLibraryPath;
  private String master;
  private String description;
  private String frameworkName;

  // Find the default values for these variables in
  // src/main/resources/org/jenkinsci/plugins/mesos/MesosCloud/config.jelly.
  private final double slaveCpus;
  private final int slaveMem; // MB.
  private final double executorCpus;
  private final int maxExecutors;
  private final int executorMem; // MB.
  private final int idleTerminationMinutes;

  private final String labelString = "mesos";

  private static String staticMaster;

  private static final Logger LOGGER = Logger.getLogger(MesosCloud.class.getName());

  private static volatile boolean nativeLibraryLoaded = false;

  /**
   * We want to start the Mesos scheduler as part of the initialization of Jenkins
   * and after the cloud class values have been restored from persistence.If this is
   * the very first time, this method will be NOOP as MesosCloud is not registered yet.
   */

  @Initializer(after=InitMilestone.JOB_LOADED)
  public static void init() {
    Hudson h = Hudson.getInstance();
    List<Node> slaves = h.getNodes();

    // Turning the AUTOMATIC_SLAVE_LAUNCH flag off because the below slave removals
    // causes computer launch in other slaves that have not been removed yet.
    // To study how a slave removal updates the entire list, one can refer to
    // Hudson NodeProvisioner class and follow this method chain removeNode() -> 
    // setNodes() -> updateComputerList() -> updateComputer(). 
    h.AUTOMATIC_SLAVE_LAUNCH = false;
    for (Node n : slaves) {
      //Remove all slaves that were persisted when Jenkins shutdown.
      if (n instanceof MesosSlave) {
        ((MesosSlave)n).terminate();
      }
    }        

    // Turn it back on for future real slaves.
    h.AUTOMATIC_SLAVE_LAUNCH = true;

    for (Cloud c : h.clouds) {
      if( c instanceof MesosCloud) {
        ((MesosCloud)c).restartMesos();
      }
    }

  } 

  @DataBoundConstructor
  public MesosCloud(String nativeLibraryPath, String master, String description, String frameworkName, String slaveCpus,
      int slaveMem, int maxExecutors, String executorCpus, int executorMem, int idleTerminationMinutes)
          throws NumberFormatException {
    super("MesosCloud");

    this.nativeLibraryPath = nativeLibraryPath;
    this.master = master;
    this.description = description;
    this.frameworkName = StringUtils.isNotBlank(frameworkName) ? frameworkName : DEFAULT_FRAMEWORK_NAME;
    this.slaveCpus = Double.parseDouble(slaveCpus);
    this.slaveMem = slaveMem;
    this.maxExecutors = maxExecutors;
    this.executorCpus = Double.parseDouble(executorCpus);
    this.executorMem = executorMem;
    this.idleTerminationMinutes = idleTerminationMinutes;

    restartMesos();

  }

  public void restartMesos() {

    if(!nativeLibraryLoaded) {
      // First, we attempt to load the library from the given path.
      // If unsuccessful, we attempt to load using 'MesosNativeLibrary.load()'.
      try {
      	MesosNativeLibrary.load(nativeLibraryPath);
      } catch (UnsatisfiedLinkError error) {
      	LOGGER.warning("Failed to load native Mesos library from '" + nativeLibraryPath +
        	             "': " + error.getMessage());
      	MesosNativeLibrary.load();
      }	
      nativeLibraryLoaded = true;
    }

    // Restart the scheduler if the master has changed or a scheduler is not up.
    if (!master.equals(staticMaster) || !Mesos.getInstance().isSchedulerRunning()) {
      if (!master.equals(staticMaster)) {
        LOGGER.info("Mesos master changed, restarting the scheduler");
        staticMaster = master;
      } else {
        LOGGER.info("Scheduler was down, restarting the scheduler");
      }

      Mesos.getInstance().stopScheduler();
      Mesos.getInstance().startScheduler(Jenkins.getInstance().getRootUrl(), master, this.frameworkName);
    } else {
      LOGGER.info("Mesos master has not changed, leaving the scheduler running");
    }

  }

  @Override
  public Collection<PlannedNode> provision(Label label, int excessWorkload) {
    List<PlannedNode> list = new ArrayList<PlannedNode>();

    try {
      while (excessWorkload > 0) {
        final int numExecutors = Math.min(excessWorkload, maxExecutors);
        excessWorkload -= numExecutors;
        LOGGER.info("Provisioning Jenkins Slave on Mesos with " + numExecutors +
                    " executors. Remaining excess workload: " + excessWorkload + " executors)");
        list.add(new PlannedNode(this.getDisplayName(), Computer.threadPoolForRemoting
            .submit(new Callable<Node>() {
              public Node call() throws Exception {
                MesosSlave s = doProvision(numExecutors);
                // We do not need to explicitly add the Node here because that is handled by
                // hudson.slaves.NodeProvisioner::update() that checks the result from the
                // Future and adds the node. Though there is duplicate node addition check
                // because of this early addition there is difference in job scheduling and
                // best to avoid it.
                return s;
              }
            }), numExecutors));
      }
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Failed to create instances on Mesos", e);
      return Collections.emptyList();
    }

    return list;
  }

  private MesosSlave doProvision(int numExecutors) throws Descriptor.FormException, IOException {
    String name = "mesos-jenkins-" + UUID.randomUUID().toString();
    return new MesosSlave(name, numExecutors, labelString, slaveCpus, slaveMem,
        executorCpus, executorMem, idleTerminationMinutes);
  }

  @Override
  public boolean canProvision(Label label) {
    // Provisioning is simply creating a task for a jenkins slave.
    // Therefore, we can always provision as long as the label
    // matches "mesos".
    // TODO(vinod): The framework may not have the resources necessary
    // to start a task when it comes time to launch the slave.
    return label.matches(Label.parse(labelString));
  }

  public String getNativeLibraryPath() {
    return this.nativeLibraryPath;
  }

  public void setNativeLibraryPath(String nativeLibraryPath) {
    this.nativeLibraryPath = nativeLibraryPath;
  }

  public String getMaster() {
    return this.master;
  }

  public void setMaster(String master) {
    this.master = master;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getFrameworkName() {
    return frameworkName;
  }

  public void setFrameworkName(String frameworkName) {
    this.frameworkName = frameworkName;
  }

  @Override
  public DescriptorImpl getDescriptor() {
    return (DescriptorImpl) super.getDescriptor();
  }

  public static MesosCloud get() {
    return Hudson.getInstance().clouds.get(MesosCloud.class);
  }

  @Extension
  public static class DescriptorImpl extends Descriptor<Cloud> {
    private String nativeLibraryPath;
    private String master;
    private String description;
    private String frameworkName;
    
    @Override
    public String getDisplayName() {
      return "Mesos Cloud";
    }

    @Override
    public boolean configure(StaplerRequest request, JSONObject object) throws FormException {
      nativeLibraryPath = object.getString("nativeLibraryPath");
      master = object.getString("master");
      description = object.getString("description");
      frameworkName = object.getString("frameworkName");
      save();
      return super.configure(request, object);
    }

    /**
     * Test connection from configuration page.
     */
    public FormValidation doTestConnection(
        @QueryParameter("master") String master,
        @QueryParameter("nativeLibraryPath") String nativeLibraryPath)
        throws IOException, ServletException {
      master = master.trim();

      if (master.equals("local")) {
        return FormValidation.warning("'local' creates a local mesos cluster");
      }

      if (master.startsWith("zk://")) {
        return FormValidation.warning("Zookeeper paths can be used, but the connection cannot be " +
            "tested prior to saving this page.");
      }

      if (master.startsWith("http://")) {
        return FormValidation.error("Please omit 'http://'.");
      }

      if (!nativeLibraryPath.startsWith("/")) {
        return FormValidation.error("Please provide an absolute path");
      }

      try {
        // URL requires the protocol to be explicitly specified.
        HttpURLConnection urlConn =
          (HttpURLConnection) new URL("http://" + master).openConnection();
        urlConn.connect();
        int code = urlConn.getResponseCode();
        urlConn.disconnect();

        if (code == 200) {
          return FormValidation.ok("Connected to Mesos successfully");
        } else {
          return FormValidation.error("Status returned from url was " + code);
        }
      } catch (IOException e) {
        LOGGER.log(Level.WARNING, "Failed to connect to Mesos " + master, e);
        return FormValidation.error(e.getMessage());
      }
    }
    
    public FormValidation doCheckSlaveCpus(@QueryParameter String value) {
      return doCheckCpus(value);    
    }

    public FormValidation doCheckExecutorCpus(@QueryParameter String value) {
      return doCheckCpus(value);
    }
    
    private FormValidation doCheckCpus(@QueryParameter String value) {
      boolean valid = true;
      String errorMessage = "Invalid CPUs value, it should be a positive decimal.";
      
      if (StringUtils.isBlank(value)) {
        valid = false;
      } else {
        try {
          if (Double.parseDouble(value) < 0) {
            valid = false;
          }
        } catch (NumberFormatException e) {
          valid = false;
        }
      }
      
      return valid ? FormValidation.ok() : FormValidation.error(errorMessage); 
    }
  }

}
