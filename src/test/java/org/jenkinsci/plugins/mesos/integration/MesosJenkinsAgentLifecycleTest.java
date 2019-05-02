package org.jenkinsci.plugins.mesos.integration;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import com.mesosphere.utils.mesos.MesosClusterExtension;
import com.mesosphere.utils.zookeeper.ZookeeperServerExtension;
import hudson.model.Node.Mode;
import hudson.model.labels.LabelAtom;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.mesos.MesosAgentSpecTemplate;
import org.jenkinsci.plugins.mesos.MesosCloud;
import org.jenkinsci.plugins.mesos.MesosJenkinsAgent;
import org.jenkinsci.plugins.mesos.TestUtils;
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
    String mesosUrl = mesosCluster.getMesosUrl();
    MesosCloud cloud =
        new MesosCloud(
            mesosUrl,
            "MesosTest",
            "*",
            System.getProperty("user.name"),
            j.getURL().toString(),
            new ArrayList<>());

    final String name = "jenkins-lifecycle";
    final String idleMin = "1";
    LabelAtom label = new LabelAtom("label");
    final MesosAgentSpecTemplate spec =
        new MesosAgentSpecTemplate(
            label.toString(),
            Mode.EXCLUSIVE,
            "0.1",
            "32",
            idleMin,
            true,
            "1",
            "1",
            "0",
            "0",
            "",
            "",
            "",
            "",
            "",
            "",
            "");
    MesosJenkinsAgent agent = (MesosJenkinsAgent) cloud.startAgent(name, spec).get();
    agent.waitUntilOnlineAsync().get();

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
            mesosCluster.getMesosUrl(),
            "MesosTest",
            "*",
            System.getProperty("user.name"),
            j.getURL().toString(),
            new ArrayList<>());

    final String name = "jenkins-node-terminate";
    final String idleMin = "1";
    LabelAtom label = new LabelAtom("label");
    final MesosAgentSpecTemplate spec =
        new MesosAgentSpecTemplate(
            label.toString(),
            Mode.EXCLUSIVE,
            "0.1",
            "32",
            idleMin,
            true,
            "1",
            "1",
            "0",
            "0",
            "",
            "",
            "",
            "",
            "",
            "",
            "");

    MesosJenkinsAgent agent = (MesosJenkinsAgent) cloud.startAgent(name, spec).get();
    agent.waitUntilOnlineAsync().get();

    assertThat(agent.isRunning(), is(true));
    assertThat(agent.getComputer().isOnline(), is(true));

    MesosJenkinsAgent shouldBeParent = (MesosJenkinsAgent) agent.getComputer().getNode();
    shouldBeParent.terminate();
    await().atMost(10, TimeUnit.SECONDS).until(agent::isKilled);
    assertThat(agent.isKilled(), is(true));
  }

  @Test
  public void testComputerNodeDeletion(TestUtils.JenkinsRule j) throws Exception {
    MesosCloud cloud =
        new MesosCloud(
            mesosCluster.getMesosUrl(),
            "MesosTest",
            "*",
            System.getProperty("user.name"),
            j.getURL().toString(),
            new ArrayList<>());

    final String name = "jenkins-node-delete";
    final String idleMin = "1";
    LabelAtom label = new LabelAtom("label");
    final MesosAgentSpecTemplate spec =
        new MesosAgentSpecTemplate(
            label.toString(),
            Mode.EXCLUSIVE,
            "0.1",
            "32",
            idleMin,
            true,
            "1",
            "1",
            "0",
            "0",
            "",
            "",
            "",
            "",
            "",
            "",
            "");

    MesosJenkinsAgent agent = (MesosJenkinsAgent) cloud.startAgent(name, spec).get();
    agent.waitUntilOnlineAsync().get();

    assertThat(agent.isRunning(), is(true));
    assertThat(agent.getComputer().isOnline(), is(true));

    agent.getComputer().doDoDelete();

    await().atMost(10, TimeUnit.SECONDS).until(agent::isKilled);
    assertThat(agent.isKilled(), is(true));
  }

  @Test
  public void testRetentionStrategy(TestUtils.JenkinsRule j) throws Exception {
    MesosCloud cloud =
        new MesosCloud(
            mesosCluster.getMesosUrl(),
            "MesosTest",
            "*",
            System.getProperty("user.name"),
            j.getURL().toString(),
            new ArrayList<>());

    final String name = "jenkins-node-delete";
    final String idleMin = "1";
    LabelAtom label = new LabelAtom("label");
    final MesosAgentSpecTemplate spec =
        new MesosAgentSpecTemplate(
            label.toString(),
            Mode.EXCLUSIVE,
            "0.1",
            "32",
            idleMin,
            true,
            "1",
            "1",
            "0",
            "0",
            "",
            "",
            "",
            "",
            "",
            "",
            null);

    MesosJenkinsAgent agent = (MesosJenkinsAgent) cloud.startAgent(name, spec).get();
    agent.waitUntilOnlineAsync().get();

    assertThat(agent.isRunning(), is(true));
    assertThat(agent.getComputer().isOnline(), is(true));
    assertThat(agent.getComputer().isIdle(), is(true));

    // after 1 minute MesosRetentionStrategy will kill the task
    await().atMost(3, TimeUnit.MINUTES).until(agent::isKilled);
  }
}
