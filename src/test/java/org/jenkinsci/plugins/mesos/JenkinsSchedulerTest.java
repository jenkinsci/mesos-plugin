package org.jenkinsci.plugins.mesos;

import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Queue.Item;
import jenkins.metrics.api.Metrics;
import jenkins.model.Jenkins;
import org.apache.mesos.Protos;
import org.apache.mesos.SchedulerDriver;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.Matchers.anyListOf;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;


@RunWith(PowerMockRunner.class)
@PrepareForTest( { Jenkins.class })
public class JenkinsSchedulerTest {
    @Mock
    private MesosCloud mesosCloud;

    private JenkinsScheduler jenkinsScheduler;

    private Jenkins jenkins;

    private static final int    TEST_JENKINS_SLAVE_MEM   = 512;
    private static final String TEST_JENKINS_SLAVE_ARG   = "-Xms16m -XX:+UseConcMarkSweepGC -Djava.net.preferIPv4Stack=true";
    private static final String TEST_JENKINS_JNLP_ARG    = "";
    private static final String TEST_JENKINS_SLAVE_NAME  = "testSlave1";
    private static final String TEST_MESOS_ROLE_NAME     = "test_role";
    private static final Protos.FrameworkID TEST_FRAMEWORK_ID =
            Protos.FrameworkID.newBuilder().setValue("test-framework-id").build();


    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mesosCloud.getMaster()).thenReturn("Mesos Cloud Master");

        // Simulate basic Jenkins env
        jenkins = Mockito.mock(Jenkins.class);
        when(jenkins.getPlugin(Metrics.class)).thenReturn(new Metrics());
        when(jenkins.isUseSecurity()).thenReturn(false);
        PowerMockito.mockStatic(Jenkins.class);
        Mockito.when(Jenkins.getInstance()).thenReturn(jenkins);

        jenkinsScheduler = new JenkinsScheduler("jenkinsMaster", mesosCloud, false);

    }

    @Test
    public void testFindRoleForPorts() {
        Protos.Offer offer = createOfferWithRoleForPorts();
        String role = jenkinsScheduler.findRoleForPorts(offer);
        assertEquals(TEST_MESOS_ROLE_NAME, role);
    }

    @Test
    public void testFindPortsToUse() {
        Protos.Offer offer = createOfferWithVariableRanges(31000, 32000);

        Set<Long> portsToUse = jenkinsScheduler.findPortsToUse(offer, 1);

        assertEquals(1, portsToUse.size());
        assertEquals(Long.valueOf(31000), portsToUse.iterator().next());
    }

    @Test
    public void testFindPortsToUseSamePortNumber() {
        final ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                Protos.Offer offer = createOfferWithVariableRanges(31000, 31000);

                Set<Long> portsToUse = jenkinsScheduler.findPortsToUse(offer, 1);

                assertEquals(1, portsToUse.size());
                assertEquals(Long.valueOf(31000), portsToUse.iterator().next());
            }
        });

        executorService.shutdown();

        // Test that there is no infinite loop
        try {
            assertTrue(executorService.awaitTermination(2L, TimeUnit.SECONDS));
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
                SortedSet<Long> portsToUse = jenkinsScheduler.findPortsToUse(protoOffer, 2);

                assertEquals(2, portsToUse.size());
                Iterator<Long> iterator = portsToUse.iterator();
                assertEquals(Long.valueOf(31000), iterator.next());
                assertEquals(Long.valueOf(31005), iterator.next());
            }
        });

        executorService.shutdown();

        // Test that there is no infinite loop
        try {
            assertTrue(executorService.awaitTermination(2L, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    public void testDeclineOffersWithNoRequestsInQueue() throws Exception {
        Protos.Offer offer = createOfferWithVariableRanges(31000, 32000);
        ArrayList<Protos.Offer> offers = new ArrayList<Protos.Offer>();
        offers.add(offer);

        Queue queue = Mockito.mock(Queue.class);
        Mockito.when(jenkins.getQueue()).thenReturn(queue);

        SchedulerDriver driver = Mockito.mock(SchedulerDriver.class);
        jenkinsScheduler.setDriver(driver);
        Mockito.when(mesosCloud.getDeclineOfferDurationDouble()).thenReturn((double) 120000);
        jenkinsScheduler.resourceOffers(driver, offers);
        Mockito.verify(driver, Mockito.never()).declineOffer(offer.getId());
        Mockito.verify(driver).declineOffer(offer.getId(), Protos.Filters.newBuilder().setRefuseSeconds(120000).build());
    }

    @Test
    public void testDeclineOffersWithRequestsInQueue() throws Exception {
        Mesos.SlaveRequest request = mockSlaveRequest(false, false, null);
        jenkinsScheduler.requestJenkinsSlave(request, null);

        Protos.Offer offer = createOfferWithVariableRanges(31000, 32000);
        ArrayList<Protos.Offer> offers = new ArrayList<Protos.Offer>();
        offers.add(offer);

        SchedulerDriver driver = Mockito.mock(SchedulerDriver.class);
        jenkinsScheduler.setDriver(driver);
        Mockito.when(mesosCloud.getDeclineOfferDurationDouble()).thenReturn((double) 120000);
        jenkinsScheduler.resourceOffers(driver, offers);
        Mockito.verify(driver).declineOffer(offer.getId(), Protos.Filters.newBuilder().setRefuseSeconds(MesosCloud.SHORT_DECLINE_OFFER_DURATION_SEC).build());
        Mockito.verify(driver, Mockito.never()).declineOffer(offer.getId(), Protos.Filters.newBuilder().setRefuseSeconds(120000).build());
    }

    @Test
    public void testDeclineOffersWithBuildsInQueue() throws Exception {
        Protos.Offer offer = createOfferWithVariableRanges(31000, 32000);
        ArrayList<Protos.Offer> offers = new ArrayList<Protos.Offer>();
        offers.add(offer);

        Queue queue = Mockito.mock(Queue.class);
        Mockito.when(jenkins.getQueue()).thenReturn(queue);

        Item item = Mockito.mock(Item.class);
        Item [] items = {item};
        Mockito.when(queue.getItems()).thenReturn(items);
        Mockito.when(mesosCloud.canProvision(null)).thenReturn(true);

        SchedulerDriver driver = Mockito.mock(SchedulerDriver.class);
        jenkinsScheduler.setDriver(driver);
        Mockito.when(mesosCloud.getDeclineOfferDurationDouble()).thenReturn((double) 120000);
        jenkinsScheduler.resourceOffers(driver, offers);
        Mockito.verify(driver).declineOffer(offer.getId(), Protos.Filters.newBuilder().setRefuseSeconds(MesosCloud.SHORT_DECLINE_OFFER_DURATION_SEC).build());
        Mockito.verify(driver, Mockito.never()).declineOffer(offer.getId(), Protos.Filters.newBuilder().setRefuseSeconds(120000).build());
    }

    @Test
    public void testAcceptOfferAndLaunchTasksWithPastUnavailability() throws Exception {
        Date now = new Date();
        Date startDate = new Date(now.getTime() - TimeUnit.HOURS.toMillis(1));
        Date endDate = new Date(startDate.getTime() + TimeUnit.MINUTES.toMillis(30));

        Protos.Offer matchingOffer = createOfferWithUnavailability(startDate, endDate);
        ArrayList<Protos.Offer> offers = new ArrayList<Protos.Offer>();
        offers.add(matchingOffer);

        Mesos.SlaveRequest request = mockSlaveRequest(false, false, null);
        jenkinsScheduler.requestJenkinsSlave(request, null);

        Queue queue = Mockito.mock(Queue.class);
        Mockito.when(jenkins.getQueue()).thenReturn(queue);

        MesosComputer computer = Mockito.mock(MesosComputer.class);
        Mockito.when(jenkins.getComputers()).thenReturn(new Computer[] { computer });

        Item item = Mockito.mock(Item.class);
        Item [] items = {item};
        Mockito.when(queue.getItems()).thenReturn(items);
        Mockito.when(mesosCloud.canProvision(null)).thenReturn(true);

        SchedulerDriver driver = Mockito.mock(SchedulerDriver.class);
        jenkinsScheduler.setDriver(driver);

        jenkinsScheduler.resourceOffers(driver, offers);

        Mockito.verify(driver, never()).declineOffer(matchingOffer.getId());
        Mockito.verify(driver).launchTasks(eq(matchingOffer.getId()), anyListOf(Protos.TaskInfo.class), eq(Protos.Filters.newBuilder().setRefuseSeconds(1).build()));
        assertEquals(0, jenkinsScheduler.getUnmatchedLabels().size());
    }

    @Test
    public void testAcceptOfferAndLaunchTasksWithFutureUnavailability() throws Exception {
        // set it up
        Date now = new Date();
        Date startDate = new Date(now.getTime() + TimeUnit.HOURS.toMillis(1));
        Date endDate = new Date(startDate.getTime() + TimeUnit.MINUTES.toMillis(30));

        Protos.Offer matchingOffer = createOfferWithUnavailability(startDate, endDate);
        ArrayList<Protos.Offer> offers = new ArrayList<Protos.Offer>();
        offers.add(matchingOffer);

        Mesos.SlaveRequest request = mockSlaveRequest(false, false, null);
        jenkinsScheduler.requestJenkinsSlave(request, null);

        Queue queue = Mockito.mock(Queue.class);
        Mockito.when(jenkins.getQueue()).thenReturn(queue);

        MesosComputer computer = Mockito.mock(MesosComputer.class);
        Mockito.when(jenkins.getComputers()).thenReturn(new Computer[] { computer });

        Item item = Mockito.mock(Item.class);
        Item [] items = { item };
        Mockito.when(queue.getItems()).thenReturn(items);
        Mockito.when(mesosCloud.canProvision(null)).thenReturn(true);

        SchedulerDriver driver = Mockito.mock(SchedulerDriver.class);
        jenkinsScheduler.setDriver(driver);

        // test it
        jenkinsScheduler.resourceOffers(driver, offers);

        // verify it
        Mockito.verify(driver, never()).declineOffer(matchingOffer.getId());
        Mockito.verify(driver).launchTasks(eq(matchingOffer.getId()), anyListOf(Protos.TaskInfo.class), eq(Protos.Filters.newBuilder().setRefuseSeconds(1).build()));
        assertEquals(0, jenkinsScheduler.getUnmatchedLabels().size());
    }

    @Test
    public void testDeclineOfferWithCurrentUnavailability() throws Exception {
        // set it up
        Date now = new Date();
        Date startDate = new Date(now.getTime() - TimeUnit.HOURS.toMillis(1));
        Date endDate = new Date(startDate.getTime() + TimeUnit.HOURS.toMillis(2));

        Protos.Offer matchingOffer = createOfferWithUnavailability(startDate, endDate);
        ArrayList<Protos.Offer> offers = new ArrayList<Protos.Offer>();
        offers.add(matchingOffer);

        Mesos.SlaveRequest request = mockSlaveRequest(false, false, null);
        jenkinsScheduler.requestJenkinsSlave(request, null);

        Queue queue = Mockito.mock(Queue.class);
        Mockito.when(jenkins.getQueue()).thenReturn(queue);

        Item item = Mockito.mock(Item.class);
        Item [] items = { item };
        Mockito.when(queue.getItems()).thenReturn(items);
        Mockito.when(mesosCloud.canProvision(null)).thenReturn(true);

        SchedulerDriver driver = Mockito.mock(SchedulerDriver.class);
        jenkinsScheduler.setDriver(driver);

        // test it
        jenkinsScheduler.resourceOffers(driver, offers);

        // verify it
        // make sure it does not call "matches()" and gets declined because of a non-matching offer
        Mockito.verify(mesosCloud, never()).getRole();
        Mockito.verify(driver).declineOffer(matchingOffer.getId(), Protos.Filters.newBuilder().setRefuseSeconds(MesosCloud.SHORT_DECLINE_OFFER_DURATION_SEC).build());
        assertEquals(1, jenkinsScheduler.getUnmatchedLabels().size());
    }

    @Test
    public void testReviveOffersWhenAddingSlaveRequest() throws Exception {
        SchedulerDriver driver = Mockito.mock(SchedulerDriver.class);
        jenkinsScheduler.setDriver(driver);
        Mesos.SlaveRequest request = mockSlaveRequest(false, false, null);

        jenkinsScheduler.requestJenkinsSlave(request, null);
        Mockito.verify(driver).reviveOffers();
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
                TEST_JENKINS_SLAVE_NAME);
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
                TEST_JENKINS_SLAVE_NAME);
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
                TEST_JENKINS_SLAVE_NAME);

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

    @Test
    public void isProcessing() {
        jenkinsScheduler = new JenkinsScheduler("jenkinsMaster", mesosCloud, false);
        Assert.assertFalse(jenkinsScheduler.isProcessing());

        jenkinsScheduler.startProcessing();
        Assert.assertTrue(jenkinsScheduler.isProcessing());
    }

    @Test
    public void constructMultiThreaded() {
        SchedulerDriver driver = Mockito.mock(SchedulerDriver.class);

        jenkinsScheduler = new JenkinsScheduler("jenkinsMaster", mesosCloud, true);
        jenkinsScheduler.setDriver(driver);
        Assert.assertFalse(jenkinsScheduler.isProcessing());

        jenkinsScheduler.registered(driver, TEST_FRAMEWORK_ID, null);
        jenkinsScheduler.resourceOffers(driver, Collections.emptyList());

        Assert.assertTrue(jenkinsScheduler.isProcessing());
    }

    @Test
    public void getFrameworkId() {
        Assert.assertEquals(JenkinsScheduler.NULL_FRAMEWORK_ID, jenkinsScheduler.getFrameworkId());
        jenkinsScheduler.registered(null, TEST_FRAMEWORK_ID, null);
        Assert.assertEquals(TEST_FRAMEWORK_ID.getValue(), jenkinsScheduler.getFrameworkId());
    }

    private Mesos.SlaveRequest mockSlaveRequest(
        Boolean useDocker,
        Boolean useCustomDockerCommandShell,
        String customDockerCommandShell) throws Descriptor.FormException, IOException {
        MesosSlaveInfo.ContainerInfo containerInfo = null;
        if (useDocker) {
            containerInfo = new MesosSlaveInfo.ContainerInfo(
                    "docker",
                    "test-docker-in-docker-image",
                    Boolean.TRUE,
                    Boolean.TRUE,
                    Boolean.FALSE,
                    useCustomDockerCommandShell,
                    customDockerCommandShell,
                    Collections.<MesosSlaveInfo.Volume>emptyList(),
                    Collections.<MesosSlaveInfo.Parameter>emptyList(),
                    Protos.ContainerInfo.DockerInfo.Network.HOST.name(),
                    Collections.<MesosSlaveInfo.PortMapping>emptyList(),
                    Collections.<MesosSlaveInfo.NetworkInfo>emptyList());
        }

        MesosSlaveInfo mesosSlaveInfo = new MesosSlaveInfo(
                "testLabelString",  // labelString,
                Node.Mode.NORMAL,
                "0.2",              // slaveCpus,
                "512",              // slaveMem,
                "1",                // minExecutors,
                "2",                // maxExecutors,
                "0.2",              // executorCpus,
                "500",               // diskNeeded
                "512",              // executorMem,
                "remoteFSRoot",     // remoteFSRoot,
                "2",                // idleTerminationMinutes,
                (String)null,       // slaveAttributes,
                null,               // jvmArgs,
                null,               // jnlpArgs,
                null,               // defaultSlave,
                containerInfo,      // containerInfo,
                null,              // additionalURIs
                null              // nodeProperties
                );
        return new Mesos.SlaveRequest(
            new Mesos.JenkinsSlave(TEST_JENKINS_SLAVE_NAME), Mockito.mock(MesosSlave.class), 0.2d, TEST_JENKINS_SLAVE_MEM, "jenkins", mesosSlaveInfo, 500);
    }

    private JenkinsScheduler.Request mockMesosRequest(
            Boolean useDocker,
            Boolean useCustomDockerCommandShell,
            String customDockerCommandShell) throws Descriptor.FormException, IOException {
        Mesos.SlaveResult slaveResult = Mockito.mock(Mesos.SlaveResult.class);
        return new JenkinsScheduler.Request(
            mockSlaveRequest(useDocker, useCustomDockerCommandShell, customDockerCommandShell),
            slaveResult);
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

    private Protos.Offer createOfferWithRoleForPorts() {
        Protos.Value.Range range = Protos.Value.Range.newBuilder()
                .setBegin(31000)
                .setEnd(32000)
                .build();

        Protos.Value.Ranges ranges = Protos.Value.Ranges.newBuilder()
                .addRange(range)
                .build();

        Protos.Resource resource = Protos.Resource.newBuilder()
                .setName("ports")
                .setRanges(ranges)
                .setRole(TEST_MESOS_ROLE_NAME)
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

    private Protos.Resource createScalarResource(String name, double value) {
        return Protos.Resource.newBuilder()
                .setName(name)
                .setScalar(Protos.Value.Scalar.newBuilder().setValue(value))
                .setType(Protos.Value.Type.SCALAR)
                .build();
    }

    private Protos.Resource createCpuResource(double value) {
        return createScalarResource("cpus", value);
    }

    private Protos.Resource createMemResource(double value) {
        return createScalarResource("mem", value);
    }

    private Protos.Offer createOfferWithUnavailability(Date startDate, Date endDate) {
        return createOfferWithUnavailability(startDate, endDate, 2.0, 1000.0);
    }

    private Protos.Offer createOfferWithUnavailability(Date startDate, Date endDate, double valueCpus, double valueMem) {

        long startNanos = TimeUnit.MILLISECONDS.toNanos(startDate.getTime());
        long durationNanos = TimeUnit.MILLISECONDS.toNanos(endDate.getTime() - startDate.getTime());

        Protos.Unavailability unavailability = Protos.Unavailability.newBuilder()
                .setStart(Protos.TimeInfo.newBuilder().setNanoseconds(startNanos))
                .setDuration(Protos.DurationInfo.newBuilder().setNanoseconds(durationNanos))
                .build();

        Protos.Offer offer = createOfferWithVariableRanges(31000, 32000);
        return Protos.Offer.newBuilder(offer)
                .addResources(createCpuResource(valueCpus))
                .addResources(createMemResource(valueMem))
                .setUnavailability(unavailability).build();
    }
}
