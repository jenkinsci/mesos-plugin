package org.jenkinsci.plugins.mesos.integration;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import com.mesosphere.utils.mesos.MesosClusterExtension;
import com.mesosphere.utils.zookeeper.ZookeeperServerExtension;
import hudson.model.Slave;
import hudson.slaves.SlaveComputer;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.*;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.mesos.*;
import org.jenkinsci.plugins.mesos.fixture.AgentSpecMother;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

@ExtendWith(TestUtils.JenkinsParameterResolver.class)
public class MesosJenkinsAgentLifecycleTest {

  @RegisterExtension static ZookeeperServerExtension zkServer = new ZookeeperServerExtension();

  static ActorSystem system = ActorSystem.create("mesos-scheduler-test");
  static ActorMaterializer materializer = ActorMaterializer.create(system);

  @RegisterExtension
  static MesosClusterExtension mesosCluster =
      MesosClusterExtension.builder()
          .withMesosMasterUrl(String.format("zk://%s/mesos", zkServer.getConnectionUrl()))
          .withLogPrefix(MesosJenkinsAgentLifecycleTest.class.getCanonicalName())
          .build(system, materializer);

  @Test
  public void testAgentLifecycle(TestUtils.JenkinsRule j) throws Exception {
    MesosCloud cloud =
        new MesosCloud(
            mesosCluster.getMesosUrl().toString(),
            "MesosTest",
            "*",
            System.getProperty("user.name"),
            j.getURL().toString(),
            Collections.emptyList());

    final String name = "jenkins-lifecycle";
    final MesosAgentSpecTemplate spec = AgentSpecMother.simple;
    MesosJenkinsAgent agent = (MesosJenkinsAgent) cloud.startAgent(name, spec).get();
    agent.waitUntilOnlineAsync(materializer).get();

    // verify slave is running when the future completes;
    assertThat(agent.isRunning(), is(true));

    assertThat(Jenkins.getInstanceOrNull().getNodes(), hasSize(1));

    agent.terminate();
    await().atMost(10, TimeUnit.SECONDS).until(agent::isKilled);
    assertThat(agent.isKilled(), is(true));

    assertThat(Jenkins.getInstanceOrNull().getNodes(), hasSize(0));
  }

  @Test
  public void testComputerNodeTermination(TestUtils.JenkinsRule j) throws Exception {
    MesosCloud cloud =
        new MesosCloud(
            mesosCluster.getMesosUrl().toString(),
            "MesosTest",
            "*",
            System.getProperty("user.name"),
            j.getURL().toString(),
            Collections.emptyList());

    final String name = "jenkins-node-terminate";
    final MesosAgentSpecTemplate spec = AgentSpecMother.simple;

    MesosJenkinsAgent agent = (MesosJenkinsAgent) cloud.startAgent(name, spec).get();
    agent.waitUntilOnlineAsync(materializer).get();

    assertThat(agent.isRunning(), is(true));
    assertThat(agent.getComputer().isOnline(), is(true));

    SlaveComputer computer = agent.getComputer();
    assert (computer != null);
    Slave node = computer.getNode();
    assert (node != null);
    MesosJenkinsAgent shouldBeParent = (MesosJenkinsAgent) node;
    shouldBeParent.terminate();
    await().atMost(10, TimeUnit.SECONDS).until(agent::isKilled);
    assertThat(agent.isKilled(), is(true));
  }

  @Test
  public void testComputerNodeDeletion(TestUtils.JenkinsRule j) throws Exception {
    MesosCloud cloud =
        new MesosCloud(
            mesosCluster.getMesosUrl().toString(),
            "MesosTest",
            "*",
            System.getProperty("user.name"),
            j.getURL().toString(),
            Collections.emptyList());

    final String name = "jenkins-node-delete";
    final MesosAgentSpecTemplate spec = AgentSpecMother.simple;

    MesosJenkinsAgent agent = (MesosJenkinsAgent) cloud.startAgent(name, spec).get();
    agent.waitUntilOnlineAsync(materializer).get();

    assertThat(agent.isRunning(), is(true));
    assertThat(agent.getComputer().isOnline(), is(true));

    SlaveComputer computer = agent.getComputer();
    assert (computer != null);
    computer.doDoDelete();

    await().atMost(10, TimeUnit.SECONDS).until(agent::isKilled);
    assertThat(agent.isKilled(), is(true));
  }

  @Test
  public void testRetentionStrategy(TestUtils.JenkinsRule j) throws Exception {
    MesosCloud cloud =
        new MesosCloud(
            mesosCluster.getMesosUrl().toString(),
            "MesosTest",
            "*",
            System.getProperty("user.name"),
            j.getURL().toString(),
            Collections.emptyList());

    final String name = "jenkins-node-delete";
    final MesosAgentSpecTemplate spec = AgentSpecMother.simple;

    MesosJenkinsAgent agent = (MesosJenkinsAgent) cloud.startAgent(name, spec).get();
    agent.waitUntilOnlineAsync(materializer).get();

    assertThat(agent.isRunning(), is(true));

    SlaveComputer computer = agent.getComputer();
    assert (computer != null);
    assertThat(computer.isOnline(), is(true));
    assertThat(computer.isIdle(), is(true));

    // after 1 minute MesosRetentionStrategy will kill the task
    await().atMost(3, TimeUnit.MINUTES).until(agent::isKilled);
  }

  @Test
  public void testAgentTimeout(TestUtils.JenkinsRule j) throws Exception {
    MesosCloud cloud =
        new MesosCloud(
            mesosCluster.getMesosUrl().toString(),
            "MesosTest",
            "*",
            System.getProperty("user.name"),
            j.getURL().toString(),
            Collections.emptyList());

    cloud.getMesosApi().setAgentTimeout(Duration.ofSeconds(1));

    final String name = "jenkins-agent-timeout";

    final MesosAgentSpecTemplate spec = AgentSpecMother.simple;

    ExecutionException e =
        assertThrows(
            ExecutionException.class,
            () -> {
              cloud.startAgent(name, spec).get();
            });

    assertThat(
        e.getCause().getMessage(),
        is(
            "java.util.concurrent.TimeoutException: The stream has not been completed in 1 second."));
  }
}
