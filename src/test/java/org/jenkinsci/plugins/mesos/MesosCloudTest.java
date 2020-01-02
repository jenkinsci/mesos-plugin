package org.jenkinsci.plugins.mesos;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import com.mesosphere.utils.mesos.MesosClusterExtension;
import com.mesosphere.utils.zookeeper.ZookeeperServerExtension;
import hudson.util.XStream2;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import org.apache.commons.io.IOUtils;
import org.jenkinsci.plugins.mesos.integration.MesosCloudProvisionTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

@ExtendWith(TestUtils.JenkinsParameterResolver.class)
public class MesosCloudTest {
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
  void deserializeOldConfig(TestUtils.JenkinsRule j) throws IOException {
    final String oldConfig =
        IOUtils.resourceToString(
                "config_1.x.xml",
                StandardCharsets.UTF_8,
                Thread.currentThread().getContextClassLoader())
            // Master URL resolution requires a separate test.
            .replaceAll(
                "<master>.*</master>",
                String.format("<master>%s</master>", mesosCluster.getMesosUrl()));

    final XStream2 xstream = new XStream2();
    MesosCloud cloud = (MesosCloud) xstream.fromXML(oldConfig);

    assertThat(cloud.getMesosMasterUrl(), is(equalTo(mesosCluster.getMesosUrl().toString())));
    assertThat(cloud.getMesosAgentSpecTemplates(), hasSize(39));
    cloud
        .getMesosAgentSpecTemplates()
        .forEach(
            template -> {
              assertThat(template.getCpu(), is(notNullValue()));
            });
  }

  @Test
  void serializationRoundTrip(TestUtils.JenkinsRule j)
      throws IOException, InterruptedException, ExecutionException {
    final MesosCloud cloud =
        new MesosCloud(
            mesosCluster.getMesosUrl().toString(),
            "jenkins-framework",
            null,
            "*",
            "root",
            j.getURL().toString(),
            Collections.emptyList());

    final XStream2 xstream = new XStream2();
    final MesosCloud reloadedCloud = (MesosCloud) xstream.fromXML(xstream.toXML(cloud));

    assertThat(reloadedCloud.getMesosMasterUrl(), is(equalTo(cloud.getMesosMasterUrl())));
    assertThat(reloadedCloud.getFrameworkName(), is(equalTo(cloud.getFrameworkName())));
    assertThat(reloadedCloud.getFrameworkId(), is(equalTo(cloud.getFrameworkId())));
    assertThat(reloadedCloud.getRole(), is(equalTo(cloud.getRole())));
    assertThat(reloadedCloud.getAgentUser(), is(equalTo(cloud.getAgentUser())));
  }
}
