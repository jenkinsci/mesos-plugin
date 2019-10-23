package org.jenkinsci.plugins.mesos.api;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.mesosphere.usi.core.models.PodId;
import com.mesosphere.usi.core.models.commands.LaunchPod;
import com.mesosphere.usi.core.models.resources.ScalarRequirement;
import com.mesosphere.usi.core.models.template.FetchUri;
import com.mesosphere.usi.core.models.template.RunTemplate;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import jenkins.model.Jenkins;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.jenkinsci.plugins.mesos.MesosAgentSpecTemplate.ContainerInfo;
import scala.Option;

/**
 * A simpler factory for building {@link com.mesosphere.usi.core.models.commands.LaunchPod} for
 * Jenkins agents.
 */
public class LaunchCommandBuilder {

  public LaunchCommandBuilder() {}

  private static final String AGENT_JAR_URI_SUFFIX = "jnlpJars/agent.jar";

  // We allocate extra memory for the JVM
  private static final int JVM_XMX = 32;

  private static final String AGENT_COMMAND_FORMAT =
      "java -DHUDSON_HOME=jenkins -server -Xmx%dm %s -jar ${MESOS_SANDBOX-.}/agent.jar %s %s -jnlpUrl %s";

  private static final String JNLP_SECRET_FORMAT = "-secret %s";

  private PodId id = null;
  private ScalarRequirement cpus = null;
  private ScalarRequirement memory = null;
  private ScalarRequirement disk = null;
  private String role = "test";
  private List<FetchUri> additionalFetchUris = Collections.emptyList();
  private Optional<ContainerInfo> containerInfo = Optional.empty();

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

  public LaunchCommandBuilder withContainerInfo(Optional<ContainerInfo> containerInfo) {
    this.containerInfo = containerInfo;
    return this;
  }

  public LaunchCommandBuilder withAdditionalFetchUris(List<FetchUri> additionalFetchUris) {

    this.additionalFetchUris = additionalFetchUris;
    return this;
  }

  public LaunchCommandBuilder withJnlpArguments(String args) {
    this.jnlpArgString = args;
    return this;
  }

  public LaunchPod build() throws MalformedURLException, URISyntaxException {
    final RunTemplate runTemplate =
        RunTemplateFactory.newRunTemplate(
            this.id.value(),
            Arrays.asList(this.cpus, this.memory, this.disk),
            this.buildCommand(),
            this.role,
            this.buildFetchUris(),
            this.containerInfo);
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

  @VisibleForTesting
  String buildJnlpSecret() {
    String jnlpSecret = "";
    if (getJenkins().isUseSecurity()) {
      jnlpSecret =
          String.format(
              JNLP_SECRET_FORMAT,
              jenkins.slaves.JnlpSlaveAgentProtocol.SLAVE_SECRET.mac(this.id.value()));
    }
    return jnlpSecret;
  }

  @NonNull
  private static Jenkins getJenkins() {
    Jenkins jenkins = Jenkins.getInstanceOrNull();
    if (jenkins == null) {
      throw new IllegalStateException("Jenkins is null");
    }
    return jenkins;
  }

  /** @return the Jnlp url for the agent: http://[master]/computer/[slaveName]/slave-agent.jnlp */
  private URL buildJnlpUrl() throws MalformedURLException {
    final String path = Paths.get("computer", this.id.value(), "slave-agent.jnlp").toString();
    return new URL(this.jenkinsMaster, path);
  }

  /** @return the {@link FetchUri} for the Jenkins agent jar file. */
  private List<FetchUri> buildFetchUris() throws MalformedURLException, URISyntaxException {
    final URI uri = new URL(this.jenkinsMaster, AGENT_JAR_URI_SUFFIX).toURI();
    final FetchUri jenkinsAgentFetchUri = new FetchUri(uri, false, false, false, Option.empty());

    return ImmutableList.<FetchUri>builder()
        .addAll(this.additionalFetchUris)
        .add(jenkinsAgentFetchUri)
        .build();
  }
}
