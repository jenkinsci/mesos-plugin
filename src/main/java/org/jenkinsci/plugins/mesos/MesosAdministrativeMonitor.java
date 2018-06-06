package org.jenkinsci.plugins.mesos;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AdministrativeMonitor;
import jenkins.model.Jenkins;
import org.apache.mesos.Scheduler;

import java.util.HashSet;
import java.util.Set;

/**
 * An administrative monitor warning the administrator in case some slaveInfos are unable to provision tasks.
 */
@Extension
public class MesosAdministrativeMonitor extends AdministrativeMonitor {

    @NonNull
    static Set<String> getLabels() {
        Set<String> result = new HashSet<>();
        for (Mesos mesos : Mesos.getAllClouds()) {
            Scheduler scheduler = mesos.getScheduler();
            if (scheduler instanceof JenkinsScheduler) {
                result.addAll(((JenkinsScheduler) scheduler).getUnmatchedLabels());
            }
        }
        return result;
    }

    @Override
    public String getDisplayName() {
        return super.getDisplayName();
    }

    @Override
    public boolean isActivated() {
        return !getLabels().isEmpty();
    }

    static MesosAdministrativeMonitor getAdministrativeMonitor() {
        return (MesosAdministrativeMonitor) Jenkins.getInstance().getAdministrativeMonitor(MesosAdministrativeMonitor.class.getName());
    }
}
