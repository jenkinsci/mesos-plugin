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
 * Allows to access a workspace through jenkins master. Primary use case is
 * cloud based CI implementation where slaves won't be there online forever.
 * Note that control comes here only when nodes are not accessible or offline.
 * Make sure that workspace are created in mounts/filer which is accessible from
 * jenkins master.
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
