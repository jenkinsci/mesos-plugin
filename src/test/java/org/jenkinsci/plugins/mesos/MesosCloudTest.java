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
}
