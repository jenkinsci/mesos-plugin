package org.jenkinsci.plugins.mesos.api;

import com.mesosphere.usi.core.models.*;
import com.mesosphere.usi.core.models.resources.ScalarRequirement;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import scala.Option;
import scala.collection.JavaConverters;
import scala.collection.Seq;

/**
 * A simpler factory for building {@link com.mesosphere.usi.core.models.LaunchPod} for Jenkins
 * agents.
 */
public class LaunchCommandBuilder {

  public LaunchCommandBuilder() {}

  private static final String AGENT_JAR_URI_SUFFIX = "jnlpJars/agent.jar";

  // We allocate extra memory for the JVM
  private static final int JVM_XMX = 32;

  private static final String AGENT_COMMAND_FORMAT =
      "java -DHUDSON_HOME=jenkins -server -Xmx%dm %s -jar ${MESOS_SANDBOX-.}/agent.jar %s %s -jnlpUrl %s";

  private PodId id = null;
  private ScalarRequirement cpus = null;
  private ScalarRequirement memory = null;
  private ScalarRequirement disk = null;
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
  public LaunchCommandBuilder withName(String name) {
    this.id = new PodId(name);
    return this;
  }

  public LaunchCommandBuilder withCpu(Double cpus) {
    this.cpus = ScalarRequirement.cpus(cpus);
    return this;
  }

  /**
   * Sets the maximum memory pool for the JVM aka Xmx. Please note that the Mesos task will have
   * {@link LaunchCommandBuilder#JVM_XMX} more memory allocated.
   *
   * @param memory Memory in megabyte.
   * @return the pod spec builder.
   */
  public LaunchCommandBuilder withMemory(int memory) {
    this.memory = ScalarRequirement.memory(memory + JVM_XMX);
    this.xmx = JVM_XMX;
    return this;
  }

  public LaunchCommandBuilder withDisk(Double disk) {
    this.disk = ScalarRequirement.disk(disk);
    return this;
  }

  public LaunchCommandBuilder withJenkinsUrl(URL url) {
    this.jenkinsMaster = url;
    return this;
  }

  public LaunchCommandBuilder withRole(String role) {
    this.role = role;
    return this;
  }

  public LaunchPod build() throws MalformedURLException, URISyntaxException {
    final RunTemplate runTemplate =
        new RunTemplate(
            convertListToSeq(Arrays.asList(this.cpus, this.memory, this.disk)),
            this.buildCommand(),
            this.role,
            convertListToSeq(Arrays.asList(buildFetchUri())));
    return new LaunchPod(this.id, runTemplate);
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
    final URI uri = new URL(this.jenkinsMaster, AGENT_JAR_URI_SUFFIX).toURI();
    return new FetchUri(uri, false, false, false, Option.empty());
  }

  private <T> Seq<T> convertListToSeq(List<T> inputList) {
    return JavaConverters.asScalaIteratorConverter(inputList.iterator()).asScala().toSeq();
  }
}
