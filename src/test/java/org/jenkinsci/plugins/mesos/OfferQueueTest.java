package org.jenkinsci.plugins.mesos;

import java.util.List;
import java.util.UUID;

import com.codahale.metrics.MetricRegistry;

import org.apache.mesos.Protos;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import jenkins.metrics.api.Metrics;

/**
 * This class tests the {@link OfferQueue}.
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest( { Metrics.class })
public class OfferQueueTest {
    private static final int TEST_CAPACITY = 10;
    public static final Protos.OfferID OFFER_ID = Protos.OfferID.newBuilder().setValue("test-offer-id").build();
    public static final Protos.SlaveID AGENT_ID = Protos.SlaveID.newBuilder().setValue("test-slave-id").build();
    public static final String HOSTNAME = "test-hostname";

    public static final Protos.FrameworkID FRAMEWORK_ID =
            Protos.FrameworkID.newBuilder()
                    .setValue("test-framework-id")
                    .build();

    @Before
    public void interceptMetricRegistry() {
        PowerMockito.mockStatic(Metrics.class);
        Mockito.when(Metrics.metricRegistry()).thenReturn(new MetricRegistry());
    }

    @Test
    public void testEmptyQueue() {
        OfferQueue offerQueue = new OfferQueue();
        Assert.assertTrue(offerQueue.isEmpty());
    }

    @Test
    public void testEnqueueOffer() {
        OfferQueue offerQueue = new OfferQueue(TEST_CAPACITY);
        offerQueue.offer(getOffer());
        Assert.assertEquals(1, offerQueue.getSize());
        Assert.assertEquals(TEST_CAPACITY - 1, offerQueue.getRemainingCapacity());
    }

    @Test
    public void testExceedCapacity() {
        OfferQueue offerQueue = new OfferQueue();
        int capacity = offerQueue.getRemainingCapacity();
        for (int i = 0; i < capacity; i++) {
            Assert.assertTrue(offerQueue.offer(getOffer()));
        }

        Assert.assertEquals(0, offerQueue.getRemainingCapacity());
        Assert.assertFalse(offerQueue.offer(getOffer()));
    }

    @Test
    public void testTakeOne() {
        OfferQueue offerQueue = new OfferQueue(TEST_CAPACITY);
        offerQueue.offer(getOffer());
        List<Protos.Offer> offers = offerQueue.takeAll();
        Assert.assertEquals(1, offers.size());
        Assert.assertEquals(TEST_CAPACITY, offerQueue.getRemainingCapacity());
    }

    @Test
    public void testTakeMultiple() {
        OfferQueue offerQueue = new OfferQueue(TEST_CAPACITY);
        int halfCapacity = offerQueue.getRemainingCapacity() / 2;
        for (int i = 0; i < halfCapacity; i++) {
            offerQueue.offer(getOffer());
        }

        List<Protos.Offer> offers = offerQueue.takeAll();
        Assert.assertEquals(halfCapacity, offers.size());
        Assert.assertEquals(TEST_CAPACITY, offerQueue.getRemainingCapacity());
    }

    @Test
    public void testTakeFull() {
        OfferQueue offerQueue = new OfferQueue(TEST_CAPACITY);
        int capacity = offerQueue.getRemainingCapacity();
        for (int i = 0; i < capacity; i++) {
            offerQueue.offer(getOffer());
        }

        List<Protos.Offer> offers = offerQueue.takeAll();
        Assert.assertEquals(capacity, offers.size());
        Assert.assertEquals(TEST_CAPACITY, offerQueue.getRemainingCapacity());
    }

    @Test
    public void testRemoveFromEmptyQueue() {
        OfferQueue offerQueue = new OfferQueue();
        Assert.assertTrue(offerQueue.isEmpty());
        offerQueue.remove(OFFER_ID);
        Assert.assertTrue(offerQueue.isEmpty());
    }

    @Test
    public void testRemoveFromSingleOfferQueue() {
        OfferQueue offerQueue = new OfferQueue();
        offerQueue.offer(getOffer());
        Assert.assertEquals(1, offerQueue.getSize());
        offerQueue.remove(OFFER_ID);
        Assert.assertTrue(offerQueue.isEmpty());
    }

    @Test
    public void testRemoveUnknownOffer() {
        OfferQueue offerQueue = new OfferQueue();
        offerQueue.offer(getOffer(UUID.randomUUID().toString()));
        Assert.assertEquals(1, offerQueue.getSize());
        offerQueue.remove(OFFER_ID);
        Assert.assertEquals(1, offerQueue.getSize());
    }

    @Test
    public void testRemoveOneLeaveOthers() {
        OfferQueue offerQueue = new OfferQueue();
        int halfCapacity = offerQueue.getRemainingCapacity() / 2;
        // Add many offers with random ids
        for (int i = 0; i < halfCapacity; i++) {
            offerQueue.offer(getOffer(UUID.randomUUID().toString()));
        }

        // Add one offer with a known id
        offerQueue.offer(getOffer());

        int remainingCapacity = offerQueue.getRemainingCapacity();
        offerQueue.remove(OFFER_ID);
        // Expect capacity to increase by one as we've removed one known offer
        Assert.assertEquals(remainingCapacity + 1, offerQueue.getRemainingCapacity());
    }

    private Protos.Offer getOffer() {
        return getOffer(OFFER_ID.getValue());
    }

    private Protos.Offer getOffer(String id) {
        return Protos.Offer.newBuilder()
                .setId(Protos.OfferID.newBuilder().setValue(id))
                .setFrameworkId(FRAMEWORK_ID)
                .setSlaveId(AGENT_ID)
                .setHostname(HOSTNAME)
                .build();
    }
}
