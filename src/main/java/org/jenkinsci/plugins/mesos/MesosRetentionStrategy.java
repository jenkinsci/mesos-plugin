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

import static java.util.concurrent.TimeUnit.MINUTES;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import org.joda.time.DateTimeUtils;
import hudson.slaves.OfflineCause;

import hudson.model.Descriptor;
import hudson.slaves.RetentionStrategy;

/**
 * This is inspired by {@link hudson.slaves.CloudRetentionStrategy}.
 */
public class MesosRetentionStrategy extends RetentionStrategy<MesosComputer> {

  /**
   * Number of minutes of idleness before an instance should be terminated. A
   * value of zero indicates that the instance should never be automatically
   * terminated.
   */
  public final int idleTerminationMinutes;
  private transient ReentrantLock computerCheckLock = new ReentrantLock(false);

  private static final Logger LOGGER = Logger
      .getLogger(MesosRetentionStrategy.class.getName());

  public MesosRetentionStrategy(int idleTerminationMinutes) {
    this.idleTerminationMinutes = idleTerminationMinutes;
  }

  private void readResolve() {
    computerCheckLock = new ReentrantLock(false);
  }

  @Override
  public long check(MesosComputer c) {
    if (!computerCheckLock.tryLock()) {
      return 1;
    } else {
      try {
        return checkInternal(c);
      } finally {
        computerCheckLock.unlock();
      }
    }
  }

  /**
   * Checks if the computer has expired and marks it for deletion.
   * {@link org.jenkinsci.plugins.mesos.MesosCleanupThread} will then come around and terminate those tasks
   * @param c The Mesos Computer
   * @return The number of minutes to check again afterwards
   */
  private long checkInternal(MesosComputer c) {
    MesosSlave node = c.getNode();
    if (node == null || node.isPendingDelete()) {
      return 1;
    }

    // Terminate if the computer is idle and a single-use agent.
    if (c.isIdle() && c.isOffline() && node.isSingleUse()) {
      LOGGER.info("Disconnecting single-use computer " + c.getName());
      node.setPendingDelete(true);
      return 1;
    }

    // If we just launched this computer, check back after 1 min.
    // NOTE: 'c.getConnectTime()' refers to when the Jenkins slave was launched.
    if ((DateTimeUtils.currentTimeMillis() - c.getConnectTime()) <
        MINUTES.toMillis(idleTerminationMinutes < 1 ? 1 : idleTerminationMinutes)) {
      return 1;
    }

    // Terminate the computer if it is idle for longer than
    // 'idleTerminationMinutes'.
    if (isTerminable() && c.isIdle()) {
      final long idleMilliseconds =
          DateTimeUtils.currentTimeMillis() - c.getIdleStartMilliseconds();

      if (idleMilliseconds > MINUTES.toMillis(idleTerminationMinutes)) {
        LOGGER.info("Disconnecting idle computer " + c.getName());
        node.setPendingDelete(true);

        if (!c.isOffline()) {
          c.setTemporarilyOffline(true, OfflineCause.create(Messages._MesosRetentionStrategy_DeletedCause()));
        }
      }
    }
    return 1;
  }

  /**
   * Try to connect to it ASAP to launch the slave agent.
   */
  @Override
  public void start(MesosComputer c) {
    c.connect(false);
  }

  /**
   * No registration since this retention strategy is used only for Mesos nodes
   * that we provision automatically.
   */
  public static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {
    @Override
    public String getDisplayName() {
      return "MESOS";
    }
  }

  boolean isTerminable() {
    return idleTerminationMinutes != 0;
  }
}
