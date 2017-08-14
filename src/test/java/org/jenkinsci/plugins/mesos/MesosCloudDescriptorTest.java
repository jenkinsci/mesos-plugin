package org.jenkinsci.plugins.mesos;

import org.junit.Test;

import hudson.util.FormValidation;

import static org.assertj.core.api.Assertions.assertThat;

public class MesosCloudDescriptorTest {
  @Test
  public void tunnelCanBeNull() throws Exception {
    FormValidation formValidation = new MesosCloud.DescriptorImpl()
        .doCheckTunnel(null);

    assertThat(formValidation.kind).isEqualByComparingTo(FormValidation.Kind.OK);
  }

  @Test
  public void tunnelCanBeBlank() throws Exception {
    FormValidation formValidation = new MesosCloud.DescriptorImpl()
        .doCheckTunnel("");

    assertThat(formValidation.kind).isEqualByComparingTo(FormValidation.Kind.OK);
  }

  @Test
  public void tunnelMustHaveColonSeparator() throws Exception {
    FormValidation formValidation = new MesosCloud.DescriptorImpl()
        .doCheckTunnel("127.0.0.1,3333");

    assertThat(formValidation.kind).isEqualByComparingTo(FormValidation.Kind.ERROR);
  }

  @Test
  public void tunnelMustHaveValidPort() throws Exception {
    FormValidation formValidation = new MesosCloud.DescriptorImpl()
        .doCheckTunnel("127.0.0.1:99999");

    assertThat(formValidation.kind).isEqualByComparingTo(FormValidation.Kind.ERROR);
  }

  @Test
  public void tunnelMustHavePort() throws Exception {
    FormValidation formValidation = new MesosCloud.DescriptorImpl()
        .doCheckTunnel("127.0.0.1");

    assertThat(formValidation.kind).isEqualByComparingTo(FormValidation.Kind.ERROR);
  }

  @Test
  public void validTunnelString() throws Exception {
    FormValidation formValidation = new MesosCloud.DescriptorImpl()
        .doCheckTunnel("127.0.0.1:8080");

    assertThat(formValidation.kind).isEqualByComparingTo(FormValidation.Kind.OK);
  }
}
