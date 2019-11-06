package org.jenkinsci.plugins.mesos;

import com.codahale.metrics.health.HealthCheck;
import com.google.common.base.Joiner;

import static org.jenkinsci.plugins.mesos.MesosAdministrativeMonitor.getAdministrativeMonitor;

public class MesosHealthCheck extends HealthCheck {
    @Override
    protected Result check() throws Exception {
        MesosAdministrativeMonitor administrativeMonitor = getAdministrativeMonitor();
        if (administrativeMonitor != null) {
            if (administrativeMonitor.isActivated()) {
                return Result.unhealthy("Some mesos slaves cannot be provisioned : " + Joiner.on(',').join(administrativeMonitor.getLabels()));
            } else {
                return Result.healthy();
            }
        }
        return Result.healthy();
    }
}
