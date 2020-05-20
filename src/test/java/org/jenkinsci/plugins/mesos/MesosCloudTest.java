package org.jenkinsci.plugins.mesos;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import hudson.util.XStream2;
import io.jenkins.plugins.casc.ConfigurationAsCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestUtils.JenkinsParameterResolver.class)
public class MesosCloudTest {

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
                String.format("<master>%s</master>", "http://localhost:5050"));

    final XStream2 xstream = new XStream2();
    MesosCloud cloud = (MesosCloud) xstream.fromXML(oldConfig);

    assertThat(cloud.getMesosMasterUrl(), is("http://localhost:5050"));
    assertThat(cloud.getMesosAgentSpecTemplates(), hasSize(39));
    cloud
        .getMesosAgentSpecTemplates()
        .forEach(
            template -> {
              assertThat(template.getCpus(), is(notNullValue()));
            });
  }

  @Test
  void serializationRoundTrip(TestUtils.JenkinsRule j)
      throws IOException, InterruptedException, ExecutionException {
    final MesosCloud cloud =
        new MesosCloud(
            "http://localhost:5050",
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

  @Test
  void configureAsCode(TestUtils.JenkinsRule j) throws IOException {
    final String config =
        IOUtils.resourceToURL("configuration.yaml", this.getClass().getClassLoader())
            .toExternalForm();
    ConfigurationAsCode.get().configure(config);

    assertThat(j.jenkins.clouds.getAll(MesosCloud.class), hasSize(1));
  }
}
