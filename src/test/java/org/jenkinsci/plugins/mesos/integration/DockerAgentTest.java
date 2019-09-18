package org.jenkinsci.plugins.mesos.integration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import com.mesosphere.utils.mesos.MesosAgentConfig;
import com.mesosphere.utils.mesos.MesosClusterExtension;
import com.mesosphere.utils.zookeeper.ZookeeperServerExtension;
import java.util.Collections;
import java.util.List;
import org.jenkinsci.plugins.mesos.MesosAgentSpecTemplate;
import org.jenkinsci.plugins.mesos.MesosCloud;
import org.jenkinsci.plugins.mesos.MesosJenkinsAgent;
import org.jenkinsci.plugins.mesos.TestUtils;
import org.jenkinsci.plugins.mesos.fixture.AgentSpecMother;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import scala.Option;

@ExtendWith(TestUtils.JenkinsParameterResolver.class)
public class DockerAgentTest {

  @RegisterExtension static ZookeeperServerExtension zkServer = new ZookeeperServerExtension();

  static ActorSystem system = ActorSystem.create("mesos-scheduler-test");
  static ActorMaterializer materializer = ActorMaterializer.create(system);

  static MesosAgentConfig config =
      new MesosAgentConfig(
          "linux",
          "mesos",
          Option.apply("filesystem/linux,docker/runtime"),
          Option.apply("docker"));

  @RegisterExtension
  static MesosClusterExtension mesosCluster =
      MesosClusterExtension.builder()
          .withMesosMasterUrl(String.format("zk://%s/mesos", zkServer.getConnectionUrl()))
          .withLogPrefix(MesosApiTest.class.getCanonicalName())
          .withAgentConfig(config)
          .build(system, materializer);

  @Test
  @EnabledOnOs(OS.LINUX)
  public void testJenkinsAgentWithDockerImage(TestUtils.JenkinsRule j) throws Exception {
    final MesosAgentSpecTemplate spec = AgentSpecMother.docker;
    List<MesosAgentSpecTemplate> specTemplates = Collections.singletonList(spec);

    MesosCloud cloud =
        new MesosCloud(
            mesosCluster.getMesosUrl().toString(),
            "MesosTest",
            "*",
            System.getProperty("user.name"),
            j.getURL().toString(),
            specTemplates);

    final String name = "jenkins-docker-agent";

    MesosJenkinsAgent agent = (MesosJenkinsAgent) cloud.startAgent(name, spec).get();
    agent.waitUntilOnlineAsync(materializer).get();

    // verify slave is running when the future completes;
    assertThat(agent.isRunning(), is(true));
  }
}
