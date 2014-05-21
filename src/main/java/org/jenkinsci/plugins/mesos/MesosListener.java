package org.jenkinsci.plugins.mesos;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Hudson;
import hudson.model.Item;
import hudson.model.Label;
import hudson.model.listeners.ItemListener;
import org.jenkinsci.plugins.mesos.MesosCloud;
import org.jenkinsci.plugins.mesos.MesosSlaveInfo;

import java.lang.Exception;import java.lang.Override;import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by prdhir on 4/22/2014.
 * Listener for job operations/events (ex- create, update, delete etc.)
 */
@Extension
public class MesosListener  extends ItemListener {
    private static Logger LOGGER = Logger.getLogger(MesosListener.class.getName());

    /**
     * listener for {@link Item} create
     * @param item
     */
    @Override
    public void onCreated(final Item item) {
        LOGGER.log(Level.INFO, "MesosListener.onCreated() called...");
        setLabel(item);
    }

    /**
     * listener for {@link Item} update
     * @param item
     */
    @Override
    public void onUpdated(Item item) {
        LOGGER.log(Level.INFO, "MesosListener.onUpdated() called...");
        setLabel(item);
    }

    /**
     * sets the Label in case no label is assigned to the {@link Item}
     * @param item
     */
    private void setLabel(final Item item){
        if (item == null){
            LOGGER.log(Level.WARNING, "MesosListener.setLabel(), item was null");
            return ;
        }
        LOGGER.info("MesosListener.setLabel(), setting label");
        AbstractProject<?, ?> job = (AbstractProject<?, ?>) item;
        Label label = job.getAssignedLabel();
        try {
            if(label == null) {// No label assigned, override now
                LOGGER.log(Level.INFO, "No label assigned to job - " + item.getDisplayName() + ". Assigning a label now...");
                label = getLabel();
                if (label != null) {
                    LOGGER.log(Level.INFO, "Assigned - " + label.getName() + "  to job - " + item.getDisplayName());
                    job.setAssignedLabel(label);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to assign label \"" + label +"\" to " + item.getDisplayName(), e);
        }
    }

    /**
     * create and gets the label as Hudson {@link hudson.model.Label}
     * @return
     */
    private Label getLabel(){
        Label label = null;
        MesosCloud cloud = MesosCloud.get();//get mesos cloud
        if(cloud != null){
            List<MesosSlaveInfo> list = cloud.getSlaveInfos();//get all label associate  with cloud
            if(list != null && list.size() > 0){
                //TODO: improve the default label allocation logic later when there are multiple label doing different stuff
                label = Hudson.getInstance().getLabel(list.get(0).getLabelString());// picking up the first label
            }
        }
        return label;
    }

}