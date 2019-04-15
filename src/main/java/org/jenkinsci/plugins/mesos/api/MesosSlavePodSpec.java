package org.jenkinsci.plugins.mesos.api;

import com.mesosphere.usi.core.models.FetchUri;
import com.mesosphere.usi.core.models.Goal.Running$;
import com.mesosphere.usi.core.models.PodId;
import com.mesosphere.usi.core.models.PodSpec;
import com.mesosphere.usi.core.models.RunSpec;
import com.mesosphere.usi.core.models.resources.ScalarRequirement;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.List;
import scala.Option;
import scala.collection.JavaConverters;
import scala.collection.Seq;

public class MesosSlavePodSpec {

  private MesosSlavePodSpec() {}

  public static Builder builder() {
    return new Builder();
  }

  /**
   * A simpler factory for building {@link com.mesosphere.usi.core.models.PodSpec} for Jenkins
   * agents.
   */
  public static class Builder {

    private static final String AGENT_JAR_URI_SUFFIX = "jnlpJars/agent.jar";

    // We allocate extra memory for the JVM
    private static final int JVM_XMX = 32;

    private static final String AGENT_COMMAND_FORMAT =
        "java -DHUDSON_HOME=jenkins -server -Xmx%dm %s -jar ${MESOS_SANDBOX-.}/agent.jar %s %s -jnlpUrl %s";

    private PodId id = null;
    private ScalarRequirement cpus = null;
    private ScalarRequirement memory = null;
    private String role = "test";

    private int xmx = 0;

    private String jvmArgString = "";
    private String jnlpArgString = "";

    private URL jenkinsMaster = null;

    /**
     * Sets the name of the Mesos task.
     *
     * @param name Unique name of Mesos task.
     * @return this pod spec builder.
     */
    public Builder withName(String name) {
      this.id = new PodId(name);
      return this;
    }

    public Builder withCpu(Double cpus) {
      this.cpus = ScalarRequirement.cpus(cpus);
      return this;
    }

    /**
     * Sets the maximum memory pool for the JVM aka Xmx. Please note that the Mesos task will have
     * {@link Builder#JVM_MEM_OVERHEAD_FACTOR} more memory allocated.
     *
     * @param memory Memory in megabyte.
     * @return the pod spec builder.
     */
    public Builder withMemory(int memory) {
      this.memory = ScalarRequirement.memory(memory + JVM_XMX);
      this.xmx = JVM_XMX;
      return this;
    }

    public Builder withJenkinsUrl(URL url) {
      this.jenkinsMaster = url;
      return this;
    }

    public Builder withRole(String role) {
      this.role = role;
      return this;
    }

    public PodSpec build() throws MalformedURLException, URISyntaxException {
      final var runSpec =
          new RunSpec(
              convertListToSeq(List.of(this.cpus, this.memory)),
              this.buildCommand(),
              this.role,
              convertListToSeq(List.of(buildFetchUri())));
      return new PodSpec(this.id, Running$.MODULE$, runSpec);
    }

    /** @return the agent shell command for the Mesos task. */
    private String buildCommand() throws MalformedURLException {
      return String.format(
          AGENT_COMMAND_FORMAT,
          this.xmx,
          this.jvmArgString,
          this.jnlpArgString,
          buildJnlpSecret(),
          buildJnlpUrl());
    }

    private String buildJnlpSecret() {
      return ""; // TODO
      // https://github.com/mesosphere/mesos-plugin/blob/master/src/main/java/org/jenkinsci/plugins/mesos/JenkinsScheduler.java#L232
    }

    /** @return the Jnlp url for the agent: http://[master]/computer/[slaveName]/slave-agent.jnlp */
    private URL buildJnlpUrl() throws MalformedURLException {
      final String path = Paths.get("computer", this.id.value(), "slave-agent.jnlp").toString();
      return new URL(this.jenkinsMaster, path);
    }

    /** @return the {@link FetchUri} for the Jenkins agent jar file. */
    private FetchUri buildFetchUri() throws MalformedURLException, URISyntaxException {
      final var uri = new URL(this.jenkinsMaster, AGENT_JAR_URI_SUFFIX).toURI();
      return new FetchUri(uri, false, false, false, Option.empty());
    }

    private <T> Seq<T> convertListToSeq(List<T> inputList) {
      return JavaConverters.asScalaIteratorConverter(inputList.iterator()).asScala().toSeq();
    }
  }
}
