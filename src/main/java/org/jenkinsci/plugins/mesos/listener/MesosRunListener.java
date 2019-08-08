package org.jenkinsci.plugins.mesos.listener;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.AbstractBuild;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.model.Run;
import hudson.model.listeners.RunListener;

import java.io.IOException;
import java.util.logging.Logger;

import org.jenkinsci.plugins.mesos.MesosSlave;

@SuppressWarnings("rawtypes")
@Extension
public class MesosRunListener extends RunListener<Run> {

  private static final Logger LOGGER = Logger.getLogger(MesosRunListener.class.getName());
  private static final String HOSTNAME = "HOSTNAME";
  private static final String DOCKER = "DOCKER";

  public MesosRunListener() {
    
  }
  
  /**
   * @param targetType
   */
  @SuppressWarnings("unchecked")
  public MesosRunListener(Class targetType) {
    super(targetType);    
  }
  
  /**
   * Prints the actual Hostname where Mesos slave is provisioned in console output.
   * This would help us debug/take action if build fails in that slave.
   */
  @Override
  public void onStarted(Run r, TaskListener listener) {
    if (r instanceof AbstractBuild) {
      Node node = getCurrentNode();
      if (node instanceof MesosSlave) {
        try {
          String hostname = null;
          Computer computer = node.toComputer();
          MesosSlave mesosSlave = (MesosSlave) node;
          if (mesosSlave.getSlaveInfo().getContainerInfo() != null &&
                  DOCKER.equals(mesosSlave.getSlaveInfo().getContainerInfo().getType())) {
            if (computer != null) {
              EnvVars environment = computer.getEnvironment();
              hostname = environment.get(HOSTNAME);
            }
          } else {
              if(computer != null) {
                hostname = computer.getHostName();
              }
          }
          listener.getLogger().println("Mesos slave(hostname): " + hostname);
        } catch (IOException e) {
          LOGGER.warning("IOException while trying to get hostname: " + e);
          e.printStackTrace();
        } catch (InterruptedException e) {
          LOGGER.warning("InterruptedException while trying to get hostname: " + e);
        }
      }
    }
  }

  /**
   * Returns the current {@link Node} on which we are building.
   */
  private final Node getCurrentNode() {
    Executor executor = Executor.currentExecutor();
    if (executor != null) {
      Computer owner = executor.getOwner();
      if (owner != null) {
        return owner.getNode();
      }
    }
    return null;
  }

}
