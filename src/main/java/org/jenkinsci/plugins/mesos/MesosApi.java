package org.jenkinsci.plugins.mesos;

import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Sink;
import com.mesosphere.mesos.client.MesosClient;
import com.mesosphere.mesos.client.MesosClient$;
import com.mesosphere.mesos.conf.MesosClientSettings;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigValueFactory;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.apache.commons.lang.NotImplementedException;
import org.apache.mesos.v1.Protos;

public class MesosApi {

  private final String slavesUser;
  private final String frameworkName;
  private final Protos.FrameworkID frameworkId;
  private final MesosClientSettings clientSettings;
  private final MesosClient client;

  private final ActorSystem system;
  private final ActorMaterializer materializer;

  /**
   * Establishes a connection to Mesos and provides a simple interface to start and stop {@link
   * MesosSlave} instances.
   *
   * @param masterUrl The Mesos master address to connect to.
   * @param user The username used for executing Mesos tasks.
   * @param frameworkName The name of the framework the Mesos client should register as.
   * @throws InterruptedException
   * @throws ExecutionException
   */
  public MesosApi(String masterUrl, String user, String frameworkName)
      throws InterruptedException, ExecutionException {
    this.frameworkName = frameworkName;
    this.frameworkId =
        Protos.FrameworkID.newBuilder().setValue(UUID.randomUUID().toString()).build();
    this.slavesUser = user;

    Config conf =
        ConfigFactory.load()
            .getConfig("mesos-client")
            .withValue("master-url", ConfigValueFactory.fromAnyRef(masterUrl));
    this.clientSettings = MesosClientSettings.fromConfig(conf);
    system = ActorSystem.create("mesos-scheduler");
    materializer = ActorMaterializer.create(system);

    client = connectClient().get();
  }

  /**
   * Start a Jekins slave/agent on Mesos.
   *
   * @return A future reference to the {@link MesosSlave}.
   */
  public CompletableFuture<MesosSlave> startAgent() {
    throw new NotImplementedException();
  }

  /** Establish a connection to Mesos via the v1 client. */
  private CompletableFuture<MesosClient> connectClient() {
    Protos.FrameworkInfo frameworkInfo =
        Protos.FrameworkInfo.newBuilder()
            .setUser(slavesUser)
            .setName(frameworkName)
            .setId(frameworkId)
            .addRoles("test")
            .addCapabilities(
                Protos.FrameworkInfo.Capability.newBuilder()
                    .setType(Protos.FrameworkInfo.Capability.Type.MULTI_ROLE))
            .setFailoverTimeout(0d)
            .build();

    return MesosClient$.MODULE$
        .apply(clientSettings, frameworkInfo, system, materializer)
        .runWith(Sink.head(), materializer)
        .toCompletableFuture();
  }
}
