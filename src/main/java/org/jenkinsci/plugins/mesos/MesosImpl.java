package org.jenkinsci.plugins.mesos;

import org.apache.mesos.Scheduler;

import java.util.logging.Logger;

public class MesosImpl extends Mesos {

  private static final Logger LOGGER = Logger.getLogger(MesosImpl.class.getName());

  @Override
  public void startScheduler(String jenkinsMaster, MesosCloud mesosCloud) {
    if (jenkinsMaster == null) {
      throw new IllegalArgumentException("Cannot start scheduler if jenkinsMaster is null");
    }
    lock();
    try {
      scheduler = new JenkinsScheduler(jenkinsMaster, mesosCloud);
      scheduler.init();
    } finally {
      unlock();
    }
  }

  @Override
  public boolean isSchedulerRunning() {
    lock();
    try {
      return scheduler != null && scheduler.isRunning();
    } finally {
      unlock();
    }
  }

  @Override
  public boolean stopScheduler(boolean force) {
    lock();
    try {
      if (scheduler != null) {
        if (force || scheduler.reachedMinimumTimeToLive()) {
          scheduler.stop();
          scheduler = null;
          return true;
        } else {
          LOGGER.info("Not stopping scheduler because it has been created too recently.");
          return false;
        }
      }
      return true;
    } finally {
      unlock();
    }
  }

  @Override
  public void startJenkinsSlave(SlaveRequest request, SlaveResult result) {
    lock();
    try {
      if (scheduler != null) {
        scheduler.requestJenkinsSlave(request, result);
      }
    } finally {
      unlock();
    }
  }

  @Override
  public void stopJenkinsSlave(String name) {
    lock();
    try {
      if (scheduler != null) {
        scheduler.terminateJenkinsSlave(name);
      }
    } finally {
      unlock();
    }
  }

  @Override
  public void updateScheduler(String jenkinsMaster, MesosCloud mesosCloud) {
    lock();
    try {
      scheduler.setMesosCloud(mesosCloud);
      scheduler.setJenkinsMaster(jenkinsMaster);
    } finally {
      unlock();
    }
  }

  private JenkinsScheduler scheduler;

  @Override
  public Scheduler getScheduler() {
    return scheduler;
  }

  private void unlock() {
    JenkinsScheduler.SUPERVISOR_LOCK.unlock();
  }

  private void lock() {
    JenkinsScheduler.SUPERVISOR_LOCK.lock();
  }
}
