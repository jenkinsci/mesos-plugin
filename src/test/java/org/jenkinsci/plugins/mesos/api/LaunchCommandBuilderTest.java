package org.jenkinsci.plugins.mesos.api;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import hudson.security.FullControlOnceLoggedInAuthorizationStrategy;
import hudson.security.HudsonPrivateSecurityRealm;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.mesos.TestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(TestUtils.JenkinsParameterResolver.class)
public class LaunchCommandBuilderTest {

  static ActorSystem system = ActorSystem.create("mesos-scheduler-test");
  static ActorMaterializer materializer = ActorMaterializer.create(system);

  @Test
  public void testJnlpAgentCommandContainsSecret(TestUtils.JenkinsRule j) throws Exception {
    final String name = "jenkins-jnlp-security";
    LaunchCommandBuilder builder = new LaunchCommandBuilder().withName(name);

    // before enabling security shell command contains no secret param
    assertThat(builder.buildJnlpSecret(), not(containsString("-secret")));

    // This test requires a running Jenkins instances *and* at least one node.
    Jenkins instance = j.getInstance();
    if (instance == null) {
      throw new IllegalStateException("Jenkins is null");
    }
    HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false);
    instance.setSecurityRealm(realm);
    FullControlOnceLoggedInAuthorizationStrategy strategy =
        new hudson.security.FullControlOnceLoggedInAuthorizationStrategy();

    strategy.setAllowAnonymousRead(false);
    instance.setAuthorizationStrategy(strategy);
    instance.save();

    // after enabling security shell command contains secret
    assertThat(builder.buildJnlpSecret(), containsString("-secret"));
  }
}
