package org.jenkinsci.plugins.mesos;

import org.apache.commons.lang.StringUtils;
import java.util.UUID;

public final class MesosUtils {
    private static final int MAX_HOSTNAME_LENGTH = 63; // Guard against LONG hostnames - RFC-1034
    /** The buildNodeName may contain the jenkins label, and is subsequently
     * used to name the mesos task and docker hostname.
     * A mesos task cannot contain "/"
     * A docker hostname may not contain ":" or "/"
     * Even if jenkins can accept those characters for the build node name,
     * we clean it up here for consistency
     * https://github.com/jenkinsci/mesos-plugin/issues/251
     */
    private static final String REPLACE_INVALID_CHARS = "[^a-zA-Z0-9-]"; // RFC 952

    public static String buildNodeName(String label) {
        String suffix;
        if (label == null) {
            suffix = StringUtils.EMPTY;
        } else {
            suffix = "-" + label.replaceAll(REPLACE_INVALID_CHARS, "-");
        }
        return StringUtils.left("mesos-jenkins-" + StringUtils.remove(UUID.randomUUID().toString(), '-') + suffix, MAX_HOSTNAME_LENGTH);
    }
}
