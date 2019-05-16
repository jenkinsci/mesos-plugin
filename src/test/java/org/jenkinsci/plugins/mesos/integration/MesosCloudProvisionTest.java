package org.jenkinsci.plugins.mesos.integration;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import com.mesosphere.utils.mesos.MesosClusterExtension;
import com.mesosphere.utils.zookeeper.ZookeeperServerExtension;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Node.Mode;
import hudson.model.labels.LabelAtom;
import hudson.slaves.NodeProvisioner;
import hudson.tasks.Builder;
import hudson.tasks.Shell;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import jenkins.model.Jenkins;
import okhttp3.Response;
import org.jenkinsci.plugins.mesos.JenkinsConfigClient;
import org.jenkinsci.plugins.mesos.MesosAgentSpecTemplate;
import org.jenkinsci.plugins.mesos.MesosCloud;
import org.jenkinsci.plugins.mesos.MesosJenkinsAgent;
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
  public void testJenkinsProvision(TestUtils.JenkinsRule j) throws Exception {
    LabelAtom label = new LabelAtom("label");
    final String idleMin = "1";
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
    List<MesosAgentSpecTemplate> specTemplates = Collections.singletonList(spec);

    MesosCloud cloud =
        new MesosCloud(
            mesosCluster.getMesosUrl().toString(),
            "MesosTest",
            "*",
            System.getProperty("user.name"),
            j.getURL().toString(),
            specTemplates);

    int workload = 3;
    Collection<NodeProvisioner.PlannedNode> plannedNodes = cloud.provision(label, workload);

    assertThat(plannedNodes, hasSize(workload));
    for (NodeProvisioner.PlannedNode node : plannedNodes) {
      // resolve all plannedNodes
      MesosJenkinsAgent agent = (MesosJenkinsAgent) node.future.get();

      // ensure all plannedNodes are now running
      assertThat(agent.isRunning(), is(true));
      assertThat(agent.getComputer().isOnline(), is(true));
    }

    // check that jenkins knows about all the plannedNodes
    assertThat(Jenkins.getInstanceOrNull().getNodes(), hasSize(workload));
  }

  @Test
  public void testStartAgent(TestUtils.JenkinsRule j) throws Exception {
    final String name = "jenkins-agent";
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

    List<MesosAgentSpecTemplate> specTemplates = Collections.singletonList(spec);
    MesosCloud cloud =
        new MesosCloud(
            mesosCluster.getMesosUrl().toString(),
            "MesosTest",
            "*",
            System.getProperty("user.name"),
            j.getURL().toString(),
            specTemplates);

    MesosJenkinsAgent agent = (MesosJenkinsAgent) cloud.startAgent(name, spec).get();

    await().atMost(10, TimeUnit.SECONDS).until(agent::isRunning);

    assertThat(agent.isRunning(), is(true));
    assertThat(agent.isOnline(), is(true));

    // assert jenkins has the 1 added nodes
    assertThat(Jenkins.getInstanceOrNull().getNodes(), hasSize(1));
  }

  @Test
  public void runSimpleBuild(TestUtils.JenkinsRule j) throws Exception {

    // Given: a configured Mesos Cloud.
    final String label = "mesos";
    final JenkinsConfigClient jenkinsClient = new JenkinsConfigClient(j.createWebClient());
    final Response response =
        jenkinsClient.addMesosCloud(
            mesosCluster.getMesosUrl().toString(),
            "Jenkins Scheduler",
            "*",
            System.getProperty("user.name"),
            j.getURL().toURI().resolve("jenkins").toString(),
            label,
            "EXCLUSIVE");
    assertThat(response.code(), is(lessThan(400)));

    // And: a project with a simple build command.
    FreeStyleProject project = j.createFreeStyleProject("mesos-test");
    final Builder step = new Shell("echo Hello");
    project.getBuildersList().add(step);
    project.setAssignedLabel(new LabelAtom(label));

    // When: we run a build
    FreeStyleBuild build = j.buildAndAssertSuccess(project);

    // Then it finishes successfully and the logs contain our command.
    assertThat(j.getLog(build), containsString("echo Hello"));
  }
}
