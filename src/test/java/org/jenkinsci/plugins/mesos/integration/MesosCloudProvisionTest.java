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
import hudson.slaves.NodeProvisioner;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.mesos.MesosCloud;
import org.jenkinsci.plugins.mesos.MesosSlave;
import org.jenkinsci.plugins.mesos.TestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

@ExtendWith(TestUtils.JenkinsParameterResolver.class)
public class MesosCloudProvisionTest {

  @RegisterExtension static ZookeeperServerExtension zkServer = new ZookeeperServerExtension();

  static ActorSystem system = ActorSystem.create("mesos-scheduler-test");
  static ActorMaterializer materializer = ActorMaterializer.create(system);

  @RegisterExtension
  static MesosClusterExtension mesosCluster =
      MesosClusterExtension.builder()
          .withMesosMasterUrl(String.format("zk://%s/mesos", zkServer.getConnectionUrl()))
          .withLogPrefix(MesosCloudProvisionTest.class.getCanonicalName())
          .build(system, materializer);

  @Test
  public void testJekinsProvision(TestUtils.JenkinsRule j) throws Exception {
    LabelAtom label = new LabelAtom("label");

    MesosCloud cloud = new MesosCloud("mesos", mesosCluster.getMesosUrl(), j.getURL().toString());

    int workload = 3;
    Collection<NodeProvisioner.PlannedNode> plannedNodes = cloud.provision(label, workload);

    assertThat(plannedNodes, hasSize(workload));
    for (NodeProvisioner.PlannedNode node : plannedNodes) {
      // resolve all plannedNodes
      MesosSlave agent = (MesosSlave) node.future.get();

      // ensure all plannedNodes are now running
      assertThat(agent.isRunning(), is(true));
    }

    // check that jenkins knows about all the plannedNodes
    assertThat(Jenkins.getInstanceOrNull().getNodes(), hasSize(workload));
  }

  @Test
  public void testStartAgent(TestUtils.JenkinsRule j) throws Exception {
    LabelAtom label = new LabelAtom("label");
    MesosCloud cloud = new MesosCloud("mesos", mesosCluster.getMesosUrl(), j.getURL().toString());

    MesosSlave agent = (MesosSlave) cloud.startAgent().get();

    await().atMost(5, TimeUnit.MINUTES).until(agent::isRunning);

    assertThat(agent.isRunning(), is(true));
    assertThat(agent.toComputer().isOnline(), is(true));

    // assert jenkins has the 1 added nodes
    assertThat(Jenkins.getInstanceOrNull().getNodes(), hasSize(1));
  }
}
