package org.jenkinsci.plugins.mesos;

import hudson.model.Descriptor;
import hudson.model.Node;
import jenkins.model.Jenkins;
import org.apache.mesos.Protos;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;


@RunWith(PowerMockRunner.class)
@PrepareForTest( { Jenkins.class })
public class JenkinsSchedulerTest {
    @Mock
    private MesosCloud mesosCloud;

    private JenkinsScheduler jenkinsScheduler;

    private static int    TEST_JENKINS_SLAVE_MEM   = 512;
    private static String TEST_JENKINS_SLAVE_ARG   = "-Xms16m -XX:+UseConcMarkSweepGC -Djava.net.preferIPv4Stack=true";
    private static String TEST_JENKINS_JNLP_ARG    = "";
    private static String TEST_JENKINS_SLAVE_NAME  = "testSlave1";


    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mesosCloud.getMaster()).thenReturn("Mesos Cloud Master");

        // Simulate basic Jenkins env
        Jenkins jenkins = Mockito.mock(Jenkins.class);
        when(jenkins.isUseSecurity()).thenReturn(false);
        PowerMockito.mockStatic(Jenkins.class);
        Mockito.when(Jenkins.getInstance()).thenReturn(jenkins);

        jenkinsScheduler = new JenkinsScheduler("jenkinsMaster", mesosCloud);

    }

    @Test
    public void testFindPortsToUse() {
        Protos.Offer offer = createOfferWithVariableRanges(31000, 32000);

        List<Integer> portsToUse = jenkinsScheduler.findPortsToUse(offer, 1);

        assertEquals(1, portsToUse.size());
        assertEquals(Integer.valueOf(31000), portsToUse.get(0));
    }

    @Test
    public void testFindPortsToUseSamePortNumber() {
        final ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                Protos.Offer offer = createOfferWithVariableRanges(31000, 31000);

                List<Integer> portsToUse = jenkinsScheduler.findPortsToUse(offer, 1);

                assertEquals(1, portsToUse.size());
                assertEquals(Integer.valueOf(31000), portsToUse.get(0));
            }
        });

        executorService.shutdown();

        // Test that there is no infinite loop by asserting that the task finishes in 500ms or less
        try {
            assertTrue(executorService.awaitTermination(500L, TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    public void testSingleFirstRangeLongRangeAfterNoInfiniteLoop() {
        Protos.Value.Range range = Protos.Value.Range.newBuilder()
                .setBegin(31000)
                .setEnd(31000)
                .build();

        Protos.Value.Range range2 = Protos.Value.Range.newBuilder()
                .setBegin(31005)
                .setEnd(32000)
                .build();

        Protos.Value.Ranges ranges = Protos.Value.Ranges.newBuilder()
                .addRange(range)
                .addRange(range2)
                .build();

        Protos.Resource resource = Protos.Resource.newBuilder()
                .setName("ports")
                .setRanges(ranges)
                .setType(Protos.Value.Type.RANGES)
                .build();

        final Protos.Offer protoOffer = Protos.Offer.newBuilder()
                .addResources(resource)
                .setId(Protos.OfferID.newBuilder().setValue("value").build())
                .setFrameworkId(Protos.FrameworkID.newBuilder().setValue("value").build())
                .setSlaveId(Protos.SlaveID.newBuilder().setValue("value").build())
                .setHostname("hostname")
                .build();

        final ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                List<Integer> portsToUse = jenkinsScheduler.findPortsToUse(protoOffer, 2);

                assertEquals(2, portsToUse.size());
                assertEquals(Integer.valueOf(31000), portsToUse.get(0));
                assertEquals(Integer.valueOf(31005), portsToUse.get(1));
            }
        });

        executorService.shutdown();

        // Test that there is no infinite loop by asserting that the task finishes in 500ms or less
        try {
            assertTrue(executorService.awaitTermination(999999500L, TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    public void testConstructMesosCommandInfoWithNoContainer() throws Exception {
        JenkinsScheduler.Request request = mockMesosRequest(Boolean.FALSE, null, null);

        Protos.CommandInfo.Builder commandInfoBuilder = jenkinsScheduler.getCommandInfoBuilder(request);
        Protos.CommandInfo commandInfo = commandInfoBuilder.build();

        assertTrue("Default shell config (true) should be configured when no container specified", commandInfo.getShell());

        String jenkinsCommand2Run = jenkinsScheduler.generateJenkinsCommand2Run(
                TEST_JENKINS_SLAVE_MEM,
                TEST_JENKINS_SLAVE_ARG,
                TEST_JENKINS_JNLP_ARG,
                TEST_JENKINS_SLAVE_NAME,
                null);
        assertEquals("jenkins command to run should be specified as value", jenkinsCommand2Run, commandInfo.getValue());
        assertEquals("mesos command should have no args specified by default", 0, commandInfo.getArgumentsCount());
    }

    @Test
    public void testConstructMesosCommandInfoWithDefaultDockerShell() throws Exception {
        JenkinsScheduler.Request request = mockMesosRequest(Boolean.TRUE,false,null);

        Protos.CommandInfo.Builder commandInfoBuilder = jenkinsScheduler.getCommandInfoBuilder(request);
        Protos.CommandInfo commandInfo = commandInfoBuilder.build();

        assertTrue("Default shell config (true) should be configured when no container specified", commandInfo.getShell());
        String jenkinsCommand2Run = jenkinsScheduler.generateJenkinsCommand2Run(
                TEST_JENKINS_SLAVE_MEM,
                TEST_JENKINS_SLAVE_ARG,
                TEST_JENKINS_JNLP_ARG,
                TEST_JENKINS_SLAVE_NAME,
                null);
        assertEquals("jenkins command to run should be specified as value", jenkinsCommand2Run, commandInfo.getValue());
        assertEquals("mesos command should have no args specified by default", 0, commandInfo.getArgumentsCount());
    }

    @Test
    public void testConstructMesosCommandInfoWithCustomDockerShell() throws Exception {
        JenkinsScheduler.Request request = mockMesosRequest(Boolean.TRUE, true, "/bin/wrapdocker");

        Protos.CommandInfo.Builder commandInfoBuilder = jenkinsScheduler.getCommandInfoBuilder(request);
        Protos.CommandInfo commandInfo = commandInfoBuilder.build();

        assertFalse("shell should be configured as false when using a custom shell", commandInfo.getShell());
        assertEquals("Custom shell should be specified as value", "/bin/wrapdocker", commandInfo.getValue());
        String jenkinsCommand2Run = jenkinsScheduler.generateJenkinsCommand2Run(
                TEST_JENKINS_SLAVE_MEM,
                TEST_JENKINS_SLAVE_ARG,
                TEST_JENKINS_JNLP_ARG,
                TEST_JENKINS_SLAVE_NAME,
                null);

        assertEquals("args should now consist of the single original command ", 1, commandInfo.getArgumentsCount());
        assertEquals("args should now consist of the original command ", jenkinsCommand2Run, commandInfo.getArguments(0));
    }

    @Test(expected = java.lang.IllegalArgumentException.class)
    public void testConstructMesosCommandInfoWithBlankCustomDockerShell() throws Exception {
        JenkinsScheduler.Request request = mockMesosRequest(Boolean.TRUE, true, " ");

        jenkinsScheduler.getCommandInfoBuilder(request);
    }

    @Test(expected = java.lang.IllegalArgumentException.class)
    public void testConstructMesosCommandInfoWithNullCustomDockerShell() throws Exception {
        JenkinsScheduler.Request request = mockMesosRequest(Boolean.TRUE, true, null);

        jenkinsScheduler.getCommandInfoBuilder(request);
    }

    private JenkinsScheduler.Request mockMesosRequest(
            Boolean useDocker,
            Boolean useCustomDockerCommandShell,
            String customDockerCommandShell) throws Descriptor.FormException {

        MesosSlaveInfo.ContainerInfo containerInfo = null;
        if (useDocker) {
            containerInfo = new MesosSlaveInfo.ContainerInfo(
                    "docker",
                    "test-docker-in-docker-image",
                    Boolean.TRUE,
                    Boolean.TRUE,
                    useCustomDockerCommandShell,
                    customDockerCommandShell,
                    Collections.<MesosSlaveInfo.Volume>emptyList(),
                    Collections.<MesosSlaveInfo.Parameter>emptyList(),
                    Protos.ContainerInfo.DockerInfo.Network.HOST.name(),
                    Collections.<MesosSlaveInfo.PortMapping>emptyList());
        }

        MesosSlaveInfo mesosSlaveInfo = new MesosSlaveInfo(
                "testLabelString",  // labelString,
                Node.Mode.NORMAL,
                "0.2",              // slaveCpus,
                "512",              //slaveMem,
                "2",                // maxExecutors,
                "0.2",              // executorCpus,
                "512",              // executorMem,
                "remoteFSRoot",     // remoteFSRoot,
                "2",                // idleTerminationMinutes,
                null,               // slaveAttributes,
                null,               // jvmArgs,
                null,               //jnlpArgs,
                null,               // externalContainerInfo,
                containerInfo,      // containerInfo,
                null,               //additionalURIs
                null                // runAsUserInfo
                );
        Mesos.SlaveRequest slaveReq = new Mesos.SlaveRequest(new Mesos.JenkinsSlave(TEST_JENKINS_SLAVE_NAME),0.2d,TEST_JENKINS_SLAVE_MEM,mesosSlaveInfo);
        Mesos.SlaveResult slaveResult = Mockito.mock(Mesos.SlaveResult.class);

        return jenkinsScheduler.new Request(slaveReq,slaveResult);

    }

    private Protos.Offer createOfferWithVariableRanges(long rangeBegin, long rangeEnd) {
        Protos.Value.Range range = Protos.Value.Range.newBuilder()
                .setBegin(rangeBegin)
                .setEnd(rangeEnd)
                .build();

        Protos.Value.Ranges ranges = Protos.Value.Ranges.newBuilder()
                .addRange(range)
                .build();

        Protos.Resource resource = Protos.Resource.newBuilder()
                .setName("ports")
                .setRanges(ranges)
                .setType(Protos.Value.Type.RANGES)
                .build();

        return Protos.Offer.newBuilder()
                .addResources(resource)
                .setId(Protos.OfferID.newBuilder().setValue("value").build())
                .setFrameworkId(Protos.FrameworkID.newBuilder().setValue("value").build())
                .setSlaveId(Protos.SlaveID.newBuilder().setValue("value").build())
                .setHostname("hostname")
                .build();
    }
}
