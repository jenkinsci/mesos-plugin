package org.jenkinsci.plugins.mesos;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import hudson.util.FormValidation.Kind;
import java.io.IOException;
import org.jenkinsci.plugins.mesos.MesosCloud.DescriptorImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestUtils.JenkinsParameterResolver.class)
public class MesosCloudDescriptorTest {

  @Test
  void validateMesosMasterUrl(TestUtils.JenkinsRule j) {
    MesosCloud.DescriptorImpl descriptor = new DescriptorImpl();
    assertThat(descriptor.doCheckMesosMasterUrl("http/other").kind, is(Kind.ERROR));
    assertThat(descriptor.doCheckMesosMasterUrl("zk://localhost:5050").kind, is(Kind.ERROR));
    assertThat(descriptor.doCheckMesosMasterUrl("http://localhost:5050").kind, is(Kind.OK));
    assertThat(descriptor.doCheckMesosMasterUrl("https://localhost:5050").kind, is(Kind.OK));
  }

  @Test
  void validateFrameworkName(TestUtils.JenkinsRule j) {
    MesosCloud.DescriptorImpl descriptor = new DescriptorImpl();
    assertThat(descriptor.doCheckFrameworkName("").kind, is(Kind.ERROR));
    assertThat(descriptor.doCheckFrameworkName("something").kind, is(Kind.OK));
  }

  @Test
  void validateRole(TestUtils.JenkinsRule j) {
    MesosCloud.DescriptorImpl descriptor = new DescriptorImpl();
    assertThat(descriptor.doCheckRole("").kind, is(Kind.ERROR));
    assertThat(descriptor.doCheckRole("-something").kind, is(Kind.ERROR));
    assertThat(descriptor.doCheckRole(".").kind, is(Kind.ERROR));
    assertThat(descriptor.doCheckRole("..").kind, is(Kind.ERROR));
    assertThat(descriptor.doCheckRole(" ").kind, is(Kind.ERROR));
    assertThat(descriptor.doCheckRole("/something").kind, is(Kind.ERROR));
    assertThat(descriptor.doCheckRole("some/thing").kind, is(Kind.ERROR));
    assertThat(descriptor.doCheckRole("\\something").kind, is(Kind.ERROR));
    assertThat(descriptor.doCheckRole("some\\thing").kind, is(Kind.ERROR));
    assertThat(descriptor.doCheckRole("something").kind, is(Kind.OK));
    assertThat(descriptor.doCheckRole("some.thing.").kind, is(Kind.OK));
    assertThat(descriptor.doCheckRole("some-thing").kind, is(Kind.OK));
    assertThat(descriptor.doCheckRole("*").kind, is(Kind.OK));
  }

  @Test
  void validateAgentUser(TestUtils.JenkinsRule j) {
    MesosCloud.DescriptorImpl descriptor = new DescriptorImpl();
    assertThat(descriptor.doCheckAgentUser("").kind, is(Kind.ERROR));
    assertThat(descriptor.doCheckAgentUser("Invalid").kind, is(Kind.ERROR));
    assertThat(descriptor.doCheckAgentUser("Inval$d").kind, is(Kind.ERROR));
    assertThat(descriptor.doCheckAgentUser("something").kind, is(Kind.OK));
  }

  @Test
  void validateJenkinsUrl(TestUtils.JenkinsRule j) {
    MesosCloud.DescriptorImpl descriptor = new DescriptorImpl();
    assertThat(descriptor.doCheckJenkinsUrl("http/other").kind, is(Kind.ERROR));
    assertThat(descriptor.doCheckJenkinsUrl("zk://localhost:5050").kind, is(Kind.ERROR));
    assertThat(descriptor.doCheckJenkinsUrl("http://localhost:5050").kind, is(Kind.OK));
    assertThat(descriptor.doCheckJenkinsUrl("https://localhost:5050").kind, is(Kind.OK));
  }

  @Test
  void connectionTest(TestUtils.JenkinsRule j) throws IOException {
    MesosCloud.DescriptorImpl descriptor = new DescriptorImpl();
    assertThat(descriptor.doTestConnection("invalid url").kind, is(Kind.ERROR));
    assertThat(descriptor.doTestConnection("http://unknownhost.foo").kind, is(Kind.ERROR));
    assertThat(descriptor.doTestConnection(j.getURL().toString()).kind, is(Kind.OK));
  }
}
