package org.jenkinsci.plugins.mesos;

import org.apache.mesos.Scheduler;

public class MesosImpl extends Mesos {
  @Override
  public void startScheduler(String jenkinsMaster, MesosCloud mesosCloud) {
    lock();
    try {
      stopScheduler();
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
  public void stopScheduler() {
    lock();
    try {
      if (scheduler != null) {
        scheduler.stop();
        scheduler = null;
      }
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
