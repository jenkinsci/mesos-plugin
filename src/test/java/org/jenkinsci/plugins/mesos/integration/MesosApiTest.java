package org.jenkinsci.plugins.mesos.integration;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import com.mesosphere.utils.mesos.MesosClusterExtension;
import com.mesosphere.utils.zookeeper.ZookeeperServerExtension;
import hudson.model.Descriptor.FormException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Optional;
import java.util.UUID;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ExtendWith(JenkinsParameterResolver.class)
class MesosApiTest {

  private static final Logger logger = LoggerFactory.getLogger(MesosApiTest.class);

  @RegisterExtension static ZookeeperServerExtension zkServer = new ZookeeperServerExtension();

  static ActorSystem system = ActorSystem.create("mesos-scheduler-test");
  static Materializer materializer = ActorMaterializer.create(system);

  @RegisterExtension
  static MesosClusterExtension mesosCluster =
      MesosClusterExtension.builder()
          .withMesosMasterUrl(String.format("zk://%s/mesos", zkServer.getConnectionUrl()))
          .withNumMasters(3)
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
            "MesosTest-startAgent",
            UUID.randomUUID().toString(),
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
            "MesosTest-stopAgent",
            UUID.randomUUID().toString(),
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

  @Test
  public void reconnectMesos(JenkinsRule j) throws Exception {
    URL jenkinsUrl = j.getURL();
    String mesosUrl = mesosCluster.getMesosUrl().toString();

    MesosApi api =
        new MesosApi(
            mesosUrl,
            jenkinsUrl,
            System.getProperty("user.name"),
            "MesosTest-reconnect",
            UUID.randomUUID().toString(),
            "*",
            Optional.empty(),
            Optional.empty());

    // Given a running agent
    final MesosAgentSpecTemplate spec = AgentSpecMother.simple;
    MesosJenkinsAgent agent1 =
        api.enqueueAgent("agent-before-failover", spec).toCompletableFuture().get();
    // Poll state until we get something.
    await().atMost(5, TimeUnit.MINUTES).until(agent1::isRunning);
    assertThat(agent1.isRunning(), equalTo(true));

    // When Mesos has a failover.
    mesosCluster.mesosCluster().failover();

    // Then we can start a new agent
    MesosJenkinsAgent agent2 =
        api.enqueueAgent("agent-after-failover", spec).toCompletableFuture().get();
    // Poll state until we get something.
    await().atMost(5, TimeUnit.MINUTES).until(agent2::isRunning);
    assertThat(agent2.isRunning(), equalTo(true));
  }
}
