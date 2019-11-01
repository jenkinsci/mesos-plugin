package org.jenkinsci.plugins.mesos;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import hudson.util.FormValidation.Kind;
import org.jenkinsci.plugins.mesos.MesosAgentSpecTemplate.DescriptorImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestUtils.JenkinsParameterResolver.class)
public class MesosAgentSpecTemplateDescriptorTest {

  @Test
  public void validateCpus(TestUtils.JenkinsRule j) {
    MesosAgentSpecTemplate.DescriptorImpl descriptor = new DescriptorImpl();
    assertThat(descriptor.doCheckCpus("0.0").kind, is(Kind.ERROR));
    assertThat(descriptor.doCheckCpus("0").kind, is(Kind.ERROR));
    assertThat(descriptor.doCheckCpus("-0.1").kind, is(Kind.ERROR));
    assertThat(descriptor.doCheckCpus("0.1").kind, is(Kind.OK));
  }
}
