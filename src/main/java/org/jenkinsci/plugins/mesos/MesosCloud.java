package org.jenkinsci.plugins.mesos;

import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.AbstractCloudImpl;
import hudson.slaves.NodeProvisioner;
import org.apache.commons.lang.NotImplementedException;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Jenkins Cloud implementation for Mesos.
 *
 * The layout is inspired by the Nomad Plugin.
 *
 * @see https://github.com/jenkinsci/nomad-plugin
 */
class MesosCloud extends AbstractCloudImpl {

    private MesosApi mesos;

    @DataBoundConstructor
    public MesosCloud(
            String name) {
        super(name, null);
        throw new NotImplementedException();
    }

    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(Label label, int excessWorkload) {
        List<NodeProvisioner.PlannedNode> nodes = new ArrayList<>();

        String slaveName = "undefined";
        while (excessWorkload > 0) {
            nodes.add(new NodeProvisioner.PlannedNode(slaveName, startAgent(), 1));
            excessWorkload--;
        }

        return nodes;
    }

    private CompletableFuture<Node> startAgent() {
        mesos.startAgent().thenCompose(mesosSlave -> mesosSlave.waitUntilOnlineAsync());
        throw new NotImplementedException();
    }

    @Override
    public boolean canProvision(Label label) {
        throw new NotImplementedException();
    }
}