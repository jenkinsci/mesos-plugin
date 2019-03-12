package org.jenkinsci.plugins.mesos;

import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.EphemeralNode;
import org.apache.commons.lang.NotImplementedException;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class MesosSlave extends AbstractCloudSlave implements EphemeralNode {

    public MesosSlave() throws Descriptor.FormException, IOException {
        super(null, null, null, null, null, null, null, null, null);
        throw new NotImplementedException();
    }

    public CompletableFuture<MesosSlave> waitUntilOnlineAsync() {
        throw new NotImplementedException();
    }

    @Override
    public Node asNode() {
        return this;
    }

    @Override
    public AbstractCloudComputer createComputer() {
        return new MesosComputer(this);
    }

    @Override
    protected void _terminate(TaskListener listener)  {
        throw new NotImplementedException();
    }
}
