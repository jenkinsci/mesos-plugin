package org.jenkinsci.plugins.mesos.integration;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import com.mesosphere.utils.mesos.MesosClusterExtension;
import com.mesosphere.utils.zookeeper.ZookeeperServerExtension;
import hudson.model.labels.LabelAtom;
import java.util.concurrent.TimeUnit;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.mesos.MesosCloud;
import org.jenkinsci.plugins.mesos.MesosSlave;
import org.jenkinsci.plugins.mesos.TestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

@ExtendWith(TestUtils.JenkinsParameterResolver.class)
public class MesosSlaveLifecycleTest {

  @RegisterExtension static ZookeeperServerExtension zkServer = new ZookeeperServerExtension();

  static ActorSystem system = ActorSystem.create("mesos-scheduler-test");
  static ActorMaterializer materializer = ActorMaterializer.create(system);

  @RegisterExtension
  static MesosClusterExtension mesosCluster =
      MesosClusterExtension.builder()
          .withMesosMasterUrl(String.format("zk://%s/mesos", zkServer.getConnectionUrl()))
          .withLogPrefix(MesosSlaveLifecycleTest.class.getCanonicalName())
          .build(system, materializer);

  @Test
  public void testAgentLifecycle(TestUtils.JenkinsRule j) throws Exception {
    LabelAtom label = new LabelAtom("label");
    MesosCloud cloud = new MesosCloud("mesos", mesosCluster.getMesosUrl(), j.getURL().toString());

    MesosSlave agent = (MesosSlave) cloud.startAgent().get();
    agent.waitUntilOnlineAsync().get();

    // verify slave is running when the future completes;
    assertThat(agent.isRunning(), is(true));

    assertThat(Jenkins.getInstanceOrNull().getNodes(), hasSize(1));

    agent.terminate();
    await().atMost(5, TimeUnit.MINUTES).until(agent::isKilled);
    assertThat(agent.isKilled(), is(true));

    assertThat(Jenkins.getInstanceOrNull().getNodes(), hasSize(0));
  }
}
