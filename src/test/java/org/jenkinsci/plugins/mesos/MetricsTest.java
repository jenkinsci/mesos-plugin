package org.jenkinsci.plugins.mesos;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

public class MetricsTest {

  @Test
  void metricsPrefixSanitization() {
    final String prefix = "I'm an invalid pref$x!.1";
    assertThat(Metrics.sanitize(prefix), is("I-m-an-invalid-pref-x-.1"));
  }
}
