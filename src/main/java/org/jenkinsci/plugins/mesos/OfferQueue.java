package org.jenkinsci.plugins.mesos;

import com.google.common.annotations.VisibleForTesting;
import org.apache.mesos.Protos;

import jenkins.metrics.api.Metrics;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * This class acts as a buffer of Offers from Mesos.  By default it holds a maximum of 100 Offers.
 */
public class OfferQueue {
    private final Logger logger = Logger.getLogger(JenkinsScheduler.class.getName());

    private static final int DEFAULT_CAPACITY = 100;
    private static final Duration DEFAULT_OFFER_WAIT = Duration.ofSeconds(600);
    private final BlockingQueue<Protos.Offer> queue;

    public OfferQueue() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Creates a new queue with the provided capacity.
     *
     * @param capacity the maximum size of the queue, or zero for unlimited queue size
     */
    public OfferQueue(int capacity) {
        this.queue = capacity == 0 ? new LinkedBlockingQueue<>() : new LinkedBlockingQueue<>(capacity);
    }

    /**
     * Calling this method will wait for Offers for the provided duration.
     * It returns all Offers currently in the queue if any are present and none otherwise.
     */
    public List<Protos.Offer> takeAll(Duration duration) {
        List<Protos.Offer> offers = new LinkedList<>();
        try {
            // The poll() waits for one Offer or returns null if none is present within the timeout.  The following
            // drainTo() call will pull the following Offers (if any) off the queue and return.
            Protos.Offer offer = queue.poll(duration.getSeconds(), TimeUnit.SECONDS);
            if (offer != null) {
                offers.add(offer);
            }

            queue.drainTo(offers);
        } catch (InterruptedException e) {
            logger.warning("Interrupted while waiting for offer in queue.");
        }

        return offers;
    }

    /**
     * Calling this method will wait for Offers for a static duration of {@link OfferQueue#DEFAULT_OFFER_WAIT}.
     * It returns all Offers currently in the queue if any are present and an empty list if the duration
     * of {@link OfferQueue#DEFAULT_OFFER_WAIT} is reached.
     */
    public List<Protos.Offer> takeAll() {
        return takeAll(DEFAULT_OFFER_WAIT);
    }

    /**
     * This method enqueues an Offer from Mesos if there is capacity. If there is not capacity the Offer is not added
     * to the queue.
     * @return true if the Offer was successfully put in the queue, false otherwise
     */
    public boolean offer(Protos.Offer offer) {
        boolean success = queue.offer(offer);

        if (success) {
            Metrics.metricRegistry().meter("mesos.offer.queue.added").mark();
        } else {
            Metrics.metricRegistry().meter("mesos.offer.queue.dropped").mark();
        }

        return success;
    }

    /**
     * This method removes an offer from the queue based on its OfferID.
     */
    public void remove(Protos.OfferID offerID) {
        Collection<Protos.Offer> offers = queue.parallelStream()
                .filter(offer -> offer.getId().equals(offerID))
                .collect(Collectors.toList());

        boolean removed = queue.removeAll(offers);
        if (!removed) {
            logger.warning(
                    String.format(
                            "Attempted to remove offer: '%s' but it was not present in the queue.",
                            offerID.getValue()));
        } else {
            logger.info(String.format("Removed offer: %s", offerID.getValue()));

            Metrics.metricRegistry().meter("mesos.offer.queue.removed").mark();
        }
    }

    /**
     * This method specifies whether any offers are in the queue.
     */
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /**
     * This method returns the number of elements in the queue.
     */
    @VisibleForTesting
    int getSize() {
        return queue.size();
    }

    /**
     * This method returns the remaining capacity in the queue.
     */
    @VisibleForTesting
    int getRemainingCapacity() {
        return queue.remainingCapacity();
    }
}
