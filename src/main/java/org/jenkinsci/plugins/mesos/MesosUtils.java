package org.jenkinsci.plugins.mesos;

import org.apache.commons.lang.StringUtils;
import java.util.UUID;

public final class MesosUtils {
    private static final int MAX_HOSTNAME_LENGTH = 63; // Guard against LONG hostnames - RFC-1034

    public static String buildNodeName(String label) {
        String suffix;
        if (label == null) {
            suffix = StringUtils.EMPTY;
        } else {
            suffix = StringUtils.remove("-" + label, " ");
        }
        return StringUtils.left(
            "mesos-jenkins-" +
            StringUtils.left(StringUtils.remove(UUID.randomUUID().toString(), '-'), 8) +
            suffix, MAX_HOSTNAME_LENGTH);
    }
}
