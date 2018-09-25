package org.jenkinsci.plugins.mesos;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by maselvaraj on 7/12/17.
 * This tracks the most recent workspace that was used for a particular Jenkins job so that the user
 * shall be able to view the most recent workspace even after the slave went offline.
 * This is useful only when shared storage like NFS is used for builds and the same shared storage
 * is mounted at same path on Jenkins master.
 * Refer: MesosWorkspaceBrowser
 *
 */

public class MesosRecentWSTracker {

    private static MesosRecentWSTracker mesosRecentWSTracker = new MesosRecentWSTracker();

    private MesosRecentWSTracker() {
    }

    static MesosRecentWSTracker getMesosRecentWSTracker() {
        return mesosRecentWSTracker;
    }

    private final Map<String, String> recentWorkspaceMap = new HashMap<String, String>();

    public Map<String, String> getRecentWorkspaceMap() {
        return recentWorkspaceMap;
    }

    public void updateRecentWorkspaceMap(String jobName, String workspace) {
        recentWorkspaceMap.put(jobName,workspace);
    }

    public String getRecentWorkspaceForJob(String jobName) {
       return recentWorkspaceMap.get(jobName);
    }
}
