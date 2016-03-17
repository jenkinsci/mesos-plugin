package org.jenkinsci.plugins.mesos;

import com.codahale.metrics.health.HealthCheck;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import jenkins.metrics.api.HealthCheckProvider;

import java.util.HashMap;
import java.util.Map;

@Extension
public class MesosHealthCheckProvider extends HealthCheckProvider {
    @NonNull
    @Override
    public Map<String, HealthCheck> getHealthChecks() {
        Map<String, HealthCheck> result = new HashMap<String, HealthCheck>();
        result.put("mesos", new MesosHealthCheck());
        return result;
    }
}
    