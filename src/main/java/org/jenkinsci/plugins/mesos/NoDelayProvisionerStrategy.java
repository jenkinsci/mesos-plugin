package org.jenkinsci.plugins.mesos;

import hudson.Extension;
import hudson.model.Label;
import hudson.model.LoadStatistics;
import hudson.slaves.Cloud;
import hudson.slaves.CloudProvisioningListener;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.NodeProvisioner.PlannedNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import jenkins.model.Jenkins;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is a fork of the Kubernetes plugin provision strategy.
 *
 * <p>Implementation of {@link NodeProvisioner.Strategy} which will provision a new node immediately
 * as a task enter the queue. In kubernetes, we don't really need to wait before provisioning a new
 * node, because kubernetes agents can be started and destroyed quickly
 *
 * @author <a href="mailto:root@junwuhui.cn">runzexia</a>
 */
@Extension(ordinal = 100)
public class NoDelayProvisionerStrategy extends NodeProvisioner.Strategy {

  private static final Logger logger = LoggerFactory.getLogger(MesosCloud.class);

  private static final boolean DISABLE_NODELAY_PROVISING =
      Boolean.valueOf(System.getProperty("io.jenkins.plugins.mesos.disableNoDelayProvisioning"));

  @Override
  public NodeProvisioner.StrategyDecision apply(NodeProvisioner.StrategyState strategyState) {
    if (DISABLE_NODELAY_PROVISING) {
      logger.info("Provisioning not complete, NoDelayProvisionerStrategy is disabled");
      return NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES;
    }

    final Label label = strategyState.getLabel();

    LoadStatistics.LoadStatisticsSnapshot snapshot = strategyState.getSnapshot();
    int availableCapacity =
        snapshot.getAvailableExecutors() // live executors
            + snapshot.getConnectingExecutors() // executors present but not yet connected
            + strategyState
                .getPlannedCapacitySnapshot() // capacity added by previous strategies from previous
            // rounds
            + strategyState
                .getAdditionalPlannedCapacity(); // capacity added by previous strategies _this
    // round_
    int currentDemand = snapshot.getQueueLength();
    logger.info("Available capacity={}, currentDemand={}", availableCapacity, currentDemand);
    if (availableCapacity < currentDemand) {
      List<Cloud> jenkinsClouds = new ArrayList<>(Jenkins.get().clouds);
      Collections.shuffle(jenkinsClouds);
      for (Cloud cloud : jenkinsClouds) {
        int workloadToProvision = currentDemand - availableCapacity;
        if (!(cloud instanceof MesosCloud)) continue;
        if (!cloud.canProvision(label)) continue;
        for (CloudProvisioningListener cl : CloudProvisioningListener.all()) {
          if (cl.canProvision(cloud, strategyState.getLabel(), workloadToProvision) != null) {
            continue;
          }
        }
        Collection<PlannedNode> plannedNodes = cloud.provision(label, workloadToProvision);
        logger.info("Planned {} new nodes", plannedNodes.size());
        fireOnStarted(cloud, strategyState.getLabel(), plannedNodes);
        strategyState.recordPendingLaunches(plannedNodes);
        availableCapacity += plannedNodes.size();
        logger.info(
            "After provisioning, available capacity={}, currentDemand={}",
            availableCapacity,
            currentDemand);
        break;
      }
    }
    if (availableCapacity >= currentDemand) {
      logger.info("Provisioning completed");
      return NodeProvisioner.StrategyDecision.PROVISIONING_COMPLETED;
    } else {
      logger.info("Provisioning not complete, consulting remaining strategies");
      return NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES;
    }
  }

  private static void fireOnStarted(
      final Cloud cloud,
      final Label label,
      final Collection<NodeProvisioner.PlannedNode> plannedNodes) {
    for (CloudProvisioningListener cl : CloudProvisioningListener.all()) {
      try {
        cl.onStarted(cloud, label, plannedNodes);
      } catch (Error e) {
        throw e;
      } catch (Throwable e) {
        logger.error(
            "Unexpected uncaught exception encountered while "
                + "processing onStarted() listener call in "
                + cl
                + " for label "
                + label.toString(),
            e);
      }
    }
  }
}
