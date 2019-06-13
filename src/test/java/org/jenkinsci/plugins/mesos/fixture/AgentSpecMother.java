package org.jenkinsci.plugins.mesos.fixture;

import hudson.model.Node.Mode;
import org.jenkinsci.plugins.mesos.MesosAgentSpecTemplate;

/**
 * A Mother object for {@link org.jenkinsci.plugins.mesos.MesosAgentSpecTemplate}.
 *
 * @see <a href="https://martinfowler.com/bliki/ObjectMother.html">ObjectMother</a>
 */
public class AgentSpecMother {

  public static final MesosAgentSpecTemplate simple =
      new MesosAgentSpecTemplate(
          "label",
          Mode.EXCLUSIVE,
          "0.1",
          "32",
          "1",
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
          "",
          "");

  public static final MesosAgentSpecTemplate docker =
      new MesosAgentSpecTemplate(
          "label",
          Mode.EXCLUSIVE,
          "0.1",
          "32",
          "1",
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
          "",
          "mesosphere/jenkins-dind:0.6.0-alpine");
}
