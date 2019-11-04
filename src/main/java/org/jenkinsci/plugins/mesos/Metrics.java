package org.jenkinsci.plugins.mesos;

import com.mesosphere.usi.metrics.dropwizard.conf.HistorgramSettings;
import com.mesosphere.usi.metrics.dropwizard.conf.MetricsSettings;
import java.util.HashMap;
import javax.annotation.Nonnull;
import scala.Option;

public class Metrics {

  @Nonnull
  private static HashMap<String, com.mesosphere.usi.metrics.Metrics> metrics = new HashMap<>();

  /**
   * The USI metrics is a singleton per framework name.
   *
   * <p>A singleton is required because we use in in validation code in {@link MesosCloud} and in
   * {@link MesosApi} instances.
   *
   * @param frameworkName The name of the framework that is used as a prefix.
   * @return The Metrics implementation for the framework.
   */
  public static synchronized com.mesosphere.usi.metrics.Metrics getInstance(String frameworkName) {
    final String prefix = sanitize(frameworkName);
    if (!metrics.containsKey(prefix)) {
      // Construct metrics
      MetricsSettings metricsSettings =
          new MetricsSettings(
              sanitize(frameworkName),
              HistorgramSettings.apply(
                  HistorgramSettings.apply$default$1(),
                  HistorgramSettings.apply$default$2(),
                  HistorgramSettings.apply$default$3(),
                  HistorgramSettings.apply$default$4(),
                  HistorgramSettings.apply$default$5()),
              Option.empty(),
              Option.empty());
      metrics.put(
          prefix,
          new com.mesosphere.usi.metrics.dropwizard.DropwizardMetrics(
              metricsSettings, jenkins.metrics.api.Metrics.metricRegistry()));
    }

    return metrics.get(prefix);
  }

  /** @return a santized prefix for Dropwizard metrics. */
  public static String sanitize(String prefix) {
    return prefix.replaceAll("[^a-zA-Z0-9\\-\\.]", "-");
  }
}
