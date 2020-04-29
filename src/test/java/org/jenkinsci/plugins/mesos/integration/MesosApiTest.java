package org.jenkinsci.plugins.mesos.integration;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import com.mesosphere.utils.mesos.MesosClusterExtension;
import com.mesosphere.utils.zookeeper.ZookeeperServerExtension;
import hudson.model.Descriptor.FormException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.jenkinsci.plugins.mesos.MesosAgentSpecTemplate;
import org.jenkinsci.plugins.mesos.MesosApi;
import org.jenkinsci.plugins.mesos.MesosJenkinsAgent;
import org.jenkinsci.plugins.mesos.TestUtils.JenkinsParameterResolver;
import org.jenkinsci.plugins.mesos.TestUtils.JenkinsRule;
import org.jenkinsci.plugins.mesos.fixture.AgentSpecMother;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

@ExtendWith(JenkinsParameterResolver.class)
class MesosApiTest {

  @RegisterExtension static ZookeeperServerExtension zkServer = new ZookeeperServerExtension();

  static ActorSystem system = ActorSystem.create("mesos-scheduler-test");
  static ActorMaterializer materializer = ActorMaterializer.create(system);

  @RegisterExtension
  static MesosClusterExtension mesosCluster =
      MesosClusterExtension.builder()
          .withMesosMasterUrl(String.format("zk://%s/mesos", zkServer.getConnectionUrl()))
          .withLogPrefix(MesosApiTest.class.getCanonicalName())
          .build(system, materializer);

  @Test
  public void startAgent(JenkinsRule j)
      throws InterruptedException, ExecutionException, IOException, FormException,
          URISyntaxException {

    URL jenkinsUrl = j.getURL();

    String mesosUrl = mesosCluster.getMesosUrl().toString();
    MesosApi api =
        new MesosApi(
            mesosUrl,
            jenkinsUrl,
            System.getProperty("user.name"),
            "MesosTest",
            "unique",
            "*",
            Optional.empty(),
            Optional.empty());

    final String name = "jenkins-start-agent";
    final MesosAgentSpecTemplate spec = AgentSpecMother.simple;

    MesosJenkinsAgent agent = api.enqueueAgent(name, spec).toCompletableFuture().get();

    Awaitility.await().atMost(5, TimeUnit.MINUTES).until(agent::isRunning);
  }

  @Test
  public void stopAgent(JenkinsRule j) throws Exception {

    String mesosUrl = mesosCluster.getMesosUrl().toString();
    URL jenkinsUrl = j.getURL();
    MesosApi api =
        new MesosApi(
            mesosUrl,
            jenkinsUrl,
            System.getProperty("user.name"),
            "MesosTest",
            "unique",
            "*",
            Optional.empty(),
            Optional.empty());
    final String name = "jenkins-stop-agent";
    final MesosAgentSpecTemplate spec = AgentSpecMother.simple;

    MesosJenkinsAgent agent = api.enqueueAgent(name, spec).toCompletableFuture().get();
    // Poll state until we get something.
    await().atMost(5, TimeUnit.MINUTES).until(agent::isRunning);
    assertThat(agent.isRunning(), equalTo(true));

    api.killAgent(agent.getPodId());
    await().atMost(5, TimeUnit.MINUTES).until(agent::isKilled);
    assertThat(agent.isKilled(), equalTo(true));
  }

  // TODO: Move to session
  /*
  @Test
  public void testLaunchOverflow(JenkinsRule j) throws Exception {
    // Given a scheduler flow that never processes commands.
    Settings settings = Settings.load().withCommandQueueBufferSize(1);
    final CompletableFuture<StateEvent> ignore = new CompletableFuture<>();
    final Flow<SchedulerCommand, StateEvent, NotUsed> schedulerFlow =
        Flow.of(SchedulerCommand.class).mapAsync(1, command -> ignore);
    Session session = new Session(schedulerFlow) ;

    MesosApi api =
        new MesosApi(
            new URL("http://jenkins.com"),
            System.getProperty("user.name"),
            "MesosTest",
            "uniqueId",
            "*",
            session,
            null,
            null,
            settings,
            system,
            materializer);

    // And one agent is processed and one is queued.
    api.enqueueAgent("agent1", AgentSpecMother.simple);
    api.enqueueAgent("agent2", AgentSpecMother.simple);

    // When we enqueue a third agent
    CompletableFuture<MesosJenkinsAgent> result =
        api.enqueueAgent("agent3", AgentSpecMother.simple).toCompletableFuture();

    // Then backpressure hits us.
    try {
      result.get();
    } catch (ExecutionException ex) {
      assertThat(ex.getCause(), is(instanceOf(IllegalStateException.class)));
      assertThat(ex.getCause().getMessage(), is("Launch command for agent3 was dropped."));
    }
  }
  */

  // TODO: move to session
  /*
  @Test
  void testKillOverflow(JenkinsRule j) throws Exception {
    // Given a scheduler flow that never processes commands.
    Settings settings = Settings.load().withCommandQueueBufferSize(1);
    final CompletableFuture<StateEvent> ignore = new CompletableFuture<>();
    final Flow<SchedulerCommand, StateEvent, NotUsed> schedulerFlow =
        Flow.of(SchedulerCommand.class).mapAsync(1, command -> ignore);
    Session session = new Session(schedulerFlow) ;

    MesosApi api =
        new MesosApi(
            new URL("http://jenkins.com"),
            System.getProperty("user.name"),
            "MesosTest",
            "uniqueId",
            "*",
            session,
            null,
            null,
            settings,
            system,
            materializer);

    // And one agent is processed and one is queued.
    api.enqueueAgent("agent1", AgentSpecMother.simple);
    api.enqueueAgent("agent2", AgentSpecMother.simple);

    // When we enqueue a third agent
    CompletableFuture<Void> result = api.killAgent("agent3").toCompletableFuture();

    // Then backpressure hits us.
    try {
      result.get();
    } catch (ExecutionException ex) {
      assertThat(ex.getCause(), is(instanceOf(IllegalStateException.class)));
      assertThat(ex.getCause().getMessage(), is("Kill command for agent3 was dropped."));
    }
  }*/
}
