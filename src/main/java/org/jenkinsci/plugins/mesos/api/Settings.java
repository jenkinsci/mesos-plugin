package org.jenkinsci.plugins.mesos.api;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.time.Duration;

/**
 * Operation settings for the Jenkins plugin. These should not be set by Jenkins admins and users
 * but rather by Jenkins operators.
 */
public class Settings {

  private final Duration agentTimeout;
  private final int commandQueueBufferSize;
  private final Duration failoverTimeout;

  /** Internal constructor */
  private Settings(Duration agentTimeout, int commandQueueBufferSize, Duration failoverTimeout) {
    this.agentTimeout = agentTimeout;
    this.commandQueueBufferSize = commandQueueBufferSize;
    this.failoverTimeout = failoverTimeout;
  }

  /** @return copy of these settings with overridden command queue buffer size. */
  public Settings withCommandQueueBufferSize(int commandQueueBufferSize) {
    return new Settings(this.agentTimeout, commandQueueBufferSize, this.failoverTimeout);
  }

  /** @return copy of these settings with overridden agent timeout. */
  public Settings withAgentTimeout(Duration agentTimeout) {
    return new Settings(agentTimeout, this.commandQueueBufferSize, this.failoverTimeout);
  }

  /** @return copy of these settings with overridden failover timeout. */
  public Settings withFailoverTimeout(Duration failoverTimeout) {
    return new Settings(this.agentTimeout, this.commandQueueBufferSize, failoverTimeout);
  }

  /** @return agent timeout setting. */
  public Duration getAgentTimeout() {
    return this.agentTimeout;
  }

  /** @return command queue buffer size setting. */
  public int getCommandQueueBufferSize() {
    return this.commandQueueBufferSize;
  }

  /** @return failover timeout setting. */
  public Duration getFailoverTimeout() {
    return this.failoverTimeout;
  }

  /**
   * Factory method to construct {@link Settings} from a Lightbend {@link Config}.
   *
   * @param conf The Lightbend config object.
   * @return a new settings instances.
   */
  public static Settings fromConfig(Config conf) {
    return new Settings(
        conf.getDuration("agent-timeout"),
        conf.getInt("command-queue-buffer-size"),
        conf.getDuration("failover-timeout"));
  }

  /**
   * Factory method to construct {@link Settings} with Lightbend {@link ConfigFactory}.
   *
   * @param loader The class loader used to find application.conf and reference.conf.
   * @return a new settings instance loaded from "usi.jenkins" in resources.
   */
  public static Settings load(ClassLoader loader) {
    Config conf = ConfigFactory.load(loader).getConfig("usi.jenkins");
    return fromConfig(conf);
  }

  /** @return a new settings instance loaded from "usi.jenkins" in resources. */
  public static Settings load() {
    Config conf = ConfigFactory.load().getConfig("usi.jenkins");
    return fromConfig(conf);
  }
}
