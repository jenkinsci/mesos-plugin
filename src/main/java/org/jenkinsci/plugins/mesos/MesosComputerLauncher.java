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

import hudson.model.TaskListener;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.SlaveComputer;
import jenkins.metrics.api.Metrics;

import org.jenkinsci.plugins.mesos.Mesos.JenkinsSlave;

import java.io.PrintStream;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;

import com.codahale.metrics.Timer;

public class MesosComputerLauncher extends JNLPLauncher {

  private final MesosCloud cloud;

  enum State { INIT, RUNNING, FAILURE }

  private static final Logger LOGGER = Logger.getLogger(MesosComputerLauncher.class.getName());

  public MesosComputerLauncher(MesosCloud cloud, String _name) {
    super();
    LOGGER.finer("Constructing MesosComputerLauncher");
    this.cloud = cloud;
    this.state = State.INIT;
    this.name = _name;
  }

  /**
   * Launches a mesos task that starts the jenkins slave.
   *
   * NOTE: This has to be a blocking call:
   *
   * @see hudson.slaves.ComputerLauncher#launch(hudson.slaves.SlaveComputer,
   *      hudson.model.TaskListener)
   */
  @Override
  public void launch(SlaveComputer _computer, TaskListener listener)  {
    LOGGER.fine("Launching slave computer " + name);

    MesosComputer computer = (MesosComputer) _computer;
    PrintStream logger = listener.getLogger();

    // Get a handle to mesos.
    Mesos mesos = Mesos.getInstance(cloud);

    // If Jenkins scheduler is not running, terminate the node.
    // This might happen if the computer was offline when Jenkins was shutdown.
    // Since Jenkins persists its state, it tries to launch offline slaves when
    // it restarts.
    MesosSlave node = computer.getNode();
    if (!mesos.isSchedulerRunning()) {
      Metrics.metricRegistry().counter("mesos.computer.launcher.launch.fail").inc();

      LOGGER.warning("Not launching " + name +
                     " because the Mesos Jenkins scheduler is not running");
      if (node != null) {
        node.terminate();
      }
      return;
    }
    if (node == null) {
      return;
    }

    // Create the request.
    double cpus = node.getCpus();
    int mem = node.getMem();
    double diskNeeded = node.getDiskNeeded();
    String role = cloud.getRole();

    Mesos.SlaveRequest request = new Mesos.SlaveRequest(new JenkinsSlave(name), node,
            cpus, mem, role, node.getSlaveInfo(), diskNeeded);

    // Launch the jenkins slave.
    final CountDownLatch latch = new CountDownLatch(1);

    logger.println("Starting mesos slave " + name);
    LOGGER.info("Sending a request to start jenkins slave " + name);

    mesos.startJenkinsSlave(request, new Mesos.SlaveResult() {
      public void running(JenkinsSlave slave) {
        state = State.RUNNING;
        latch.countDown();
      }

      public void finished(JenkinsSlave slave) {
        state = State.FAILURE;
        latch.countDown();
      }

      public void failed(JenkinsSlave slave) {
        state = State.FAILURE;
        latch.countDown();
      }
    });

    // Block until we know the status of the slave.
    // TODO(vinod): What happens if the callback is called again!
    try {
      latch.await();
    } catch (InterruptedException e) {
      e.printStackTrace();
      LOGGER.severe("Error while launching slave " + name);
      node.setPendingDelete(true);
      return;
    }

    if (state == State.RUNNING) {
      // Since we just launched a slave, remove it from pending deletion in case it was marked previously while
      // we were waiting for resources to be available
      node.setPendingDelete(false);
      waitForSlaveConnection(computer, logger);

      computer.getNode().provisionedAndReady();
      logger.println("Successfully launched slave " + name);
    }

    LOGGER.info("Finished launching slave computer " + name);
  }

  private void waitForSlaveConnection(MesosComputer computer, PrintStream logger) {
    while (computer.isOffline() && computer.isConnecting() && !state.equals(State.FAILURE)) {
      try {
        logger.println("Waiting for slave computer connection " + name);
        Thread.sleep(5000);
      } catch (InterruptedException ignored) { return; }
    }
    if (computer.isOnline()) {
      logger.println("Slave computer connected " + name);
      LOGGER.info(String.format("Slave %s connected", computer.getNode().getUuid()));
    } else {
      LOGGER.warning("Slave computer offline " + name);
    }
  }

  /**
   * Kills the mesos task that corresponds to the Jenkins slave, asynchronously.
   */
  public void terminate() {
    // Get a handle to mesos.
    Mesos mesos = Mesos.getInstance(cloud);

    mesos.stopJenkinsSlave(name);
  }

  private volatile State state;
  private final String name;
}
