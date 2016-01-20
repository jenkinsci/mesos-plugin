package org.jenkinsci.plugins.mesos;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MesosUtilsTest {
    @Test
    public void nodeNameLengthShouldBe63OrLess() {
        assertTrue(MesosUtils.buildNodeName("mySuperLongLabel").length() <= 63);
    }

    @Test
    public void nodeNameShouldntHaveSpaces() {
        String nodeName = MesosUtils.buildNodeName("a label with some spaces");
        assertTrue(nodeName.length() <= 63);
        assertFalse(nodeName.contains(" "));
    }
}
