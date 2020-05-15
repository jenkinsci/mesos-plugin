package org.jenkinsci.plugins.mesos.api;

import akka.Done;
import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.stream.ActorMaterializer;
import akka.stream.OverflowStrategy;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.RestartFlow;
import akka.stream.javadsl.RestartSource;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.javadsl.SourceQueueWithComplete;
import com.mesosphere.mesos.client.CredentialsProvider;
import com.mesosphere.mesos.client.MesosClient;
import com.mesosphere.mesos.client.MesosClient$;
import com.mesosphere.mesos.conf.MesosClientSettings;
import com.mesosphere.usi.core.SchedulerFactory;
import com.mesosphere.usi.core.conf.SchedulerSettings;
import com.mesosphere.usi.core.japi.Scheduler;
import com.mesosphere.usi.core.models.StateEvent;
import com.mesosphere.usi.core.models.StateEventOrSnapshot;
import com.mesosphere.usi.core.models.commands.SchedulerCommand;
import com.mesosphere.usi.repository.PodRecordRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import org.apache.mesos.v1.Protos;
import org.apache.mesos.v1.Protos.FrameworkInfo;
import org.jenkinsci.plugins.mesos.MesosApi;
import org.jenkinsci.plugins.mesos.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.compat.java8.OptionConverters;
import scala.concurrent.ExecutionContext;

/**
 * A representation of the connection to Mesos.
 *
 * <p>This class should only be used by {@link org.jenkinsci.plugins.mesos.MesosApi}.
 */
public class Session {

  private static final Logger logger = LoggerFactory.getLogger(Session.class);

  // Interface to USI.
  @Nonnull private final SourceQueueWithComplete<SchedulerCommand> commands;

  public static Session create(
      FrameworkInfo frameworkInfo,
      MesosClientSettings clientSettings,
      Optional<CredentialsProvider> provider,
      SchedulerSettings schedulerSettings,
      PodRecordRepository repository,
      Settings operationalSettings,
      Consumer<StateEventOrSnapshot> eventHandler,
      BiFunction<Done, Throwable, Done> terminationHandler,
      ExecutionContext context,
      ActorSystem system,
      ActorMaterializer materializer) {
    Flow<SchedulerCommand, StateEvent, NotUsed> schedulerFlow =
        RestartFlow.withBackoff(
            Duration.ofSeconds(3),
            Duration.ofSeconds(30),
            0.2,
            20,
            () -> {
              return connectClient(
                      frameworkInfo,
                      operationalSettings,
                      clientSettings,
                      provider,
                      system,
                      materializer)
                  .thenCompose(
                      client -> {
                        final SchedulerFactory schedulerFactory =
                            SchedulerFactory.create(
                                client,
                                repository,
                                schedulerSettings,
                                Metrics.getInstance(frameworkInfo.getName()),
                                context);
                        return Scheduler.asFlow(schedulerFactory);
                      })
                  .get() // TODO: avoid blocking.
                  .getFlow();
            });

    Pair<SourceQueueWithComplete<SchedulerCommand>, CompletionStage<Done>> pair =
        runScheduler(operationalSettings, schedulerFlow, eventHandler, materializer);

    // TODO: handle termination
    // pair.second().handle()

    return new Session(pair.first());
  }

  public Session(SourceQueueWithComplete<SchedulerCommand> commands) {
    this.commands = commands;
  }

  /** Establish a connection to Mesos via the v1 client. */
  private static CompletableFuture<MesosClient> connectClient(
      Protos.FrameworkInfo frameworkInfo,
      Settings operationalSettings,
      MesosClientSettings clientSettings,
      Optional<CredentialsProvider> authorization,
      ActorSystem system,
      ActorMaterializer materializer) {

    return RestartSource.onFailuresWithBackoff(
            operationalSettings.getConnectionMinBackoff(),
            operationalSettings.getConnectionMaxBackoff(),
            0.2,
            operationalSettings.getConnectionRetries(),
            () ->
                MesosClient$.MODULE$
                    .apply(
                        clientSettings,
                        frameworkInfo,
                        OptionConverters.toScala(authorization),
                        system,
                        materializer)
                    .asJava())
        .runWith(Sink.head(), materializer)
        .toCompletableFuture();
  }

  /**
   * Constructs a queue of {@link SchedulerCommand}. All state events are processed by {@link
   * MesosApi#updateState(StateEventOrSnapshot)}.
   *
   * @param schedulerFlow The scheduler flow from commands to events provided by USI.
   * @param materializer The {@link ActorMaterializer} used for the source queue.
   * @return A running source queue.
   */
  public static Pair<SourceQueueWithComplete<SchedulerCommand>, CompletionStage<Done>> runScheduler(
      Settings operationalSettings,
      Flow<SchedulerCommand, StateEvent, NotUsed> schedulerFlow,
      Consumer<StateEventOrSnapshot> eventHandler,
      ActorMaterializer materializer) {
    return Source.<SchedulerCommand>queue(
            operationalSettings.getCommandQueueBufferSize(), OverflowStrategy.dropNew())
        .via(schedulerFlow)
        .toMat(Sink.foreach(eventHandler::accept), Keep.both())
        .run(materializer);
  }

  public SourceQueueWithComplete<SchedulerCommand> getCommands() {
    return this.commands;
  }
}
