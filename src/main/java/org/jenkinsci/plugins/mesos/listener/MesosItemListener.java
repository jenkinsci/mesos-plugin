package org.jenkinsci.plugins.mesos.listener;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Hudson;
import hudson.model.Item;
import hudson.model.Label;
import hudson.model.listeners.ItemListener;
import org.jenkinsci.plugins.mesos.MesosCloud;
import org.jenkinsci.plugins.mesos.MesosSlaveInfo;

import java.lang.Exception;
import java.lang.Override;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;


@Extension
public class MesosItemListener  extends ItemListener {
    private static Logger LOGGER = Logger.getLogger(MesosItemListener.class.getName());

    /**
     * Listener for {@link Item} create
     * @param item
     */
    @Override
    public void onCreated(final Item item) {
        setLabel(item);
    }

    /**
     * Listener for {@link Item} update
     * @param item
     */
    @Override
    public void onUpdated(Item item) {
        setLabel(item);
    }

    /**
     * Set the default Slave Info Label if no Label is assigned to the {@link Item}
     * @param item
     */
    private void setLabel(final Item item) {
        if (item instanceof AbstractProject) {
            AbstractProject<?, ?> job = (AbstractProject<?, ?>) item;
            LOGGER.fine("MesosListener.setLabel(), setting label");
            Label label = job.getAssignedLabel();
            try {
                if (label == null) { // No label assigned, override now
                    LOGGER.log(Level.FINE, "No label assigned to job - " + job.getDisplayName() + ". Assigning a label now...");
                    label = getLabel();
                    if (label != null) {
                        LOGGER.log(Level.INFO, "Assigned \"" + label.getName() + "\"  to job \"" + job.getDisplayName() + "\"");
                        job.setAssignedLabel(label);
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to assign label \"" + label + "\" to " + job.getDisplayName(), e);
            }
        }
    }

    /**
     * Get the default Slave Info Label as Hudson {@link hudson.model.Label}
     * @return
     */
    private Label getLabel() {
        Label label = null;
        // get mesos cloud
        MesosCloud cloud = MesosCloud.get();
        if(cloud != null) {
            // get all label associate  with cloud
            List<MesosSlaveInfo> list = cloud.getSlaveInfos();
            if(list != null && list.size() > 0) {
                for (MesosSlaveInfo slaveInfo: list) {
                    if (slaveInfo.isDefaultSlave()) {
                        label = Hudson.getInstance().getLabel(slaveInfo.getLabelString());
                        break;
                    }
                }
            }
        }
        return label;
    }

}
