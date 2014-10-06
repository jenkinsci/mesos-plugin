package org.jenkinsci.plugins.mesos;

import hudson.Extension;
import hudson.FilePath;
import hudson.model.WorkspaceBrowser;
import hudson.model.AbstractProject;
import hudson.model.Job;

import java.io.File;
import java.util.List;
import java.util.logging.Logger;

/**
 * Allows access to the Jenkins slave's workspace when the Jenkins slave is offline.
 * The assumption here is that the Jenkins slave's root path (configurable via "Remote FS Root"
 * option) points to a shared storage location (e.g., NFS mount) accessible to the Jenkins master.
 * Note that when the Jenkins slave is online, the workspace is directly provided 
 * by the slave and the control doesn't reach here.
 */
@Extension
public class MesosWorkspaceBrowser extends WorkspaceBrowser {

  private static final Logger LOGGER = Logger
      .getLogger(MesosWorkspaceBrowser.class.getName());

  private static final String WORKSPACE = "workspace";

  @Override
  public FilePath getWorkspace(Job job) {
    LOGGER.info("Nodes went offline. Hence fetching it through master");
    String jobName = job.getName();
    if (job instanceof AbstractProject) {
      String assignedLabel = ((AbstractProject) job).getAssignedLabelString();
      MesosCloud mesosCloud = MesosCloud.get();
      if (mesosCloud != null) {
        List<MesosSlaveInfo> slaveInfos = mesosCloud.getSlaveInfos();
        for (MesosSlaveInfo mesosSlaveInfo : slaveInfos) {
          if (mesosSlaveInfo.getLabelString().equals(assignedLabel)) {
            String workspacePath = mesosSlaveInfo.getRemoteFSRoot()
                + File.separator + WORKSPACE + File.separator + jobName;
            LOGGER.info("Workspace Path: " + workspacePath);
            File workspace = new File(workspacePath);
            LOGGER.info("Workspace exists ? " + workspace.exists());
            if (workspace.exists()) {
              return new FilePath(workspace);
            }
          }
        }
      }
    }
    return null;
  }

}
