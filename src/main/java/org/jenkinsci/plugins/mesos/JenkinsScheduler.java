/*
 * Copyright 2013 Twitter, Inc. and other contributors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jenkinsci.plugins.mesos;


import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.codahale.metrics.Timer;
import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.TextFormat;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.util.Secret;
import jenkins.metrics.api.Metrics;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.collections4.OrderedMapIterator;
import org.apache.commons.collections4.map.LRUMap;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.mesos.MesosSchedulerDriver;
import org.apache.mesos.Protos;
import org.apache.mesos.Protos.*;
import org.apache.mesos.Protos.ContainerInfo.DockerInfo;
import org.apache.mesos.Protos.ContainerInfo.DockerInfo.Network;
import org.apache.mesos.Protos.ContainerInfo.DockerInfo.PortMapping;
import org.apache.mesos.Protos.Value.Range;
import org.apache.mesos.Protos.Volume.Mode;
import org.apache.mesos.Scheduler;
import org.apache.mesos.SchedulerDriver;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

public class JenkinsScheduler implements Scheduler {
    private static final String SLAVE_JAR_URI_SUFFIX = "jnlpJars/slave.jar";

    /**
     * When created, we expect this scheduler to live for at least 1 minute before killing it.
     * This prevents race conditions between threads provisioning new nodes and threads terminating them.
     */
    private static final Long MINIMUM_TIME_TO_LIVE = TimeUnit.MINUTES.toMillis(1);

    // We allocate 10% more memory to the Mesos task to account for the JVM overhead.
    private static final double JVM_MEM_OVERHEAD_FACTOR = 0.1;

    private static final String SLAVE_COMMAND_FORMAT =
            "java -DHUDSON_HOME=jenkins -server -Xmx%dm %s -jar ${MESOS_SANDBOX-.}/slave.jar %s %s -jnlpUrl %s";
    private static final String JNLP_SECRET_FORMAT = "-secret %s";
    public static final String PORT_RESOURCE_NAME = "ports";
    public static final String MESOS_DEFAULT_ROLE = "*";
    public static final String NULL_FRAMEWORK_ID = "null-framework-id";
    private final boolean multiThreaded;

    private Queue<Request> requests;
    private Set<String> unmatchedLabels;
    private Map<TaskID, Result> results;
    private Set<TaskID> finishedTasks;
    private volatile SchedulerDriver driver;
    private String jenkinsMaster;
    private volatile MesosCloud mesosCloud;
    private volatile boolean running;

    private long startedTime;

    private static final Logger LOGGER = Logger.getLogger(JenkinsScheduler.class.getName());

    public static final Lock SUPERVISOR_LOCK = new ReentrantLock();

    private static int lruCacheSize = Integer.getInteger(JenkinsScheduler.class.getName()+".lruCacheSize", 10);

    private static LRUMap<String, Object> recentlyAcceptedOffers = new LRUMap<String, Object>(lruCacheSize);

    private static final Object IGNORE = new Object();

    private static final OfferQueue offerQueue = new OfferQueue();
    private Thread offerProcessingThread = null;
    private volatile FrameworkID frameworkId;

    public JenkinsScheduler(String jenkinsMaster, MesosCloud mesosCloud, boolean multiThreaded) {
        startedTime = System.currentTimeMillis();
        LOGGER.info("JenkinsScheduler instantiated with jenkins " + jenkinsMaster + " and mesos " + mesosCloud.getMaster() + " multithreaded " + multiThreaded);

        this.jenkinsMaster = jenkinsMaster;
        this.mesosCloud = mesosCloud;
        this.multiThreaded = multiThreaded;

        this.requests = new LinkedList<Request>();
        this.unmatchedLabels = new HashSet<String>();
        this.results = new HashMap<TaskID, Result>();
        this.finishedTasks = Collections.newSetFromMap(new ConcurrentHashMap<TaskID, Boolean>());
    }

    public synchronized void init() {
        // This is to ensure that isRunning() returns true even when the driver is not yet inside run().
        // This is important because MesosCloud.provision() starts a new framework whenever isRunning() is false.
        running = true;
        String targetUser = mesosCloud.getSlavesUser();
        String webUrl = getJenkins().getRootUrl();
        if (webUrl == null) webUrl = System.getenv("JENKINS_URL");
        StandardUsernamePasswordCredentials credentials = mesosCloud.getCredentials();
        String principal = credentials == null ? "jenkins" : credentials.getUsername();
        String secret = credentials == null ? "" : Secret.toString(credentials.getPassword());
        // Have Mesos fill in the current user.
        FrameworkInfo framework = FrameworkInfo.newBuilder()
                .setUser(targetUser == null ? "" : targetUser)
                .setName(mesosCloud.getFrameworkName())
                .setRole(mesosCloud.getRole())
                .setPrincipal(principal)
                .setCheckpoint(mesosCloud.isCheckpoint())
                .setWebuiUrl(webUrl != null ? webUrl : "")
                .build();

        LOGGER.info("Initializing the Mesos driver with options"
                + "\n" + "Framework Name: " + framework.getName()
                + "\n" + "Principal: " + principal
                + "\n" + "Checkpointing: " + framework.getCheckpoint()
        );

        if (StringUtils.isNotBlank(secret)) {

            Credential credential = Credential.newBuilder()
                    .setPrincipal(principal)
                    .setSecret(secret)
                    .build();

            LOGGER.info("Authenticating with Mesos master with principal " + credential.getPrincipal());
            driver = new MesosSchedulerDriver(JenkinsScheduler.this, framework, mesosCloud.getMaster(), credential);
        } else {
            driver = new MesosSchedulerDriver(JenkinsScheduler.this, framework, mesosCloud.getMaster());
        }
        // Start the framework.
        Thread frameworkThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Status runStatus = driver.run();
                    if (runStatus != Status.DRIVER_STOPPED) {
                        LOGGER.severe("The Mesos driver was aborted! Status code: " + runStatus.getNumber());
                    } else {
                        LOGGER.info("The Mesos driver was stopped.");
                    }
                } catch(RuntimeException e) {
                    LOGGER.log(Level.SEVERE, "Caught a RuntimeException", e);
                } finally {
                    SUPERVISOR_LOCK.lock();
                    if (driver != null) {
                        driver.abort();
                    }
                    driver = null;
                    running = false;
                    SUPERVISOR_LOCK.unlock();
                }
            }
        });

        frameworkThread.setName("mesos-framework-thread");
        frameworkThread.setDaemon(true);

        frameworkThread.start();
    }

    public synchronized void stop() {
        try {
            SUPERVISOR_LOCK.lock();
            if (driver != null) {
                LOGGER.info("Stopping Mesos driver.");
                driver.stop();
            } else {
                LOGGER.warning("Unable to stop Mesos driver:  driver is null.");
            }
            running = false;
        } finally {
            SUPERVISOR_LOCK.unlock();
        }
    }

    public synchronized boolean isRunning() {
        return running;
    }

    public synchronized void requestJenkinsSlave(Mesos.SlaveRequest request, Mesos.SlaveResult result) {
        Metrics.metricRegistry().meter("mesos.scheduler.slave.requests").mark();
        LOGGER.fine("Enqueuing jenkins slave request");
        requests.add(new Request(request, result));
        if (driver != null) {
            // Ask mesos to send all offers, even the those we declined earlier.
            // See comment in resourceOffers() for further details.
            Timer.Context ctx = Metrics.metricRegistry().timer("mesos.scheduler.revives").time();
            driver.reviveOffers();
            ctx.stop();
        }
    }

    public boolean reachedMinimumTimeToLive() {
        return System.currentTimeMillis() - startedTime > MINIMUM_TIME_TO_LIVE;
    }

    /**
     * @param slaveName the slave name in jenkins
     * @return the jnlp url for the slave: http://[master]/computer/[slaveName]/slave-agent.jnlp
     */
    private String getJnlpUrl(String slaveName) {
        return joinPaths(joinPaths(joinPaths(jenkinsMaster, "computer"), slaveName), "slave-agent.jnlp");
    }

    /**
     * Slave needs to go through authentication while connecting through jnlp if security is enabled in jenkins.
     * This method gets secret (for jnlp authentication) from jenkins, constructs command line argument and returns it.
     *
     * @param slaveName the slave name in jenkins
     * @return jenkins slave secret corresponding to slave name in the format '-secret <secret>'
     */
    private String getJnlpSecret(String slaveName) {
        String jnlpSecret = "";
        if(getJenkins().isUseSecurity()) {
            jnlpSecret = String.format(JNLP_SECRET_FORMAT, jenkins.slaves.JnlpSlaveAgentProtocol.SLAVE_SECRET.mac(slaveName));
        }
        return jnlpSecret;
    }

    private static String joinPaths(String prefix, String suffix) {
        if (prefix.endsWith("/"))   prefix = prefix.substring(0, prefix.length()-1);
        if (suffix.startsWith("/")) suffix = suffix.substring(1, suffix.length());

        return prefix + '/' + suffix;
    }

    public synchronized void terminateJenkinsSlave(String name) {
        LOGGER.info("Terminating jenkins slave " + name);

        TaskID taskId = TaskID.newBuilder().setValue(name).build();

        if (results.containsKey(taskId)) {
            LOGGER.info("Killing mesos task " + taskId);
            driver.killTask(taskId);
        } else {
            // This is handling the situation that a slave was provisioned but it never
            // got scheduled because of resource scarcity and jenkins later tries to remove
            // the offline slave but since it was not scheduled we have to remove it from
            // the request queue. The method has been also synchronized because there is a race
            // between this removal request from jenkins and a resource getting freed up in mesos
            // resulting in scheduling the slave and resulting in orphaned task/slave not monitored
            // by Jenkins.

            for(Request request : requests) {
                if(request.request.slave.name.equals(name)) {
                    LOGGER.info("Removing enqueued mesos task " + name);
                    requests.remove(request);
                    // Also signal the Thread of the MesosComputerLauncher.launch() to exit from latch.await()
                    // Otherwise the Thread will stay in WAIT forever -> Leak!
                    request.result.failed(request.request.slave);
                    return;
                }
            }

            LOGGER.warning("Asked to kill unknown mesos task " + taskId);
        }

        // Since this task is now running, we should not start this task up again at a later point in time
        finishedTasks.add(taskId);

        if (mesosCloud.isOnDemandRegistration()) {
            supervise();
        }

    }

    @Override
    public void registered(SchedulerDriver driver, FrameworkID frameworkId, MasterInfo masterInfo) {
        LOGGER.info("Framework registered! ID = " + frameworkId.getValue());
        this.frameworkId = frameworkId;
    }

    @Override
    public void reregistered(SchedulerDriver driver, MasterInfo masterInfo) {
        LOGGER.info("Framework re-registered");
    }

    @Override
    public void disconnected(SchedulerDriver driver) {
        LOGGER.info("Framework disconnected!");
    }

    private void declineShort(Offer offer) {
        Metrics.metricRegistry().meter("mesos.scheduler.decline.short").mark();

        if (offer.hasSlaveId()) {
            Metrics.metricRegistry().meter("mesos.scheduler.decline.short."+offer.getSlaveId().getValue()).mark();
        }

        declineOffer(offer, MesosCloud.SHORT_DECLINE_OFFER_DURATION_SEC);
    }

    private void declineOffer(Offer offer, double duration) {
        LOGGER.info(String.format("Declining offer %s for %s seconds", offer.getId().getValue(), duration));
        Filters filters = Filters.newBuilder().setRefuseSeconds(duration).build();
        driver.declineOffer(offer.getId(), filters);
    }

    private void processOffers() {
        List<Offer> offers = offerQueue.takeAll();
        LOGGER.info("Processing " + offers.size() + " offers.");
        reArrangeOffersBasedOnAffinity(offers);
        int processedRequests = 0;
        for (Offer offer : offers) {
            Metrics.metricRegistry().meter("mesos.scheduler.offer.processed").mark();
            if (offer.hasSlaveId()) {
                Metrics.metricRegistry().meter("mesos.scheduler.offers.processed."+offer.getSlaveId().getValue()).mark();
            }

            final Timer.Context offerContext = Metrics.metricRegistry().timer("mesos.scheduler.offer.processing.time").time();
            try {
                if (requests.isEmpty() && !buildsInQueue(Jenkins.getInstance().getQueue())) {
                    unmatchedLabels.clear();
                    // Decline offer for a longer period if no slave is waiting to get spawned.
                    // This prevents unnecessarily getting offers every few seconds and causing
                    // starvation when running a lot of frameworks.
                    Metrics.metricRegistry().meter("mesos.scheduler.decline.long").mark();
                    LOGGER.info("No slave in queue.");

                    if (offer.hasSlaveId()) {
                        Metrics.metricRegistry().meter("mesos.scheduler.decline.long."+offer.getSlaveId().getValue()).mark();
                    }
                    declineOffer(offer, mesosCloud.getDeclineOfferDurationDouble());
                    continue;
                }

                boolean taskCreated = false;

                if (isOfferAvailable(offer)) {
                    for (Request request : requests) {
                        // TODO: Dirty modification of list while traversing it.
                        if (matches(offer, request)) {
                            Timer.Context ctx = Metrics.metricRegistry().timer("mesos.scheduler.offer.matched").time();
                            LOGGER.info("Offer matched! Creating mesos task " + request.request.slave.name);
                            try {
                                createMesosTask(offer, request);
                                unmatchedLabels.remove(request.request.slaveInfo.getLabelString());
                                taskCreated = true;
                                recentlyAcceptedOffers.put(offer.getSlaveId().getValue(), IGNORE);
                            } catch (Exception e) {
                                LOGGER.log(Level.SEVERE, e.getMessage(), e);
                            } finally {
                                ctx.stop();
                            }
                            requests.remove(request);
                            processedRequests++;
                            break;
                        }
                    }
                } else {
                    Metrics.metricRegistry().meter("mesos.scheduler.offer.unavailable").mark();
                }

                if (!taskCreated) {
                    declineShort(offer);
                    continue;
                }
            } finally {
                offerContext.stop();
            }
        }
        if (processedRequests > 0) {
            if (!requests.isEmpty()) {
                LOGGER.info("Created " + processedRequests + " tasks from " + offers.size() + " offers (" + requests.size() + " pending requests).");
            } else {
                LOGGER.info("Created " + processedRequests + " tasks from " + offers.size() + " offers.");
            }
        } else {
            if (!requests.isEmpty()) {
                LOGGER.info("Did not match any of the " + offers.size() + " offers (" + requests.size() + " pending requests)");
            }
        }
        for (Request request: requests) {
            unmatchedLabels.add(request.request.slaveInfo.getLabelString());
        }
    }

    @Override
    public synchronized void resourceOffers(SchedulerDriver driver, List<Offer> offers) {
        Metrics.metricRegistry().meter("mesos.scheduler.offers.received").mark(offers.size());

        if (multiThreaded && !isProcessing()) {
            startProcessing();
        }

        for (Protos.Offer offer : offers) {
            boolean queued = offerQueue.offer(offer);

            // Track offers received per agent
            if (offer.hasSlaveId()) {
                Metrics.metricRegistry().meter("mesos.scheduler.offers.received."+offer.getSlaveId().getValue()).mark();
            }

            if (!queued) {
                LOGGER.warning("Offer queue is full.");
                declineShort(offer);
            } else {
                LOGGER.info(
                        String.format(
                                "Queued offer %s from %s",
                                offer.getId().getValue(),
                                offer.getSlaveId().getValue()));
            }
        }

        if (!multiThreaded) {
            processOffers();
        }
    }

    @VisibleForTesting
    String getFrameworkId() {
        if (frameworkId != null) {
            return frameworkId.getValue();
        } else {
            return NULL_FRAMEWORK_ID;
        }
    }

    @VisibleForTesting
    void startProcessing() {
        String threadName = "mesos-offer-processor-" + getFrameworkId();
        LOGGER.info("Starting offer processing thread: " + threadName);

        offerProcessingThread = new Thread(new Runnable() {
            @Override
            public void run() {
                LOGGER.info("Started offer processing thread: " + threadName);
                try {
                    while (true) {
                        processOffers();
                    }
                } catch (Throwable t) {
                    LOGGER.severe("Offer processing thread failed with exception: " + ExceptionUtils.getStackTrace(t));
                }
            }
        }, threadName);
        offerProcessingThread.start();
    }

    @VisibleForTesting
    boolean isProcessing() {
        if (offerProcessingThread == null) {
            LOGGER.info("Initializing offer processing thread.");
            return false;
        }

        if (!offerProcessingThread.isAlive()) {
            LOGGER.info("Offer processing thread is not alive.");
            return false;
        }

        return true;
    }

    /**
     * Determines whether an offer of a Mesos Agent has an unavailability set and is currently scheduled for maintenance.
     * <br /><br />
     * For information on how to use this feature with Mesos refer to
     * <a href="http://mesos.apache.org/documentation/latest/maintenance/">Maintenance Primitives</a>)
     *
     * @param offer The offer to check for availability
     * @return whether or not the offer is currently available
     */
    private boolean isOfferAvailable(Offer offer) {
        if(offer.hasUnavailability()) {
            Protos.Unavailability unavailability = offer.getUnavailability();

            Date startTime = new Date(TimeUnit.NANOSECONDS.toMillis(unavailability.getStart().getNanoseconds()));
            long duration = unavailability.getDuration().getNanoseconds();
            Date endTime = new Date(startTime.getTime() + TimeUnit.NANOSECONDS.toMillis(duration));
            Date currentTime = new Date();

            return !(startTime.before(currentTime) && endTime.after(currentTime));
        }

        return true;
    }

    /**
     * @return the unmatched labels during the last call of resourceOffers.
     */
    public synchronized Set<String> getUnmatchedLabels() {
        return Collections.unmodifiableSet(unmatchedLabels);
    }

    /**
     * Makes sure that it gives priority to recent mesos slaves
     * (LRU algorithm) if its present in offers list.
     * This will help in reducing build time since all the required
     * artifacts will be present already.
     * @param offers
     */
    private void reArrangeOffersBasedOnAffinity(List<Offer> offers) {
        if(recentlyAcceptedOffers.size() > 0) {
            //Iterates from least to most recently used.
            OrderedMapIterator<String, Object> mapIterator = recentlyAcceptedOffers.mapIterator();
            while (mapIterator.hasNext()) {
                String recentSlaveId = mapIterator.next();
                reArrangeOffersBasedOnAffinity(offers, recentSlaveId);
            }
        }
    }

    private void reArrangeOffersBasedOnAffinity(List<Offer> offers, String recentSlaveId) {
        int offerIndex = getOfferIndex(offers, recentSlaveId);
        if(offerIndex > 0) {
            LOGGER.fine("Rearranging offers based on affinity");
            Offer recentOffer = offers.remove(offerIndex);
            offers.add(0, recentOffer);
        }
    }

    private int getOfferIndex(List<Offer> offers, String recentSlaveId) {
        int offerIndex = -1;
        for (Offer offer : offers) {
            if(offer.getSlaveId().getValue().equals(recentSlaveId)) {
                offerIndex = offers.indexOf(offer);
                return offerIndex;
            }
        }
        return offerIndex;
    }

    private boolean matches(Offer offer, Request request) {
        double cpus = -1;
        double mem = -1;
        List<Range> ports = null;

        for (Resource resource : offer.getResourcesList()) {
            String resourceRole = resource.getRole();
            String expectedRole = mesosCloud.getRole();
            if (! (resourceRole.equals(expectedRole) || resourceRole.equals("*"))) {
                LOGGER.warning("Resource role " + resourceRole +
                        " doesn't match expected role " + expectedRole);
                continue;
            }
            if (resource.getName().equals("cpus")) {
                if (resource.getType().equals(Value.Type.SCALAR)) {
                    cpus = resource.getScalar().getValue();
                } else {
                    LOGGER.severe("Cpus resource was not a scalar: " + resource.getType().toString());
                }
            } else if (resource.getName().equals("mem")) {
                if (resource.getType().equals(Value.Type.SCALAR)) {
                    mem = resource.getScalar().getValue();
                } else {
                    LOGGER.severe("Mem resource was not a scalar: " + resource.getType().toString());
                }
            } else if (resource.getName().equals("disk")) {
                LOGGER.fine("Ignoring disk resources from offer");
            } else if (resource.getName().equals("ports")) {
                if (resource.getType().equals(Value.Type.RANGES)) {
                    ports = resource.getRanges().getRangeList();
                } else {
                    LOGGER.severe("Ports resource was not a range: " + resource.getType().toString());
                }
            } else {
                LOGGER.warning("Ignoring unknown resource type: " + resource.getName());
            }
        }

        if (cpus < 0) LOGGER.fine("No cpus resource present");
        if (mem < 0)  LOGGER.fine("No mem resource present");

        MesosSlaveInfo.ContainerInfo containerInfo = request.request.slaveInfo.getContainerInfo();

        boolean hasPortMappings = containerInfo != null ? containerInfo.hasPortMappings() : false;

        boolean hasPortResources = ports != null && !ports.isEmpty();

        if (hasPortMappings && !hasPortResources) {
            LOGGER.severe("No ports resource present");
        }

        // Check for sufficient cpu and memory resources in the offer.
        double requestedCpus = request.request.cpus;
        double requestedMem = (1 + JVM_MEM_OVERHEAD_FACTOR) * request.request.mem;
        // Get matching slave attribute for this label.
        JSONObject slaveAttributes = getMesosCloud().getSlaveAttributeForLabel(request.request.slaveInfo.getLabelString());

        if (requestedCpus <= cpus
                && requestedMem <= mem
                && !(hasPortMappings && !hasPortResources)
                && slaveAttributesMatch(offer, slaveAttributes)) {
            return true;
        } else {
            String requestedPorts = containerInfo != null
                    ? StringUtils.join(containerInfo.getPortMappings().toArray(), "/")
                    : "";

            LOGGER.info(
                    "Offer not sufficient for slave request:\n" +
                            offer.getResourcesList().toString() +
                            "\n" + offer.getAttributesList().toString() +
                            "\nRequested for Jenkins slave:\n" +
                            "  cpus:  " + requestedCpus + "\n" +
                            "  mem:   " + requestedMem + "\n" +
                            "  ports: " + requestedPorts + "\n" +
                            "  attributes:  " + (slaveAttributes == null ? ""  : slaveAttributes.toString()));
            return false;
        }
    }

    private boolean buildsInQueue(hudson.model.Queue queue) {
        hudson.model.Queue.Item[] items =  queue.getItems();
        if(items != null) {
            for(hudson.model.Queue.Item item: items) {
                // Check and return if there is an item in jenkins queue for which this MesosCloud can provsion a slave
                if(mesosCloud.canProvision(item.getAssignedLabel())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks whether the cloud Mesos slave attributes match those from the Mesos offer.
     *
     * @param offer Mesos offer data object.
     * @return true if all the offer attributes match and false if not.
     */
    private boolean slaveAttributesMatch(Offer offer, JSONObject slaveAttributes) {

        //Accept any and all Mesos slave offers by default.
        boolean slaveTypeMatch = true;

        //Collect the list of attributes from the offer as key-value pairs
        Map<String, String> attributesMap = new HashMap<String, String>();
        for (Attribute attribute : offer.getAttributesList()) {
            attributesMap.put(attribute.getName(), attribute.getText().getValue());
        }

        if (slaveAttributes != null && slaveAttributes.size() > 0) {

            //Iterate over the cloud attributes to see if they exist in the offer attributes list.
            Iterator iterator = slaveAttributes.keys();
            while (iterator.hasNext()) {

                String key = (String) iterator.next();

                //If there is a single absent attribute then we should reject this offer.
                if (!(attributesMap.containsKey(key) && attributesMap.get(key).toString().equals(slaveAttributes.getString(key)))) {
                    slaveTypeMatch = false;
                    break;
                }
            }
        }

        return slaveTypeMatch;
    }

    @VisibleForTesting
    void setDriver(SchedulerDriver driver) {
        this.driver = driver;
    }

    @VisibleForTesting
    String findRoleForPorts(Offer offer) {

        String role = MESOS_DEFAULT_ROLE;
        // Locate the port resource in the offer
        for (Resource resource : offer.getResourcesList()) {
            if (resource.getName().equals(PORT_RESOURCE_NAME)) {
                role = resource.getRole();

            }
        }
        return role;
    }

    @VisibleForTesting
    SortedSet<Long> findPortsToUse(Offer offer, int maxCount) {
        SortedSet<Long> portsToUse = new TreeSet<Long>();
        List<Value.Range> portRangesList = null;

        // Locate the port resource in the offer
        for (Resource resource : offer.getResourcesList()) {
            if (resource.getName().equals(PORT_RESOURCE_NAME)) {
                portRangesList = resource.getRanges().getRangeList();
                break;
            }
        }

        LOGGER.fine("portRangesList=" + portRangesList);

        /**
         * We need to find maxCount ports to use.
         * We are provided a list of port ranges to use
         * We are assured by the offer check that we have enough ports to use
         */
        // Check this port range for ports that we can use
        if (portRangesList != null) {
            for (Value.Range currentPortRange : portRangesList) {
                // Check each port until we reach the end of the current range
                long begin = currentPortRange.getBegin();
                long end = currentPortRange.getEnd();
                for (long candidatePort = begin; candidatePort <= end && portsToUse.size() < maxCount; candidatePort++) {
                    portsToUse.add(candidatePort);
                }
            }
        }

        return portsToUse;
    }

    private void createMesosTask(Offer offer, Request request) {
        final String slaveName = request.request.slave.name;
        TaskID taskId = TaskID.newBuilder().setValue(slaveName).build();

        LOGGER.fine("Launching task " + taskId.getValue() + " with URI " +
                joinPaths(jenkinsMaster, SLAVE_JAR_URI_SUFFIX));

        if (isExistingTask(taskId)) {
            // TODO: This almost certainly is causing additional latency in the system.
            Metrics.metricRegistry().meter("mesos.scheduler.existing.task").mark();
            declineShort(offer);
            return;
        }

        Jenkins jenkins = getJenkins();
        for (final Computer computer : jenkins.getComputers()) {
            if (!MesosComputer.class.isInstance(computer)) {
                LOGGER.finer("Not a mesos computer, skipping");
                continue;
            }

            MesosComputer mesosComputer = (MesosComputer) computer;
            MesosSlave mesosSlave = mesosComputer.getNode();

            if (taskId.getValue().equals(computer.getName()) && mesosSlave.isPendingDelete()) {
                LOGGER.info("This mesos task " + taskId.getValue() + " is pending deletion. Not launching another task");
                declineShort(offer);
                return;
            }
        }

        CommandInfo.Builder commandBuilder = getCommandInfoBuilder(request);
        TaskInfo.Builder taskBuilder = getTaskInfoBuilder(offer, request, taskId, commandBuilder);

        LOGGER.info(String.format("ContainerInfo: %s", request.request.slaveInfo.getContainerInfo()));

        if (request.request.slaveInfo.getContainerInfo() != null) {
            getContainerInfoBuilder(offer, request, slaveName, taskBuilder);
        }

        List<TaskInfo> tasks = new ArrayList<TaskInfo>();
        tasks.add(taskBuilder.build());

        Metrics.metricRegistry().counter("mesos.scheduler.operation.launch").inc(tasks.size());
        Filters filters = Filters.newBuilder().setRefuseSeconds(1).build();

        for (TaskInfo taskInfo : tasks) {
            LOGGER.info(String.format("Launching TaskInfo: %s", TextFormat.shortDebugString(taskInfo)));
        }

        request.request.mesosSlave.provisionedToMesos();
        LOGGER.info(String.format("Slave %s now being provisioned by Mesos", request.request.mesosSlave.getUuid()));

        driver.launchTasks(offer.getId(), tasks, filters);

        results.put(taskId, new Result(request.result, new Mesos.JenkinsSlave(offer.getSlaveId()
                .getValue())));
        finishedTasks.add(taskId);
    }

    @NonNull
    private static Jenkins getJenkins() {
        Jenkins jenkins = Jenkins.getInstance();
        if (jenkins == null) {
            throw new IllegalStateException("Jenkins is null");
        }
        return jenkins;
    }

    private void detectAndAddAdditionalURIs(Request request, CommandInfo.Builder commandBuilder) {

        if (request.request.slaveInfo.getAdditionalURIs() != null) {
            for (MesosSlaveInfo.URI uri : request.request.slaveInfo.getAdditionalURIs()) {
                commandBuilder.addUris(
                        CommandInfo.URI.newBuilder().setValue(
                                uri.getValue()).setExecutable(uri.isExecutable()).setExtract(uri.isExtract()));
            }
        }
    }

    private TaskInfo.Builder getTaskInfoBuilder(Offer offer, Request request, TaskID taskId, CommandInfo.Builder commandBuilder) {
        TaskInfo.Builder builder = TaskInfo.newBuilder()
                .setName("task " + taskId.getValue())
                .setTaskId(taskId)
                .setSlaveId(offer.getSlaveId())
                .setCommand(commandBuilder.build());

        double cpusNeeded = request.request.cpus;
        double memNeeded = (1 + JVM_MEM_OVERHEAD_FACTOR) * request.request.mem;
        double diskNeeded = request.request.diskNeeded;

        for (Resource r : offer.getResourcesList()) {
            if (r.getName().equals("cpus") && cpusNeeded > 0) {
                double cpus = Math.min(r.getScalar().getValue(), cpusNeeded);
                builder.addResources(
                        Resource
                                .newBuilder()
                                .setName("cpus")
                                .setType(Value.Type.SCALAR)
                                .setRole(r.getRole())
                                .setScalar(
                                        Value.Scalar.newBuilder()
                                                .setValue(cpus).build()).build());
                cpusNeeded -= cpus;
            } else if (r.getName().equals("mem") && memNeeded > 0) {
                double mem = Math.min(r.getScalar().getValue(), memNeeded);
                builder.addResources(
                        Resource
                                .newBuilder()
                                .setName("mem")
                                .setType(Value.Type.SCALAR)
                                .setRole(r.getRole())
                                .setScalar(
                                        Value.Scalar.newBuilder()
                                                .setValue(mem).build()).build());
                memNeeded -= mem;
            } else if (r.getName().equals("disk") && diskNeeded > 0) {
                double disk = Math.min(r.getScalar().getValue(), diskNeeded);
                builder.addResources(
                        Resource
                                .newBuilder()
                                .setName("disk")
                                .setType(Value.Type.SCALAR)
                                .setRole(r.getRole())
                                .setScalar(
                                        Value.Scalar.newBuilder()
                                                .setValue(disk).build()).build());

            }
            else if (cpusNeeded == 0 && memNeeded == 0 && diskNeeded == 0) {
                break;
            }
        }
        return builder;
    }

    private void getContainerInfoBuilder(Offer offer, Request request, String slaveName, TaskInfo.Builder taskBuilder) {
        MesosSlaveInfo.ContainerInfo containerInfo = request.request.slaveInfo.getContainerInfo();
        ContainerInfo.Type containerType = ContainerInfo.Type.valueOf(containerInfo.getType());

        ContainerInfo.Builder containerInfoBuilder = ContainerInfo.newBuilder().setType(containerType);

        switch(containerType) {
            case DOCKER:
                LOGGER.info("Launching in Docker Mode:" + containerInfo.getDockerImage());
                DockerInfo.Builder dockerInfoBuilder = DockerInfo.newBuilder() //
                        .setImage(containerInfo.getDockerImage())
                        .setPrivileged(containerInfo.getDockerPrivilegedMode())
                        .setForcePullImage(containerInfo.getDockerForcePullImage());

                if (containerInfo.getParameters() != null) {
                    for (MesosSlaveInfo.Parameter parameter : containerInfo.getParameters()) {
                        LOGGER.info("Adding Docker parameter '" + parameter.getKey() + ":" + parameter.getValue() + "'");
                        dockerInfoBuilder.addParameters(Parameter.newBuilder().setKey(parameter.getKey()).setValue(parameter.getValue()).build());
                    }
                }

                String networking = request.request.slaveInfo.getContainerInfo().getNetworking();
                dockerInfoBuilder.setNetwork(Network.valueOf(networking));

                //  https://github.com/jenkinsci/mesos-plugin/issues/109
                if (dockerInfoBuilder.getNetwork() != Network.HOST) {
                    containerInfoBuilder.setHostname(slaveName);
                }

                if (request.request.slaveInfo.getContainerInfo().hasPortMappings()) {
                    List<MesosSlaveInfo.PortMapping> portMappings = request.request.slaveInfo.getContainerInfo().getPortMappings();
                    Set<Long> portsToUse = findPortsToUse(offer, portMappings.size());
                    String roleToUse = findRoleForPorts(offer);
                    Iterator<Long> iterator = portsToUse.iterator();
                    Value.Ranges.Builder portRangesBuilder = Value.Ranges.newBuilder();

                    for (MesosSlaveInfo.PortMapping portMapping : portMappings) {
                        PortMapping.Builder portMappingBuilder = PortMapping.newBuilder() //
                                .setContainerPort(portMapping.getContainerPort()) //
                                .setProtocol(portMapping.getProtocol());

                        Long portToUse = portMapping.getHostPort() == null ? iterator.next() : Long.valueOf(portMapping.getHostPort());

                        portMappingBuilder.setHostPort(portToUse.intValue());

                        portRangesBuilder.addRange(
                                Value.Range
                                        .newBuilder()
                                        .setBegin(portToUse)
                                        .setEnd(portToUse)
                        );

                        LOGGER.finest("Adding portMapping: " + portMapping);
                        dockerInfoBuilder.addPortMappings(portMappingBuilder);
                    }

                    taskBuilder.addResources(
                            Resource
                                    .newBuilder()
                                    .setName("ports")
                                    .setType(Value.Type.RANGES)
                                    .setRole(roleToUse)
                                    .setRanges(portRangesBuilder)
                    );
                } else {
                    LOGGER.fine("No portMappings found");
                }

                containerInfoBuilder.setDocker(dockerInfoBuilder);
                break;
            default:
                LOGGER.warning("Unknown container type:" + containerInfo.getType());
        }

        if (containerInfo.getVolumes() != null) {
            for (MesosSlaveInfo.Volume volume : containerInfo.getVolumes()) {
                LOGGER.info("Adding volume '" + volume.getContainerPath() + "'");
                Volume.Builder volumeBuilder = Volume.newBuilder()
                        .setContainerPath(volume.getContainerPath())
                        .setMode(volume.isReadOnly() ? Mode.RO : Mode.RW);
                if (!volume.getHostPath().isEmpty()) {
                    volumeBuilder.setHostPath(volume.getHostPath());
                }
                containerInfoBuilder.addVolumes(volumeBuilder.build());
            }
        }

        if (containerInfo.hasNetworkInfos()) {
            for (MesosSlaveInfo.NetworkInfo networkInfo : containerInfo.getNetworkInfos()) {

                NetworkInfo.Builder networkInfoBuilder = NetworkInfo.newBuilder();

                if (networkInfo.hasNetworkName()) {
                    //Add the virtual network specified, trimming edges for whitespace
                    networkInfoBuilder.setName(networkInfo.getNetworkName().trim());
                    LOGGER.info("Launching container on network " + networkInfo.getNetworkName() );
                }

                containerInfoBuilder.addNetworkInfos(networkInfoBuilder.build());
            }
        }

        taskBuilder.setContainer(containerInfoBuilder.build());
    }

    @VisibleForTesting
    CommandInfo.Builder getCommandInfoBuilder(Request request) {
        CommandInfo.Builder commandBuilder = getBaseCommandBuilder(request);
        detectAndAddAdditionalURIs(request, commandBuilder);
        return commandBuilder;
    }

    String generateJenkinsCommand2Run(int jvmMem,String jvmArgString,String jnlpArgString,String slaveName) {

        return String.format(SLAVE_COMMAND_FORMAT,
                jvmMem,
                jvmArgString,
                jnlpArgString,
                getJnlpSecret(slaveName),
                getJnlpUrl(slaveName));
    }

    private CommandInfo.Builder getBaseCommandBuilder(Request request) {

        CommandInfo.Builder commandBuilder = CommandInfo.newBuilder();
        String jenkinsCommand2Run = generateJenkinsCommand2Run(
                request.request.slaveInfo.getSlaveMem(),
                request.request.slaveInfo.getJvmArgs(),
                request.request.slaveInfo.getJnlpArgs(),
                request.request.slave.name);

        if (request.request.slaveInfo.getContainerInfo() != null &&
                request.request.slaveInfo.getContainerInfo().getUseCustomDockerCommandShell()) {
            // Ref http://mesos.apache.org/documentation/latest/upgrades
            // regarding setting the shell value, and the impact on the command to be
            // launched
            String customShell = request.request.slaveInfo.getContainerInfo().getCustomDockerCommandShell();
            if (StringUtils.stripToNull(customShell)==null) {
                throw new IllegalArgumentException("Invalid custom shell argument supplied  ");
            }

            LOGGER.fine( String.format( "About to use custom shell: %s " , customShell));
            commandBuilder.setShell(false);
            commandBuilder.setValue(customShell);
            List args = new ArrayList();
            args.add(jenkinsCommand2Run);
            commandBuilder.addAllArguments( args );

        } else {
            LOGGER.fine("About to use default shell ....");
            commandBuilder.setValue(jenkinsCommand2Run);
        }

        commandBuilder.addUris(
                CommandInfo.URI.newBuilder().setValue(
                        joinPaths(jenkinsMaster, SLAVE_JAR_URI_SUFFIX)).setExecutable(false).setExtract(false));
        return commandBuilder;
    }

    /**
     * Checks if the given taskId already exists or just finished running. If it has, then refuse the offer.
     * @param taskId The task id
     * @return True if the task already exists, false otherwise
     */
    @VisibleForTesting
    boolean isExistingTask(TaskID taskId) {
        // If the task has already been queued, don't launch it again
        if (results.containsKey(taskId)) {
            LOGGER.info("Task " + taskId.getValue() + " has already been launched, ignoring and refusing offer");
            return true;
        }

        // If the task has already finished, then do not start it up again even if we are offered it
        if (finishedTasks.contains(taskId)) {
            LOGGER.info("Task " + taskId.getValue() + " has already finished. Ignoring and refusing offer");
            return true;
        }

        return false;
    }

    @Override
    public void offerRescinded(SchedulerDriver driver, OfferID offerId) {
        LOGGER.info("Rescinded offer " + offerId);
        offerQueue.remove(offerId);
    }

    @Override
    public void statusUpdate(SchedulerDriver driver, TaskStatus status) {
        TaskID taskId = status.getTaskId();
        LOGGER.fine("Status update: task " + taskId + " is in state " + status.getState() +
                (status.hasMessage() ? " with message '" + status.getMessage() + "'" : ""));

        if (!results.containsKey(taskId)) {
            // The task might not be present in the 'results' map if this is a duplicate terminal
            // update.
            LOGGER.fine("Ignoring status update " + status.getState() + " for unknown task " + taskId);
            return;
        }

        Result result = results.get(taskId);
        boolean terminalState = false;

        switch (status.getState()) {
            case TASK_STAGING:
            case TASK_STARTING:
                break;
            case TASK_RUNNING:
                result.result.running(result.slave);
                break;
            case TASK_FINISHED:
                result.result.finished(result.slave);
                terminalState = true;
                break;
            case TASK_ERROR:
            case TASK_FAILED:
            case TASK_KILLED:
            case TASK_LOST:
                result.result.failed(result.slave);
                terminalState = true;
                break;
            default:
                throw new IllegalStateException("Invalid State: " + status.getState());
        }

        if (terminalState) {
            results.remove(taskId);
        }

        if (mesosCloud.isOnDemandRegistration()) {
            supervise();
        }
    }

    @Override
    public void frameworkMessage(SchedulerDriver driver, ExecutorID executorId,
                                 SlaveID slaveId, byte[] data) {
        LOGGER.info("Received framework message from executor " + executorId
                + " of slave " + slaveId);
    }

    @Override
    public void slaveLost(SchedulerDriver driver, SlaveID slaveId) {
        LOGGER.info("Slave " + slaveId + " lost!");
    }

    @Override
    public void executorLost(SchedulerDriver driver, ExecutorID executorId,
                             SlaveID slaveId, int status) {
        LOGGER.info("Executor " + executorId + " of slave " + slaveId + " lost!");
    }

    @Override
    public void error(SchedulerDriver driver, String message) {
        LOGGER.severe(message);
    }

    /**
     * @return the mesosCloud
     */
    private MesosCloud getMesosCloud() {
        return mesosCloud;
    }

    /**
     * @param mesosCloud the mesosCloud to set
     */
    protected void setMesosCloud(MesosCloud mesosCloud) {
        this.mesosCloud = mesosCloud;
    }

    private static class Result {
        private final Mesos.SlaveResult result;
        private final Mesos.JenkinsSlave slave;

        private Result(Mesos.SlaveResult result, Mesos.JenkinsSlave slave) {
            this.result = result;
            this.slave = slave;
        }
    }

    @VisibleForTesting
    static class Request {
        private final Mesos.SlaveRequest request;
        private final Mesos.SlaveResult result;

        public Request(Mesos.SlaveRequest request, Mesos.SlaveResult result) {
            this.request = request;
            this.result = result;
        }
    }

    public int getNumberofPendingTasks() {
        return requests.size();
    }

    public int getNumberOfActiveTasks() {
        return results.size();
    }

    public void clearResults() {
        results.clear();
    }

    /**
     * Disconnect framework, if we don't have active mesos slaves. Also, make
     * sure JenkinsScheduler's request queue is empty.
     */
    public static void supervise() {
        try {
            SUPERVISOR_LOCK.lock();
            Collection<Mesos> clouds = Mesos.getAllClouds();
            for (Mesos cloud : clouds) {
                try {
                    JenkinsScheduler scheduler = (JenkinsScheduler) cloud.getScheduler();
                    if (scheduler != null) {
                        boolean pendingTasks = (scheduler.getNumberofPendingTasks() > 0);
                        boolean activeSlaves = false;
                        boolean activeTasks = (scheduler.getNumberOfActiveTasks() > 0);
                        List<Node> slaveNodes = getJenkins().getNodes();
                        for (Node node : slaveNodes) {
                            if (node instanceof MesosSlave) {
                                activeSlaves = true;
                                break;
                            }
                        }
                        // If there are no active slaves, we should clear up results.
                        if (!activeSlaves) {
                            scheduler.clearResults();
                            activeTasks = false;
                        }
                        LOGGER.fine("Active slaves: " + activeSlaves
                                + " | Pending tasks: " + pendingTasks + " | Active tasks: " + activeTasks);
                        if (!activeTasks && !activeSlaves && !pendingTasks) {
                            LOGGER.info("No active tasks, or slaves or pending slave requests. Stopping the scheduler.");
                            cloud.stopScheduler();
                        }
                    } else {
                        LOGGER.info("Scheduler already stopped. NOOP.");
                    }
                } catch (Exception e) {
                    LOGGER.info("Exception: " + e);
                }
            }
        } finally {
            SUPERVISOR_LOCK.unlock();
        }
    }

    public String getJenkinsMaster() {
        return jenkinsMaster;
    }

    public void setJenkinsMaster(String jenkinsMaster) {
        this.jenkinsMaster = jenkinsMaster;
    }
}
