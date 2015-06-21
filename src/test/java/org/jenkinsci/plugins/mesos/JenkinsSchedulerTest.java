package org.jenkinsci.plugins.mesos;

import org.apache.mesos.Protos;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

public class JenkinsSchedulerTest {
    @Mock
    private MesosCloud mesosCloud;

    private JenkinsScheduler jenkinsScheduler;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mesosCloud.getMaster()).thenReturn("Mesos Cloud Master");
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
