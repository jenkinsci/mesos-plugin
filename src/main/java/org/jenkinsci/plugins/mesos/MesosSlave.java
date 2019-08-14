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
import hudson.FilePath;
import hudson.model.*;
import hudson.model.Descriptor.FormException;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.WorkspaceList;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.codahale.metrics.Timer;
import org.apache.commons.lang.StringUtils;

public class MesosSlave extends Slave {

  private static final long serialVersionUID = 1L;

  private final Object lock = new Object();

  private final String cloudName;
  private transient MesosCloud cloud;
  private final MesosSlaveInfo slaveInfo;
  private final int idleTerminationMinutes;
  private final double cpus;
  private final int mem;
  private final double diskNeeded;
  private final UUID uuid = UUID.randomUUID();
  private transient final Timer.Context provisionToReady;
  private transient final Timer.Context provisionToMesos;
  private transient final Timer mesosToReady;
  private transient long mesosHandoffTime;
  private boolean singleUse;



  private boolean pendingDelete;

  private static final Logger LOGGER = Logger.getLogger(MesosSlave.class
      .getName());

  public MesosSlave(MesosCloud cloud,
                    String name,
                    int numExecutors,
                    MesosSlaveInfo slaveInfo,
                    Timer.Context provisionToReadyContext,
                    Timer.Context provisionToMesosContext,
                    Timer mesosToReady) throws IOException, FormException {
    super(name,
          slaveInfo.getLabelString(), // node description.
          StringUtils.isBlank(slaveInfo.getRemoteFSRoot()) ? "jenkins" : slaveInfo.getRemoteFSRoot().trim(),   // remoteFS.
          "" + numExecutors,
          slaveInfo.getMode(),
          slaveInfo.getLabelString(), // Label.
          new MesosComputerLauncher(cloud, name),
          new MesosRetentionStrategy(slaveInfo.getIdleTerminationMinutes()),
          slaveInfo.getNodeProperties());
    this.cloud = cloud;
    this.cloudName = cloud.getDisplayName();
    this.slaveInfo = slaveInfo;
    this.idleTerminationMinutes = slaveInfo.getIdleTerminationMinutes();
    this.cpus = slaveInfo.getSlaveCpus() + (numExecutors * slaveInfo.getExecutorCpus());
    this.mem = slaveInfo.getSlaveMem() + (numExecutors * slaveInfo.getExecutorMem());
    this.diskNeeded = slaveInfo.getdiskNeeded();
    this.provisionToReady = provisionToReadyContext;
    this.provisionToMesos = provisionToMesosContext;
    this.mesosToReady = mesosToReady;
    this.singleUse = false;
    LOGGER.fine("Constructing Mesos slave " + name + " from cloud " + cloud.getDescription());
  }

  public MesosCloud getCloud() {
    if (cloud == null) {
      cloud = (MesosCloud) getJenkins().getCloud(cloudName);
    }
    return cloud;
  }

  private Jenkins getJenkins() {
    Jenkins jenkins = Jenkins.getInstance();
    if (jenkins == null) {
      throw new IllegalStateException("Jenkins is null");
    }
    return jenkins;
  }

  public double getCpus() {
    return cpus;
  }

  public int getMem() {
    return mem;
  }


  public double getDiskNeeded() {
    return diskNeeded;
  }

  public MesosSlaveInfo getSlaveInfo() {
    return slaveInfo;
  }

  public int getIdleTerminationMinutes() {
    return idleTerminationMinutes;
  }

  public void provisionedAndReady() {
    mesosToReady.update(System.currentTimeMillis() - mesosHandoffTime, TimeUnit.MILLISECONDS);
    provisionToReady.stop();
  }

  public void provisionedToMesos() {
    provisionToMesos.stop();
    mesosHandoffTime = System.currentTimeMillis();
  }

  public UUID getUuid() {
    return uuid;
  }

  public void terminate() {
    LOGGER.info("Terminating slave " + getNodeName());
    try {
      // Remove the node from hudson.
      Hudson.getInstance().removeNode(this);

      ComputerLauncher launcher = getLauncher();

      // If this is a mesos computer launcher, terminate the launcher.
      if (launcher instanceof MesosComputerLauncher) {
        ((MesosComputerLauncher) launcher).terminate();
      }
    } catch (IOException e) {
      LOGGER.log(Level.WARNING, "Failed to terminate Mesos instance: "
          + getInstanceId(), e);
    }
  }

  @Override
  public DescriptorImpl getDescriptor() {
    return (DescriptorImpl) super.getDescriptor();
  }

  @Extension
  public static class DescriptorImpl extends SlaveDescriptor {
    @Override
    public String getDisplayName() {
      return "Mesos Slave";
    }

    @Override
    public boolean isInstantiable() {
      return false;
    }
  }

  private String getInstanceId() {
    return getNodeName();
  }

  public boolean isPendingDelete() {
    synchronized (lock) {
      return pendingDelete;
    }
  }

  public void setPendingDelete(boolean pendingDelete) {
    synchronized (lock) {
      this.pendingDelete = pendingDelete;
    }
  }

  public boolean isSingleUse() {
    synchronized (lock) {
      return singleUse;
    }
  }

  public void setSingleUse(boolean singleUse) {
    synchronized (lock) {
      this.singleUse = singleUse;
    }
  }

  public void idleTimeout() {
    LOGGER.info("Mesos instance idle time expired: " + getInstanceId()
        + ", terminate now");
    terminate();
  }

  @Override
  public Computer createComputer() {
    return new MesosComputer(this);
  }

  @Override
  public FilePath getRootPath() {
    FilePath rootPath = createPath(remoteFS);
    if (rootPath != null) {
      try {
        // Construct absolute path for slave's remote file system root.
        rootPath = rootPath.absolutize();
      } catch (IOException e) {
        LOGGER.warning("IO exception while absolutizing slave root path: " +e);
      } catch (InterruptedException e) {
        LOGGER.warning("InterruptedException while absolutizing slave root path: " +e);
      }
    }
    // Return root path even if we caught an exception,
    // let the caller handle the error.
    return rootPath;
  }

  @Override
  public FilePath getWorkspaceFor(TopLevelItem item) {

    if(!getCloud().isNfsRemoteFSRoot()) {
      return super.getWorkspaceFor(item);
    }

    FilePath r = getWorkspaceRoot();
    if(r==null)     return null;    // offline
    FilePath child = r.child(item.getFullName());
    if (child!=null && item instanceof AbstractProject) {
      AbstractProject project = (AbstractProject) item;
      for (int i=1; ; i++) {
        FilePath candidate = i == 1 ? child : child.withSuffix(COMBINATOR + i);
        boolean candidateInUse = false;
        for (Object run : project.getBuilds()) {
          if (run instanceof AbstractBuild) {
            AbstractBuild build = (AbstractBuild) run;
            if (build.isBuilding() && build.getWorkspace()!=null && build.getWorkspace().getBaseName().equals(candidate.getName())) {
              candidateInUse = true;
              break;
            }
          }
        }
        if (!candidateInUse) {
          // Save the workspace folder name so that user can view the workspace through MesosWorkspaceBrowser even after slave goes offline
          MesosRecentWSTracker.getMesosRecentWSTracker().updateRecentWorkspaceMap(item.getName(), candidate.getName());
          return candidate;
        }

      }
    }
    return child;
  }

  // Let us use the same property that is used by Jenkins core to get combinator for workspace
  private static final String COMBINATOR = System.getProperty(WorkspaceList.class.getName(),"@");
}
