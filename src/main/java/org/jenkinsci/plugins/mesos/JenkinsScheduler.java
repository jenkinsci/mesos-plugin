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


import com.google.common.annotations.VisibleForTesting;
import hudson.model.Computer;
import hudson.model.Node;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.apache.mesos.MesosSchedulerDriver;
import org.apache.mesos.Protos.Attribute;
import org.apache.mesos.Protos.CommandInfo;
import org.apache.mesos.Protos.ContainerInfo;
import org.apache.mesos.Protos.ContainerInfo.DockerInfo;
import org.apache.mesos.Protos.ContainerInfo.DockerInfo.Network;
import org.apache.mesos.Protos.ContainerInfo.DockerInfo.PortMapping;
import org.apache.mesos.Protos.Credential;
import org.apache.mesos.Protos.ExecutorID;
import org.apache.mesos.Protos.Filters;
import org.apache.mesos.Protos.FrameworkID;
import org.apache.mesos.Protos.FrameworkInfo;
import org.apache.mesos.Protos.MasterInfo;
import org.apache.mesos.Protos.Offer;
import org.apache.mesos.Protos.OfferID;
import org.apache.mesos.Protos.Parameter;
import org.apache.mesos.Protos.Resource;
import org.apache.mesos.Protos.SlaveID;
import org.apache.mesos.Protos.Status;
import org.apache.mesos.Protos.TaskID;
import org.apache.mesos.Protos.TaskInfo;
import org.apache.mesos.Protos.TaskStatus;
import org.apache.mesos.Protos.Value;
import org.apache.mesos.Protos.Value.Range;
import org.apache.mesos.Protos.Volume;
import org.apache.mesos.Protos.Volume.Mode;
import org.apache.mesos.Scheduler;
import org.apache.mesos.SchedulerDriver;

import com.google.protobuf.ByteString;

public class JenkinsScheduler implements Scheduler {
  private static final String SLAVE_JAR_URI_SUFFIX = "jnlpJars/slave.jar";

  // We allocate 10% more memory to the Mesos task to account for the JVM overhead.
  private static final double JVM_MEM_OVERHEAD_FACTOR = 0.1;

  private static final String SLAVE_COMMAND_FORMAT =
      "java -DHUDSON_HOME=jenkins -server -Xmx%dm %s -jar ${MESOS_SANDBOX-.}/slave.jar %s %s -jnlpUrl %s";
  private static final String JNLP_SECRET_FORMAT = "-secret %s";
  public static final String PORT_RESOURCE_NAME = "ports";

  private Queue<Request> requests;
  private Map<TaskID, Result> results;
  private Set<TaskID> finishedTasks;
  private volatile MesosSchedulerDriver driver;
  private String jenkinsMaster;
  private volatile MesosCloud mesosCloud;
  private volatile boolean running;

  private static final Logger LOGGER = Logger.getLogger(JenkinsScheduler.class.getName());

  public static final Lock SUPERVISOR_LOCK = new ReentrantLock();

  public JenkinsScheduler(String jenkinsMaster, MesosCloud mesosCloud) {
    LOGGER.info("JenkinsScheduler instantiated with jenkins " + jenkinsMaster + " and mesos " + mesosCloud.getMaster());

    this.jenkinsMaster = jenkinsMaster;
    this.mesosCloud = mesosCloud;

    requests = new LinkedList<Request>();
    results = new HashMap<TaskID, Result>();
    finishedTasks = Collections.newSetFromMap(new ConcurrentHashMap<TaskID, Boolean>());
  }

  public synchronized void init() {
    // This is to ensure that isRunning() returns true even when the driver is not yet inside run().
    // This is important because MesosCloud.provision() starts a new framework whenever isRunning() is false.
    running = true;
    // Start the framework.
    new Thread(new Runnable() {
      @Override
      public void run() {
        String targetUser = mesosCloud.getSlavesUser();
        String webUrl = Jenkins.getInstance().getRootUrl();
        if (webUrl == null) webUrl = System.getenv("JENKINS_URL");
        // Have Mesos fill in the current user.
        FrameworkInfo framework = FrameworkInfo.newBuilder()
          .setUser(targetUser == null ? "" : targetUser)
          .setName(mesosCloud.getFrameworkName())
          .setPrincipal(mesosCloud.getPrincipal())
          .setCheckpoint(mesosCloud.isCheckpoint())
          .setWebuiUrl(webUrl != null ? webUrl :  "")
          .build();

        LOGGER.info("Initializing the Mesos driver with options"
        + "\n" + "Framework Name: " + framework.getName()
        + "\n" + "Principal: " + framework.getPrincipal()
        + "\n" + "Checkpointing: " + framework.getCheckpoint()
        );

        if (StringUtils.isNotBlank(mesosCloud.getSecret())) {
            Credential credential = Credential.newBuilder()
              .setPrincipal(mesosCloud.getPrincipal())
              .setSecret(ByteString.copyFromUtf8(mesosCloud.getSecret()))
              .build();

            LOGGER.info("Authenticating with Mesos master with principal " + credential.getPrincipal());
            driver = new MesosSchedulerDriver(JenkinsScheduler.this, framework, mesosCloud.getMaster(), credential);
        } else {
            driver = new MesosSchedulerDriver(JenkinsScheduler.this, framework, mesosCloud.getMaster());
        }
        Status runStatus = driver.run();
        if (runStatus != Status.DRIVER_STOPPED) {
          LOGGER.severe("The Mesos driver was aborted! Status code: " + runStatus.getNumber());
        }

        driver = null;
        running = false;
      }
    }).start();
  }

  public synchronized void stop() {
    if (driver != null) {
      driver.stop();
    } else {
      LOGGER.warning("Unable to stop Mesos driver:  driver is null.");
    }
    running = false;
  }

  public synchronized boolean isRunning() {
    return running;
  }

  public synchronized void requestJenkinsSlave(Mesos.SlaveRequest request, Mesos.SlaveResult result) {
    LOGGER.info("Enqueuing jenkins slave request");
    requests.add(new Request(request, result));
  }

  /**
   * @param slaveName the slave name in jenkins
   * @return the jnlp url for the slave: http://[master]/computer/[slaveName]/slave-agent.jnlp
   */
  private String getJnlpUrl(String slaveName) {
    return joinPaths(joinPaths(joinPaths(jenkinsMaster, "computer"), slaveName), "slave-agent.jnlp");
  }

  /**
   * Slave needs to go through authentication while connecting through jnlp if security is enabled in jenkins.
   * This method gets secret (for jnlp authentication) from jenkins, constructs command line argument and returns it.
   *
   * @param slaveName the slave name in jenkins
   * @return jenkins slave secret corresponding to slave name in the format '-secret <secret>'
   */
  private String getJnlpSecret(String slaveName) {
    String jnlpSecret = "";
    if(Jenkins.getInstance().isUseSecurity()) {
      jnlpSecret = String.format(JNLP_SECRET_FORMAT, jenkins.slaves.JnlpSlaveAgentProtocol.SLAVE_SECRET.mac(slaveName));
    }
    return jnlpSecret;
  }

  private static String joinPaths(String prefix, String suffix) {
    if (prefix.endsWith("/"))   prefix = prefix.substring(0, prefix.length()-1);
    if (suffix.startsWith("/")) suffix = suffix.substring(1, suffix.length());

    return prefix + '/' + suffix;
  }

  public synchronized void terminateJenkinsSlave(String name) {
    LOGGER.info("Terminating jenkins slave " + name);

    TaskID taskId = TaskID.newBuilder().setValue(name).build();

    if (results.containsKey(taskId)) {
      LOGGER.info("Killing mesos task " + taskId);
      driver.killTask(taskId);
    } else {
        // This is handling the situation that a slave was provisioned but it never
        // got scheduled because of resource scarcity and jenkins later tries to remove
        // the offline slave but since it was not scheduled we have to remove it from
        // the request queue. The method has been also synchronized because there is a race
        // between this removal request from jenkins and a resource getting freed up in mesos
        // resulting in scheduling the slave and resulting in orphaned task/slave not monitored
        // by Jenkins.

        for(Request request : requests) {
           if(request.request.slave.name.equals(name)) {
             LOGGER.info("Removing enqueued mesos task " + name);
             requests.remove(request);
             // Also signal the Thread of the MesosComputerLauncher.launch() to exit from latch.await()
             // Otherwise the Thread will stay in WAIT forever -> Leak!
             request.result.failed(request.request.slave);
             return;
           }
        }

        LOGGER.warning("Asked to kill unknown mesos task " + taskId);
    }

    // Since this task is now running, we should not start this task up again at a later point in time
    finishedTasks.add(taskId);

    if (mesosCloud.isOnDemandRegistration()) {
      supervise();
    }

  }

  @Override
  public void registered(SchedulerDriver driver, FrameworkID frameworkId, MasterInfo masterInfo) {
    LOGGER.info("Framework registered! ID = " + frameworkId.getValue());
  }

  @Override
  public void reregistered(SchedulerDriver driver, MasterInfo masterInfo) {
    LOGGER.info("Framework re-registered");
  }

  @Override
  public void disconnected(SchedulerDriver driver) {
    LOGGER.info("Framework disconnected!");
  }

  @Override
  public synchronized void resourceOffers(SchedulerDriver driver, List<Offer> offers) {
    LOGGER.fine("Received offers " + offers.size());
    for (Offer offer : offers) {
      boolean matched = false;
      for (Request request : requests) {
        if (matches(offer, request)) {
          matched = true;
          LOGGER.fine("Offer matched! Creating mesos task");

          try {
              createMesosTask(offer, request);
          } catch (Exception e) {
              LOGGER.log(Level.SEVERE, e.getMessage(), e);
          }
          requests.remove(request);
          break;
        }
      }

      if (!matched) {
        driver.declineOffer(offer.getId());
      }
    }
  }

  private boolean matches(Offer offer, Request request) {
    double cpus = -1;
    double mem = -1;
    List<Range> ports = null;

    for (Resource resource : offer.getResourcesList()) {
      if (resource.getName().equals("cpus")) {
        if (resource.getType().equals(Value.Type.SCALAR)) {
          cpus = resource.getScalar().getValue();
        } else {
          LOGGER.severe("Cpus resource was not a scalar: " + resource.getType().toString());
        }
      } else if (resource.getName().equals("mem")) {
        if (resource.getType().equals(Value.Type.SCALAR)) {
          mem = resource.getScalar().getValue();
        } else {
          LOGGER.severe("Mem resource was not a scalar: " + resource.getType().toString());
        }
      } else if (resource.getName().equals("disk")) {
        LOGGER.fine("Ignoring disk resources from offer");
      } else if (resource.getName().equals("ports")) {
        if (resource.getType().equals(Value.Type.RANGES)) {
          ports = resource.getRanges().getRangeList();
        } else {
          LOGGER.severe("Ports resource was not a range: " + resource.getType().toString());
        }
      } else {
        LOGGER.warning("Ignoring unknown resource type: " + resource.getName());
      }
    }

    if (cpus < 0) LOGGER.fine("No cpus resource present");
    if (mem < 0)  LOGGER.fine("No mem resource present");

    MesosSlaveInfo.ContainerInfo containerInfo = request.request.slaveInfo.getContainerInfo();

    boolean hasPortMappings = containerInfo != null ? containerInfo.hasPortMappings() : false;

    boolean hasPortResources = ports != null && !ports.isEmpty();

    if (hasPortMappings && !hasPortResources) {
      LOGGER.severe("No ports resource present");
    }

    // Check for sufficient cpu and memory resources in the offer.
    double requestedCpus = request.request.cpus;
    double requestedMem = (1 + JVM_MEM_OVERHEAD_FACTOR) * request.request.mem;
    // Get matching slave attribute for this label.
    JSONObject slaveAttributes = getMesosCloud().getSlaveAttributeForLabel(request.request.slaveInfo.getLabelString());

    if (requestedCpus <= cpus
            && requestedMem <= mem
            && !(hasPortMappings && !hasPortResources)
            && slaveAttributesMatch(offer, slaveAttributes)) {
      return true;
    } else {
      String requestedPorts = containerInfo != null
              ? StringUtils.join(containerInfo.getPortMappings().toArray(), "/")
              : "";

      LOGGER.fine(
          "Offer not sufficient for slave request:\n" +
          offer.getResourcesList().toString() +
          "\n" + offer.getAttributesList().toString() +
          "\nRequested for Jenkins slave:\n" +
          "  cpus:  " + requestedCpus + "\n" +
          "  mem:   " + requestedMem + "\n" +
          "  ports: " + requestedPorts + "\n" +
          "  attributes:  " + (slaveAttributes == null ? ""  : slaveAttributes.toString()));
      return false;
    }
  }

  /**
  * Checks whether the cloud Mesos slave attributes match those from the Mesos offer.
  *
  * @param offer Mesos offer data object.
  * @return true if all the offer attributes match and false if not.
  */
  private boolean slaveAttributesMatch(Offer offer, JSONObject slaveAttributes) {

    //Accept any and all Mesos slave offers by default.
    boolean slaveTypeMatch = true;

    //Collect the list of attributes from the offer as key-value pairs
    Map<String, String> attributesMap = new HashMap<String, String>();
    for (Attribute attribute : offer.getAttributesList()) {
      attributesMap.put(attribute.getName(), attribute.getText().getValue());
    }

    if (slaveAttributes != null && slaveAttributes.size() > 0) {

      //Iterate over the cloud attributes to see if they exist in the offer attributes list.
      Iterator iterator = slaveAttributes.keys();
      while (iterator.hasNext()) {

        String key = (String) iterator.next();

        //If there is a single absent attribute then we should reject this offer.
        if (!(attributesMap.containsKey(key) && attributesMap.get(key).toString().equals(slaveAttributes.getString(key)))) {
          slaveTypeMatch = false;
          break;
        }
      }
    }

    return slaveTypeMatch;
  }

  @VisibleForTesting
  List<Integer> findPortsToUse(Offer offer, int maxCount) {
      Set<Integer> portsToUse = new HashSet<Integer>();
      List<Value.Range> portRangesList = null;

      // Locate the port resource in the offer
      for (Resource resource : offer.getResourcesList()) {
        if (resource.getName().equals(PORT_RESOURCE_NAME)) {
          portRangesList = resource.getRanges().getRangeList();
          break;
        }
      }

      LOGGER.fine("portRangesList=" + portRangesList);

      /**
       * We need to find maxCount ports to use.
       * We are provided a list of port ranges to use
       * We are assured by the offer check that we have enough ports to use
       */
      // Check this port range for ports that we can use
      for (Value.Range currentPortRange : portRangesList) {
          int candidatePort = (int) currentPortRange.getBegin();

          // Check each port until we reach the end.
          // If the port is already in the list of ports to use, ignore it and check the next one
          while (candidatePort <= currentPortRange.getEnd() && portsToUse.size() < maxCount) {
              if (!portsToUse.contains(candidatePort)) {
                  portsToUse.add(candidatePort);
              } else {
                  candidatePort++;
              }
          }
      }

      return new ArrayList(portsToUse);
  }

  private void createMesosTask(Offer offer, Request request) {
    final String slaveName = request.request.slave.name;
    TaskID taskId = TaskID.newBuilder().setValue(slaveName).build();

    LOGGER.info("Launching task " + taskId.getValue() + " with URI " +
                joinPaths(jenkinsMaster, SLAVE_JAR_URI_SUFFIX));

    if (isExistingTask(taskId)) {
        refuseOffer(offer);
        return;
    }

    for (final Computer computer : Jenkins.getInstance().getComputers()) {
        if (!MesosComputer.class.isInstance(computer)) {
            LOGGER.finer("Not a mesos computer, skipping");
            continue;
        }

        MesosComputer mesosComputer = (MesosComputer) computer;

        if (mesosComputer == null) {
            LOGGER.fine("The mesos computer is null, skipping");
            continue;
        }

        MesosSlave mesosSlave = mesosComputer.getNode();

        if (taskId.getValue().equals(computer.getName()) && mesosSlave.isPendingDelete()) {
            LOGGER.info("This mesos task " + taskId.getValue() + " is pending deletion. Not launching another task");
            driver.declineOffer(offer.getId());
        }
    }

    CommandInfo.Builder commandBuilder = getCommandInfoBuilder(request);
    TaskInfo.Builder taskBuilder = getTaskInfoBuilder(offer, request, taskId, commandBuilder);

    if (request.request.slaveInfo.getContainerInfo() != null) {
        getContainerInfoBuilder(offer, request, slaveName, taskBuilder);
    }

    List<TaskInfo> tasks = new ArrayList<TaskInfo>();
    tasks.add(taskBuilder.build());
    Filters filters = Filters.newBuilder().setRefuseSeconds(1).build();
    driver.launchTasks(offer.getId(), tasks, filters);

    results.put(taskId, new Result(request.result, new Mesos.JenkinsSlave(offer.getSlaveId()
        .getValue())));
    finishedTasks.add(taskId);
  }

  private void detectAndAddAdditionalURIs(Request request, CommandInfo.Builder commandBuilder) {

    if (request.request.slaveInfo.getAdditionalURIs() != null) {
      for (MesosSlaveInfo.URI uri : request.request.slaveInfo.getAdditionalURIs()) {
        commandBuilder.addUris(
            CommandInfo.URI.newBuilder().setValue(
                uri.getValue()).setExecutable(uri.isExecutable()).setExtract(uri.isExtract()));
      }
    }
  }

  private void detectAndAddExternalContainerInfo(Request request, CommandInfo.Builder commandBuilder) {
    MesosSlaveInfo.ExternalContainerInfo externalContainerInfo = request.request.slaveInfo.getExternalContainerInfo();
    if (externalContainerInfo != null) {
      LOGGER.info("Launching in External Container Mode:" + externalContainerInfo.getImage());
      CommandInfo.ContainerInfo.Builder containerInfo = CommandInfo.ContainerInfo.newBuilder();
      containerInfo.setImage(externalContainerInfo.getImage());

      // add container option to builder
      String[] containerOptions = request.request.getExternalContainerOptions();
      for (int i = 0; i < containerOptions.length; i++) {
        LOGGER.info("with option: " + containerOptions[i]);
        containerInfo.addOptions(containerOptions[i]);
      }
      commandBuilder.setContainer(containerInfo.build());
    }
  }

  private TaskInfo.Builder getTaskInfoBuilder(Offer offer, Request request, TaskID taskId, CommandInfo.Builder commandBuilder) {
      return TaskInfo.newBuilder()
        .setName("task " + taskId.getValue())
        .setTaskId(taskId)
        .setSlaveId(offer.getSlaveId())
        .addResources(
            Resource
                .newBuilder()
                .setName("cpus")
                .setType(Value.Type.SCALAR)
                .setScalar(
                    Value.Scalar.newBuilder()
                        .setValue(request.request.cpus).build()).build())
        .addResources(
            Resource
                .newBuilder()
                .setName("mem")
                .setType(Value.Type.SCALAR)
                .setScalar(
                    Value.Scalar
                        .newBuilder()
                        .setValue((1 + JVM_MEM_OVERHEAD_FACTOR) * request.request.mem)
                        .build()).build())
        .setCommand(commandBuilder.build());
  }

  private void getContainerInfoBuilder(Offer offer, Request request, String slaveName, TaskInfo.Builder taskBuilder) {
      MesosSlaveInfo.ContainerInfo containerInfo = request.request.slaveInfo.getContainerInfo();
      ContainerInfo.Type containerType = ContainerInfo.Type.valueOf(containerInfo.getType());

      ContainerInfo.Builder containerInfoBuilder = ContainerInfo.newBuilder() //
              .setType(containerType); //

      switch(containerType) {
        case DOCKER:
          LOGGER.info("Launching in Docker Mode:" + containerInfo.getDockerImage());
          DockerInfo.Builder dockerInfoBuilder = DockerInfo.newBuilder() //
              .setImage(containerInfo.getDockerImage())
              .setPrivileged(containerInfo.getDockerPrivilegedMode() != null ? containerInfo.getDockerPrivilegedMode() : false)
              .setForcePullImage(containerInfo.getDockerForcePullImage() != null ? containerInfo.getDockerForcePullImage() : false);

          if (containerInfo.getParameters() != null) {
            for (MesosSlaveInfo.Parameter parameter : containerInfo.getParameters()) {
              LOGGER.info("Adding Docker parameter '" + parameter.getKey() + ":" + parameter.getValue() + "'");
              dockerInfoBuilder.addParameters(Parameter.newBuilder().setKey(parameter.getKey()).setValue(parameter.getValue()).build());
            }
          }

          String networking = request.request.slaveInfo.getContainerInfo().getNetworking();
          dockerInfoBuilder.setNetwork(Network.valueOf(networking));

          //  https://github.com/jenkinsci/mesos-plugin/issues/109
          if (dockerInfoBuilder.getNetwork() != Network.HOST) {
              containerInfoBuilder.setHostname(slaveName);
          }

          if (request.request.slaveInfo.getContainerInfo().hasPortMappings()) {
              List<MesosSlaveInfo.PortMapping> portMappings = request.request.slaveInfo.getContainerInfo().getPortMappings();
              int portToUseIndex = 0;
              List<Integer> portsToUse = findPortsToUse(offer, portMappings.size());

              Value.Ranges.Builder portRangesBuilder = Value.Ranges.newBuilder();

              for (MesosSlaveInfo.PortMapping portMapping : portMappings) {
                  PortMapping.Builder portMappingBuilder = PortMapping.newBuilder() //
                          .setContainerPort(portMapping.getContainerPort()) //
                          .setProtocol(portMapping.getProtocol());

                  int portToUse = portMapping.getHostPort() == null ? portsToUse.get(portToUseIndex++) : portMapping.getHostPort();

                  portMappingBuilder.setHostPort(portToUse);

                  portRangesBuilder.addRange(
                    Value.Range
                      .newBuilder()
                      .setBegin(portToUse)
                      .setEnd(portToUse)
                  );

                  LOGGER.finest("Adding portMapping: " + portMapping);
                  dockerInfoBuilder.addPortMappings(portMappingBuilder);
              }

              taskBuilder.addResources(
                Resource
                  .newBuilder()
                  .setName("ports")
                  .setType(Value.Type.RANGES)
                  .setRanges(portRangesBuilder)
                  );
          } else {
              LOGGER.fine("No portMappings found");
          }

          containerInfoBuilder.setDocker(dockerInfoBuilder);
          break;
        default:
          LOGGER.warning("Unknown container type:" + containerInfo.getType());
      }

      if (containerInfo.getVolumes() != null) {
        for (MesosSlaveInfo.Volume volume : containerInfo.getVolumes()) {
          LOGGER.info("Adding volume '" + volume.getContainerPath() + "'");
          Volume.Builder volumeBuilder = Volume.newBuilder()
              .setContainerPath(volume.getContainerPath())
              .setMode(volume.isReadOnly() ? Mode.RO : Mode.RW);
          if (!volume.getHostPath().isEmpty()) {
            volumeBuilder.setHostPath(volume.getHostPath());
          }
          containerInfoBuilder.addVolumes(volumeBuilder.build());
        }
      }

      taskBuilder.setContainer(containerInfoBuilder.build());
    }

  @VisibleForTesting
  CommandInfo.Builder getCommandInfoBuilder(Request request) {
        CommandInfo.Builder commandBuilder = getBaseCommandBuilder(request);
        detectAndAddAdditionalURIs(request, commandBuilder);
        detectAndAddExternalContainerInfo(request, commandBuilder);
        return commandBuilder;
  }

  String generateJenkinsCommand2Run(int jvmMem, String jvmArgString, String jnlpArgString, String slaveName, MesosSlaveInfo.RunAsUserInfo runAsUserInfo) {

    String slaveCmd = String.format(SLAVE_COMMAND_FORMAT,
            jvmMem,
            jvmArgString,
            jnlpArgString,
            getJnlpSecret(slaveName),
            getJnlpUrl(slaveName));

    if (runAsUserInfo != null) {
      slaveCmd = runAsUserInfo.getCommand()
              .replace(MesosSlaveInfo.RunAsUserInfo.TOKEN_USERNAME, runAsUserInfo.getUsername())
              .replace(MesosSlaveInfo.RunAsUserInfo.TOKEN_SLAVE_COMMAND, slaveCmd);
    }

    return slaveCmd;
  }

  private CommandInfo.Builder getBaseCommandBuilder(Request request) {

        CommandInfo.Builder commandBuilder = CommandInfo.newBuilder();
        String jenkinsCommand2Run = generateJenkinsCommand2Run(
            request.request.mem,
            request.request.slaveInfo.getJvmArgs(),
            request.request.slaveInfo.getJnlpArgs(),
            request.request.slave.name,
            request.request.slaveInfo.getRunAsUserInfo());

        if (request.request.slaveInfo.getContainerInfo() != null &&
            request.request.slaveInfo.getContainerInfo().getUseCustomDockerCommandShell()) {
            // Ref http://mesos.apache.org/documentation/latest/upgrades
            // regarding setting the shell value, and the impact on the command to be
            // launched
            String customShell = request.request.slaveInfo.getContainerInfo().getCustomDockerCommandShell();
            if (StringUtils.stripToNull(customShell)==null) {
                throw new IllegalArgumentException("Invalid custom shell argument supplied  ");
            }

            LOGGER.info( String.format( "About to use custom shell: %s " , customShell));
            commandBuilder.setShell(false);
            commandBuilder.setValue(customShell);
            List args = new ArrayList();
            args.add(jenkinsCommand2Run);
            commandBuilder.addAllArguments( args );

    } else {
        LOGGER.info("About to use default shell ....");
        commandBuilder.setValue(jenkinsCommand2Run);
    }

    commandBuilder.addUris(
        CommandInfo.URI.newBuilder().setValue(
            joinPaths(jenkinsMaster, SLAVE_JAR_URI_SUFFIX)).setExecutable(false).setExtract(false));
    return commandBuilder;
  }

  /**
   * Checks if the given taskId already exists or just finished running. If it has, then refuse the offer.
   * @param taskId The task id
   * @return True if the task already exists, false otherwise
   */
  @VisibleForTesting
  boolean isExistingTask(TaskID taskId) {
      // If the task has already been queued, don't launch it again
      if (results.containsKey(taskId)) {
          LOGGER.info("Task " + taskId.getValue() + " has already been launched, ignoring and refusing offer");
          return true;
      }

      // If the task has already finished, then do not start it up again even if we are offered it
      if (finishedTasks.contains(taskId)) {
          LOGGER.info("Task " + taskId.getValue() + " has already finished. Ignoring and refusing offer");
          return true;
      }

      return false;
  }

  /**
   * Refuses the offer provided by launching no tasks.
   * @param offer The offer to refuse
   */
  @VisibleForTesting
  void refuseOffer(Offer offer) {
      driver.declineOffer(offer.getId());
  }

  @Override
  public void offerRescinded(SchedulerDriver driver, OfferID offerId) {
    LOGGER.info("Rescinded offer " + offerId);
  }

  @Override
  public void statusUpdate(SchedulerDriver driver, TaskStatus status) {
    TaskID taskId = status.getTaskId();
    LOGGER.fine("Status update: task " + taskId + " is in state " + status.getState() +
                (status.hasMessage() ? " with message '" + status.getMessage() + "'" : ""));

    if (!results.containsKey(taskId)) {
      // The task might not be present in the 'results' map if this is a duplicate terminal
      // update.
      LOGGER.fine("Ignoring status update " + status.getState() + " for unknown task " + taskId);
      return;
    }

    Result result = results.get(taskId);
    boolean terminalState = false;

    switch (status.getState()) {
    case TASK_STAGING:
    case TASK_STARTING:
      break;
    case TASK_RUNNING:
      result.result.running(result.slave);
      break;
    case TASK_FINISHED:
      result.result.finished(result.slave);
      terminalState = true;
      break;
    case TASK_ERROR:
    case TASK_FAILED:
    case TASK_KILLED:
    case TASK_LOST:
      result.result.failed(result.slave);
      terminalState = true;
      break;
    default:
      throw new IllegalStateException("Invalid State: " + status.getState());
    }

    if (terminalState) {
      results.remove(taskId);
    }

    if (mesosCloud.isOnDemandRegistration()) {
      supervise();
    }
  }

  @Override
  public void frameworkMessage(SchedulerDriver driver, ExecutorID executorId,
      SlaveID slaveId, byte[] data) {
    LOGGER.info("Received framework message from executor " + executorId
        + " of slave " + slaveId);
  }

  @Override
  public void slaveLost(SchedulerDriver driver, SlaveID slaveId) {
    LOGGER.info("Slave " + slaveId + " lost!");
  }

  @Override
  public void executorLost(SchedulerDriver driver, ExecutorID executorId,
      SlaveID slaveId, int status) {
    LOGGER.info("Executor " + executorId + " of slave " + slaveId + " lost!");
  }

  @Override
  public void error(SchedulerDriver driver, String message) {
    LOGGER.severe(message);
  }

  /**
  * @return the mesosCloud
  */
  private MesosCloud getMesosCloud() {
    return mesosCloud;
  }

  /**
  * @param mesosCloud the mesosCloud to set
  */
  protected void setMesosCloud(MesosCloud mesosCloud) {
    this.mesosCloud = mesosCloud;
  }

  private class Result {
    private final Mesos.SlaveResult result;
    private final Mesos.JenkinsSlave slave;

    private Result(Mesos.SlaveResult result, Mesos.JenkinsSlave slave) {
      this.result = result;
      this.slave = slave;
    }
  }

  @VisibleForTesting
  class Request {
    private final Mesos.SlaveRequest request;
    private final Mesos.SlaveResult result;

    public Request(Mesos.SlaveRequest request, Mesos.SlaveResult result) {
      this.request = request;
      this.result = result;
    }
  }

  public int getNumberofPendingTasks() {
    return requests.size();
  }

  public int getNumberOfActiveTasks() {
    return results.size();
  }

  public void clearResults() {
    results.clear();
  }

  /**
   * Disconnect framework, if we don't have active mesos slaves. Also, make
   * sure JenkinsScheduler's request queue is empty.
   */
  public static void supervise() {
	SUPERVISOR_LOCK.lock();
    Collection<Mesos> clouds = Mesos.getAllClouds();
    try {
      for (Mesos cloud : clouds) {
        try {
          JenkinsScheduler scheduler = (JenkinsScheduler) cloud.getScheduler();
          if (scheduler != null) {
            boolean pendingTasks = (scheduler.getNumberofPendingTasks() > 0);
            boolean activeSlaves = false;
            boolean activeTasks = (scheduler.getNumberOfActiveTasks() > 0);
            List<Node> slaveNodes = Jenkins.getInstance().getNodes();
            for (Node node : slaveNodes) {
              if (node instanceof MesosSlave) {
                activeSlaves = true;
                break;
              }
            }
            // If there are no active slaves, we should clear up results.
            if (!activeSlaves) {
              scheduler.clearResults();
              activeTasks = false;
            }
            LOGGER.info("Active slaves: " + activeSlaves
                + " | Pending tasks: " + pendingTasks + " | Active tasks: " + activeTasks);
            if (!activeTasks && !activeSlaves && !pendingTasks) {
              LOGGER.info("No active tasks, or slaves or pending slave requests. Stopping the scheduler.");
              cloud.stopScheduler();
            }
          } else {
            LOGGER.info("Scheduler already stopped. NOOP.");
          }
        } catch (Exception e) {
          LOGGER.info("Exception: " + e);
        }
      }
    } finally {
      SUPERVISOR_LOCK.unlock();
    }
  }

  public String getJenkinsMaster() {
    return jenkinsMaster;
  }

  public void setJenkinsMaster(String jenkinsMaster) {
    this.jenkinsMaster = jenkinsMaster;
  }
}
