package org.jenkinsci.plugins.mesos;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.junit.jupiter.api.Test;

public class MesosSlaveInfoTest {

  @Test
  void migrateSingleAgentAttribute() {
    MesosSlaveInfo info = new MesosSlaveInfo();
    final String oldAttributes = "{\"os\":\"linux\"}";

    final String result = info.migrateAttributeFilter(info.parseSlaveAttributes(oldAttributes));

    assertThat(result, is(equalTo("os:linux")));
  }

  @Test
  void migrateMultipleAgentAttributes() {
    MesosSlaveInfo info = new MesosSlaveInfo();
    final String oldAttributes = "{\"os\":\"linux\", \"foo\":\"bar\"}";

    final String result = info.migrateAttributeFilter(info.parseSlaveAttributes(oldAttributes));

    assertThat(result, is(equalTo("os:linux,foo:bar")));
  }
}
