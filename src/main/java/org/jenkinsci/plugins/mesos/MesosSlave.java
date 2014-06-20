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
import hudson.model.Computer;
import hudson.model.Descriptor.FormException;
import hudson.model.Hudson;
import hudson.model.Slave;
import hudson.slaves.NodeProperty;
import hudson.slaves.ComputerLauncher;

import java.io.IOException;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.kohsuke.stapler.DataBoundConstructor;

public class MesosSlave extends Slave {

  private final double cpus;
  private final int mem;
  private final String jvmArgs;
  private final String containerImage;

  private static final Logger LOGGER = Logger.getLogger(MesosSlave.class
      .getName());

  @DataBoundConstructor
  public MesosSlave(String name, int numExecutors, String labelString,
      double slaveCpus, int slaveMem, double executorCpus, int executorMem,
      int idleTerminationMinutes, String jvmArgs, String containerImage) throws FormException, IOException
  {
    super(name,
          labelString, // node description.
          "jenkins",   // remoteFS.
          "" + numExecutors,
          Mode.NORMAL,
          labelString, // Label.
          new MesosComputerLauncher(name),
          new MesosRetentionStrategy(idleTerminationMinutes),
          Collections.<NodeProperty<?>> emptyList());

    this.cpus = slaveCpus + (numExecutors * executorCpus);
    this.mem = slaveMem + (numExecutors * executorMem);
    this.jvmArgs = jvmArgs;
    this.containerImage = containerImage;

    LOGGER.info("Constructing Mesos slave");
  }

  public double getCpus() {
    return cpus;
  }

  public int getMem() {
    return mem;
  }

  public String getJvmArgs() {
    return jvmArgs;
  }

  public String getContainerImage() {
    return containerImage;
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
  }

  private String getInstanceId() {
    return getNodeName();
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
}
