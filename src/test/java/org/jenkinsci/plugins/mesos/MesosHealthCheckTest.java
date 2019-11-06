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

import java.util.Collections;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Created by lucas_cimon on 06/06/2018.
 */
@RunWith(PowerMockRunner.class) // needed to mock Mesos.class static methods
@PrepareForTest(Mesos.class)
public class MesosHealthCheckTest {

    @Rule
    JenkinsRule j = new JenkinsRule(); // provides Jenkins.getInstance() required by getAdministrativeMonitor() in MesosHealthCheck

    @Test
    public void healthcheckHealthy() throws Exception {
        final MesosHealthCheck healthcheck = new MesosHealthCheck();
        assertEquals(HealthCheck.Result.healthy(), healthcheck.check());
    }

    @Test
    public void unhealthcheckHealthy() throws Exception {
        mockMesosUnmatchedLabels(Sets.newHashSet("foo", "bar"));
        final MesosHealthCheck healthcheck = new MesosHealthCheck();
        assertFalse(healthcheck.check().isHealthy());
    }

    static void mockMesosUnmatchedLabels(Set<String> labels) {
        final JenkinsScheduler jenkinsScheduler = mock(JenkinsScheduler.class);
        final Mesos mesos = mock(Mesos.class);
        when(jenkinsScheduler.getUnmatchedLabels()).thenReturn(labels);
        when(mesos.getScheduler()).thenReturn(jenkinsScheduler);
        // Mocking static method Mesos.getAllClouds():
        PowerMockito.mockStatic(Mesos.class);
        BDDMockito.given(Mesos.getAllClouds()).willReturn(Collections.singletonList(mesos));
    }
}
