package org.jenkinsci.plugins.mesos;

import com.codahale.metrics.health.HealthCheck;
import com.google.common.collect.Sets;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.BDDMockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.util.Map;

import static org.jenkinsci.plugins.mesos.MesosHealthCheckTest.mockMesosUnmatchedLabels;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Created by lucas_cimon on 06/06/2018.
 */
@RunWith(PowerMockRunner.class) // needed to mock Mesos.class & MesosCloud.class static methods
@PrepareForTest({Mesos.class, MesosCloud.class})
public class MesosHealthCheckProviderTest {

    @Rule
    JenkinsRule j = new JenkinsRule();

    MesosCloud mesosCloud = mockGlobalMesosCloud();

    @Test
    public void mesosHealthcheckPresentWhenHealthy() {
        when(mesosCloud.getEnableMetricsHealthcheck()).thenReturn(true);
        final Map<String, HealthCheck> healthchecks = getMesosHealthCheckProvider().getHealthChecks();
        assertTrue(healthchecks.containsKey("mesos"));
    }

    @Test
    public void mesosHealthcheckPresentWhenUnhealthy() {
        when(mesosCloud.getEnableMetricsHealthcheck()).thenReturn(true);
        mockMesosUnmatchedLabels(Sets.newHashSet("foo", "bar"));
        final Map<String, HealthCheck> healthchecks = getMesosHealthCheckProvider().getHealthChecks();
        assertTrue(healthchecks.containsKey("mesos"));
    }

    @Test
    public void mesosHealthcheckDisabledWhenUnhealthy() {
        when(mesosCloud.getEnableMetricsHealthcheck()).thenReturn(false);
        mockMesosUnmatchedLabels(Sets.newHashSet("foo", "bar"));
        final Map<String, HealthCheck> healthchecks = getMesosHealthCheckProvider().getHealthChecks();
        assertFalse(healthchecks.containsKey("mesos"));
    }

    private MesosHealthCheckProvider getMesosHealthCheckProvider() {
        return j.jenkins.getExtensionList(MesosHealthCheckProvider.class).get(0);
    }

    static MesosCloud mockGlobalMesosCloud() {
        final MesosCloud mesosCloud = mock(MesosCloud.class);
        // Mocking static method MesosCloud.get():
        PowerMockito.mockStatic(MesosCloud.class);
        BDDMockito.given(MesosCloud.get()).willReturn(mesosCloud);
        return mesosCloud;
    }
}
