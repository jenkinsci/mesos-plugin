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
import hudson.XmlFile;
import hudson.model.Saveable;
import hudson.model.listeners.SaveableListener;
import hudson.model.RestartListener;
import jenkins.model.Jenkins;
import org.apache.mesos.Scheduler;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Logger;

public abstract class Mesos {
  private static Map<MesosCloud, Mesos> clouds = new HashMap<MesosCloud, Mesos>();

  private static final Logger LOGGER = Logger.getLogger(Mesos.class.getName());

  public static class JenkinsSlave {
    String name;

    public JenkinsSlave(String name) {
      this.name = name;
    }
  }

  public static class SlaveRequest {
    JenkinsSlave slave;
    final double cpus;
    final int mem;
    final String role;
    final MesosSlaveInfo slaveInfo;

    public SlaveRequest(JenkinsSlave slave, double cpus, int mem, String role,
        MesosSlaveInfo slaveInfo) {
      this.slave = slave;
      this.cpus = cpus;
      this.mem = mem;
      this.role = role;
      this.slaveInfo = slaveInfo;
    }
  }


  interface SlaveResult {
    void running(JenkinsSlave slave);

    void finished(JenkinsSlave slave);

    void failed(JenkinsSlave slave);
  }

  abstract public void startScheduler(String jenkinsMaster, MesosCloud mesosCloud);
  abstract public void updateScheduler(String jenkinsMaster, MesosCloud mesosCloud);
  abstract public boolean isSchedulerRunning();

  /**
   * Stops the scheduler gracefully.
   * @return {@code true} if the scheduler is stopped after the call, {@code false} otherwise.
   */
  public boolean stopScheduler() {
    return stopScheduler(false);
  }
  /**
   * Stops the scheduler.
   * @param force {@code false} to stop the scheduler gracefully, {@code true} to force stop
   * @return {@code true} if the scheduler is stopped after the call, {@code false} otherwise.
   */
  abstract public boolean stopScheduler(boolean force);
  abstract public Scheduler getScheduler();
  /**
   * Starts a jenkins slave asynchronously in the mesos cluster.
   *
   * @param request
   *          slave request.
   * @param result
   *          this callback will be called when the slave starts.
   */
  abstract public void startJenkinsSlave(SlaveRequest request, SlaveResult result);


  /**
   * Stop a jenkins slave asynchronously in the mesos cluster.
   *
   * @param name
   *          jenkins slave.
   *
   */
  abstract public void stopJenkinsSlave(String name);

  /**
   * @return the mesos implementation instance for the cloud instances (since there might be more than one
   */
  public static synchronized Mesos getInstance(MesosCloud key) {
    if (!clouds.containsKey(key)) {
      clouds.put(key, new MesosImpl());
    }
    return clouds.get(key);
  }

  public static Collection<Mesos> getAllClouds() {
    return clouds.values();
  }


  /**
   * When Jenkins configuration is saved, teardown any active scheduler whose cloud has been removed.
   */
  @Extension
  public static class GarbageCollectorImpl extends SaveableListener {

    @Override
    public void onChange(Saveable o, XmlFile file) {
      if (o instanceof Jenkins) {
        Jenkins j = (Jenkins) o;
        for (Iterator<Map.Entry<MesosCloud, Mesos>> it = clouds.entrySet().iterator(); it.hasNext();) {
          Map.Entry<MesosCloud, Mesos> entry = it.next();
          if (!j.clouds.contains(entry.getKey())) {
            entry.getValue().stopScheduler(true);
            it.remove();
          }
          }
      }
    }
  }

  /**
   * Before Jenkins is restarted, remove the frameworkID associated with the current instance.
   * Jenkins tears down all frameworks when restarted, so jobs do not survive.
   */
  @Extension
  public static class frameworkIDCleaner extends RestartListener {

    @Override
    public boolean isReadyToRestart() throws IOException, InterruptedException {
      return true;
    }

    @Override
    public void onRestart() {
        for (Iterator<Map.Entry<MesosCloud, Mesos>> it = clouds.entrySet().iterator(); it.hasNext();) {
          Map.Entry<MesosCloud, Mesos> entry = it.next();
          LOGGER.info(entry.getKey().getFrameworkID());
          entry.getKey().setFrameworkID(null);
          entry.getKey().setFailoverTimeout("100.0");
          LOGGER.info(entry.getKey().getFrameworkID());
          }
      }
    }
  }

