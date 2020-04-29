package org.jenkinsci.plugins.mesos.api;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.QueueOfferResult;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.SourceQueueWithComplete;
import com.mesosphere.usi.core.models.PodId;
import com.mesosphere.usi.core.models.StateEvent;
import com.mesosphere.usi.core.models.commands.KillPod;
import com.mesosphere.usi.core.models.commands.SchedulerCommand;
import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.jenkinsci.plugins.mesos.TestUtils;
import org.jenkinsci.plugins.mesos.TestUtils.JenkinsParameterResolver;
import org.jenkinsci.plugins.mesos.fixture.AgentSpecMother;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ExtendWith(JenkinsParameterResolver.class)
public class SessionTest {

  private static final Logger logger = LoggerFactory.getLogger(SessionTest.class);

  static ActorSystem system = ActorSystem.create("mesos-scheduler-test");
  static ActorMaterializer materializer = ActorMaterializer.create(system);

  @Test
  public void testLaunchOverflow(TestUtils.JenkinsRule j) throws Exception {
    // Given a scheduler flow that never processes commands.
    final URL jenkinsUrl = new URL("https://jenkins.com");
    Settings settings = Settings.load().withCommandQueueBufferSize(1);
    final CompletableFuture<StateEvent> ignore = new CompletableFuture<>();
    final Flow<SchedulerCommand, StateEvent, NotUsed> schedulerFlow =
        Flow.of(SchedulerCommand.class).mapAsync(1, command -> ignore);

    // And a running session.
    SourceQueueWithComplete<SchedulerCommand> sourceQueue =
        Session.runScheduler(
                settings,
                schedulerFlow,
                event -> logger.debug("Received event {}", event),
                materializer)
            .first();
    Session session = new Session(sourceQueue);

    // And one agent is processed and one is queued.
    session
        .getCommands()
        .offer(AgentSpecMother.simple.buildLaunchCommand(jenkinsUrl, "agent1", "*"));
    session
        .getCommands()
        .offer(AgentSpecMother.simple.buildLaunchCommand(jenkinsUrl, "agent2", "*"));

    // When we enqueue a third agent
    CompletionStage<QueueOfferResult> result =
        session
            .getCommands()
            .offer(AgentSpecMother.simple.buildLaunchCommand(jenkinsUrl, "agent3", "*"));

    // Then backpressure hits us.
    QueueOfferResult offerFeedback = result.toCompletableFuture().get();
    assertThat(offerFeedback, is(QueueOfferResult.dropped()));
  }

  @Test
  void testKillOverflow(TestUtils.JenkinsRule j) throws Exception {
    // Given a scheduler flow that never processes commands.
    Settings settings = Settings.load().withCommandQueueBufferSize(1);
    final CompletableFuture<StateEvent> ignore = new CompletableFuture<>();
    final Flow<SchedulerCommand, StateEvent, NotUsed> schedulerFlow =
        Flow.of(SchedulerCommand.class).mapAsync(1, command -> ignore);

    // And a running session.
    SourceQueueWithComplete<SchedulerCommand> sourceQueue =
        Session.runScheduler(
                settings,
                schedulerFlow,
                event -> logger.debug("Received event {}", event),
                materializer)
            .first();
    Session session = new Session(sourceQueue);

    // And one agent is processed and one is queued.
    session.getCommands().offer(new KillPod(new PodId("agent1")));
    session.getCommands().offer(new KillPod(new PodId("agent2")));

    // When we kill a third agent
    CompletionStage<QueueOfferResult> result =
        session.getCommands().offer(new KillPod(new PodId("agent3")));

    // Then backpressure hits us.
    QueueOfferResult offerFeedback = result.toCompletableFuture().get();
    assertThat(offerFeedback, is(QueueOfferResult.dropped()));
  }
}
