package org.jenkinsci.plugins.mesos;

import org.apache.commons.lang.NotImplementedException;

import java.util.concurrent.CompletableFuture;

public class MesosApi {

    public CompletableFuture<MesosSlave> startAgent() {
        throw new NotImplementedException();
    }

}
