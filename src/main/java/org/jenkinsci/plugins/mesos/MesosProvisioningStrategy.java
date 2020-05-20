package org.jenkinsci.plugins.mesos;

import hudson.Extension;
import hudson.model.Label;
import hudson.model.LoadStatistics.LoadStatisticsSnapshot;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.slaves.NodeProvisioner.StrategyDecision;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Credit where's credit due @antweiss for nomad-plugin, @kostyasha for yet-another-docker-plugin
 * and @stephenc for mansion-cloud.
 *
 * @author jeschkies
 */
@Extension(ordinal = 10)
public class MesosProvisioningStrategy extends NodeProvisioner.Strategy {

  private static final Logger logger = LoggerFactory.getLogger(MesosCloud.class);

  /**
   * Provision as soon as possible.
   *
   * @param strategyState Provisioning state to make decisions.
   * @return Whether {@link NodeProvisioner.StrategyDecision#PROVISIONING_COMPLETED} or not.
   */
  @Nonnull
  @Override
  public NodeProvisioner.StrategyDecision apply(
      @Nonnull NodeProvisioner.StrategyState strategyState) {
    final Label label = strategyState.getLabel();
    final LoadStatisticsSnapshot snapshot = strategyState.getSnapshot();

    return getMesosCloud()
        .map(
            cloud -> {
              final int availableExecutors = snapshot.getAvailableExecutors();
              final int connectingExecutors = snapshot.getConnectingExecutors();
              final int additionalPlannedCapacity = strategyState.getAdditionalPlannedCapacity();
              int pending = 0;
              try {
                pending = cloud.getPending();
              } catch (InterruptedException | ExecutionException ex) {
                logger.error("Could not get pending instances", ex);
              }

              logger.info(
                  "Available executors={} connecting executors={} AdditionalPlannedCapacity={} pending={}",
                  availableExecutors,
                  connectingExecutors,
                  additionalPlannedCapacity,
                  pending);

              int availableCapacity =
                  availableExecutors + connectingExecutors + additionalPlannedCapacity + pending;

              final int currentDemand = snapshot.getQueueLength();

              if (availableCapacity < currentDemand) {
                final Collection<PlannedNode> plannedNodes =
                    cloud.provision(label, currentDemand - availableCapacity);
                logger.info("Planned {} new nodes", plannedNodes.size());

                strategyState.recordPendingLaunches(plannedNodes);
                availableCapacity += plannedNodes.size();
                logger.info(
                    "After provisioning, availableCapacity={}, currentDemand={}",
                    availableCapacity,
                    currentDemand);
              } else {
                logger.info(
                    "No need to provision new nodes. availableCapacity={}, currentDemand={}",
                    availableCapacity,
                    currentDemand);
              }

              if (availableCapacity >= currentDemand) {
                logger.info("Provisioning completed");
                return NodeProvisioner.StrategyDecision.PROVISIONING_COMPLETED;
              } else {
                logger.info("Provisioning not complete, consulting remaining strategies");
                return NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES;
              }
            })
        .orElseGet(
            () -> {
              logger.info("Provisioning not complete, consulting remaining strategies");
              return StrategyDecision.CONSULT_REMAINING_STRATEGIES;
            });
  }

  private static Optional<MesosCloud> getMesosCloud() {
    for (Cloud mesosCloud : Jenkins.get().clouds) {
      if (mesosCloud instanceof MesosCloud) {
        return Optional.of((MesosCloud) mesosCloud);
      }
    }
    return Optional.empty();
  }
}
