package org.jenkinsci.plugins.mesos;

import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Descriptor.FormException;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Node.Mode;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.util.DescribableList;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;
import org.apache.commons.lang.builder.ReflectionToStringBuilder;
import org.apache.mesos.Protos.ContainerInfo.DockerInfo.Network;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.CheckForNull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MesosSlaveInfo extends AbstractDescribableImpl<MesosSlaveInfo> {
    @Extension
    public static class DescriptorImpl extends Descriptor<MesosSlaveInfo> {
        public FormValidation doCheckMinExecutors(@QueryParameter String minExecutors, @QueryParameter String maxExecutors) {
            int minExecutorsVal = Integer.parseInt(minExecutors);
            int maxExecutorsVal = Integer.parseInt(maxExecutors);

            if (minExecutorsVal < 1) {
                return FormValidation.error("minExecutors must at least be equal to 1.");
            } else if (minExecutorsVal > maxExecutorsVal) {
                return FormValidation.error("minExecutors must be lower than maxExecutors.");
            } else {
                return FormValidation.ok();
            }
        }

        public FormValidation doCheckMaxExecutors(@QueryParameter String minExecutors, @QueryParameter String maxExecutors) {
            int minExecutorsVal = Integer.parseInt(minExecutors);
            int maxExecutorsVal = Integer.parseInt(maxExecutors);

            if (maxExecutorsVal < 1) {
                return FormValidation.error("maxExecutors must at least be equal to 1.");
            } else if (maxExecutorsVal < minExecutorsVal) {
                return FormValidation.error("maxExecutors must be higher than minExecutors.");
            } else {
                return FormValidation.ok();
            }
        }

        public String getDisplayName() {
            return "";
        }

        public Class<? extends Node> getNodeClass() {
            return MesosSlave.class;
        }
    }

    private static final String DEFAULT_JVM_ARGS = "-Xms16m -XX:+UseConcMarkSweepGC -Djava.net.preferIPv4Stack=true";
    private static final String JVM_ARGS_PATTERN = "-Xmx.+ ";
    private static final String CUSTOM_IMAGE_SEPARATOR = ":";
    private static final Pattern CUSTOM_IMAGE_FROM_LABEL_PATTERN = Pattern.compile(CUSTOM_IMAGE_SEPARATOR + "([\\w\\.\\-/:]+[\\w])");
    private final double slaveCpus;
    private final double diskNeeded; //MB
    private final int slaveMem; // MB.
    private final double executorCpus;
    private /*almost final*/ int minExecutors;
    private final int maxExecutors;
    private final int executorMem; // MB.
    private final String remoteFSRoot;
    private final int idleTerminationMinutes;
    private final String jvmArgs;
    private final String jnlpArgs;
    private final boolean defaultSlave;
    // Slave attributes JSON representation.
    private String slaveAttributesString;
    @Deprecated
    private transient JSONObject slaveAttributes;
    private final ContainerInfo containerInfo;
    private final List<URI> additionalURIs;
    private final Mode mode;
    private /*almost final*/ DescribableList<NodeProperty<?>, NodePropertyDescriptor> nodeProperties = new DescribableList<NodeProperty<?>, NodePropertyDescriptor>(Jenkins.getInstance());

    @CheckForNull
    private String labelString;

    private static final Logger LOGGER = Logger.getLogger(MesosSlaveInfo.class
            .getName());

    @Override
    public boolean equals(Object o) {
        return EqualsBuilder.reflectionEquals(this, o);
    }

    @Override
    public int hashCode() {
        return HashCodeBuilder.reflectionHashCode(this);
    }

    @DataBoundConstructor
    public MesosSlaveInfo(
            String labelString,
            Mode mode,
            String slaveCpus,
            String slaveMem,
            String minExecutors,
            String maxExecutors,
            String executorCpus,
            String diskNeeded,
            String executorMem,
            String remoteFSRoot,
            String idleTerminationMinutes,
            String slaveAttributes,
            String jvmArgs,
            String jnlpArgs,
            String defaultSlave,
            ContainerInfo containerInfo,
            List<URI> additionalURIs,
            List<? extends NodeProperty<?>> nodeProperties)
            throws IOException, NumberFormatException {
        // Parse the attributes provided from the cloud config
        this(
                Util.fixEmptyAndTrim(labelString),
                mode != null ? mode : Mode.NORMAL,
                Double.parseDouble(slaveCpus),
                Integer.parseInt(slaveMem),
                Integer.parseInt(minExecutors),
                Integer.parseInt(maxExecutors),
                Double.parseDouble(executorCpus),
                Double.parseDouble(diskNeeded),
                Integer.parseInt(executorMem),
                StringUtils.isNotBlank(remoteFSRoot) ? remoteFSRoot.trim() : "jenkins",
                Integer.parseInt(idleTerminationMinutes),
                parseSlaveAttributes(slaveAttributes),
                StringUtils.isNotBlank(jvmArgs) ? cleanseJvmArgs(jvmArgs) : DEFAULT_JVM_ARGS,
                StringUtils.isNotBlank(jnlpArgs) ? jnlpArgs : "",
                Boolean.valueOf(defaultSlave),
                containerInfo,
                additionalURIs,
                nodeProperties);
    }

    public MesosSlaveInfo(
            String labelString,
            Mode mode,
            double slaveCpus,
            int slaveMem,
            int minExecutors,
            int maxExecutors,
            double executorCpus,
            double diskNeeded,
            int executorMem,
            String remoteFSRoot,
            int idleTerminationMinutes,
            JSONObject slaveAttributes,
            String jvmArgs,
            String jnlpArgs,
            Boolean defaultSlave,
            ContainerInfo containerInfo,
            List<URI> additionalURIs,
            List<? extends NodeProperty<?>> nodeProperties)
            throws IOException, NumberFormatException {
        this.labelString = labelString;
        this.mode = mode;
        this.slaveCpus = slaveCpus;
        this.slaveMem = slaveMem;
        this.minExecutors = minExecutors < 1 ? 1 : minExecutors; // Ensure minExecutors is at least equal to 1
        this.maxExecutors = maxExecutors;
        this.executorCpus = executorCpus;
        this.diskNeeded = diskNeeded;
        this.executorMem = executorMem;
        this.remoteFSRoot = remoteFSRoot;
        this.idleTerminationMinutes = idleTerminationMinutes;
        this.slaveAttributesString = slaveAttributes != null ? slaveAttributes.toString() : null;
        this.jvmArgs = jvmArgs;
        this.jnlpArgs = jnlpArgs;
        this.defaultSlave = defaultSlave;
        this.containerInfo = containerInfo;
        this.additionalURIs = additionalURIs;
        this.nodeProperties.replaceBy(nodeProperties == null ? new ArrayList<NodeProperty<?>>() : nodeProperties);
    }

    private static JSONObject parseSlaveAttributes(String slaveAttributes) {
        if (StringUtils.isNotBlank(slaveAttributes)) {
            try {
                return (JSONObject) JSONSerializer.toJSON(slaveAttributes);
            } catch (JSONException e) {
                LOGGER.warning("Ignoring Mesos slave attributes JSON due to parsing error : " + slaveAttributes);
            }
        }

        return null;
    }

    public double getdiskNeeded() {
        return diskNeeded;
    }

    public MesosSlaveInfo copyWithDockerImage(String label, String dockerImage) {
        LOGGER.fine(String.format("Customize mesos slave %s using docker image %s", this.getLabelString(), dockerImage));

        try {
            return new MesosSlaveInfo(
                    label,
                    mode,
                    slaveCpus,
                    slaveMem,
                    minExecutors,
                    maxExecutors,
                    executorCpus,
                    diskNeeded,
                    executorMem,
                    remoteFSRoot,
                    idleTerminationMinutes,
                    parseSlaveAttributes(slaveAttributesString),
                    jvmArgs,
                    jnlpArgs,
                    defaultSlave,
                    containerInfo.copyWithDockerImage(dockerImage),
                    additionalURIs,
                    nodeProperties
            );
        } catch (Descriptor.FormException e) {
            LOGGER.log(Level.WARNING, "Failed to create customized mesos container info", e);
            return null;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to create customized mesos slave info", e);
            return null;
        }
    }

    @CheckForNull
    public String getLabelString() {
        return labelString;
    }

    public Mode getMode() {
        return mode;
    }

    public double getExecutorCpus() {
        return executorCpus;
    }

    public double getSlaveCpus() {
        return slaveCpus;
    }

    public int getSlaveMem() {
        return slaveMem;
    }

    public int getMinExecutors() {
        return minExecutors;
    }

    public int getMaxExecutors() {
        return maxExecutors;
    }

    public int getExecutorMem() {
        return executorMem;
    }

    public String getRemoteFSRoot() {
        return remoteFSRoot;
    }

    public int getIdleTerminationMinutes() {
        return idleTerminationMinutes;
    }

    public JSONObject getSlaveAttributes() {
        return parseSlaveAttributes(slaveAttributesString);
    }

    public String getJvmArgs() {
        return jvmArgs;
    }

    public String getJnlpArgs() {
        return jnlpArgs;
    }

    public boolean isDefaultSlave() {
        return defaultSlave;
    }

    public ContainerInfo getContainerInfo() {
        return containerInfo;
    }

    public List<URI> getAdditionalURIs() {
        return additionalURIs;
    }

    public DescribableList<NodeProperty<?>, NodePropertyDescriptor> getNodeProperties() {
        assert nodeProperties != null;
        return nodeProperties;
    }

    /**
     * Removes any additional {@code -Xmx} JVM args from the provided JVM
     * arguments. This is to ensure that the logic that sets the maximum heap
     * sized based on the memory available to the slave is not overriden by a
     * value provided via the configuration that may not work with the current
     * slave's configuration.
     *
     * @param jvmArgs the string of JVM arguments.
     * @return The cleansed JVM argument string.
     */
    private static String cleanseJvmArgs(final String jvmArgs) {
        return jvmArgs.replaceAll(JVM_ARGS_PATTERN, "");
    }

    /**
     * Check if the label in the slave matches the provided label, either both are null or are the same.
     *
     * @param label
     * @return Whether the slave label matches.
     */
    public boolean matchesLabel(@CheckForNull Label label) {

        if (label == null || getLabelString() == null) {
            return label == null && getLabelString() == null;
        }

        if (label.matches(Label.parse(getLabelString()))) {
            return true;
        }

        if (containerInfo == null || !containerInfo.getDockerImageCustomizable()) {
            return false;
        }

        String customImage = getCustomImage(label);
        return customImage != null && getLabelWithoutCustomImage(label, customImage).matches(Label.parse(getLabelString()));
    }

    public Object readResolve() {
        if (nodeProperties == null) {
            nodeProperties = new DescribableList<NodeProperty<?>, NodePropertyDescriptor>(Jenkins.getInstance());
        }
        if (minExecutors == 0) {
            this.minExecutors = 1;
        }
        if (slaveAttributes != null) {
            slaveAttributesString = slaveAttributes.toString();
            slaveAttributes = null;
        }
        return this;
    }

    private static String getCustomImage(Label label) {
        Matcher m = CUSTOM_IMAGE_FROM_LABEL_PATTERN.matcher(label.toString());

        if (m.find()) {
            return m.group(1);
        }

        return null;
    }

    private static Label getLabelWithoutCustomImage(Label label, String customDockerImage) {
        return Label.get(label.toString().replace(CUSTOM_IMAGE_SEPARATOR + customDockerImage, ""));
    }

    public MesosSlaveInfo getMesosSlaveInfoForLabel(Label label) {
        if (!matchesLabel(label)) {
            return null;
        }

        if (label == null) {
            if (getLabelString() == null) {
                return this;
            } else {
                return null;
            }
        }

        if (label.matches(Label.parse(getLabelString()))) {
            return this;
        }

        if (!containerInfo.getDockerImageCustomizable()) {
            return null;
        }

        String customImage = getCustomImage(label);
        if (customImage == null) {
            return null;
        }

        return copyWithDockerImage(label.toString(), customImage);
    }

    public static class ExternalContainerInfo {
        private final String image;
        private final String options;

        @DataBoundConstructor
        public ExternalContainerInfo(String image, String options) {
            this.image = image;
            this.options = options;
        }

        public String getOptions() {
            return options;
        }

        public String getImage() {
            return image;
        }

        @Override
        public boolean equals(Object o) {
            return EqualsBuilder.reflectionEquals(this, o);
        }

        @Override
        public int hashCode() {
            return HashCodeBuilder.reflectionHashCode(this);
        }
    }

    public static class ContainerInfo extends AbstractDescribableImpl<ContainerInfo> {
        @Extension
        public static class DescriptorImpl extends Descriptor<ContainerInfo> {
            public String getDisplayName() {
                return "";
            }
        }

        private final String type;
        private final String dockerImage;
        private final List<Volume> volumes;
        private final List<Parameter> parameters;
        private final String networking;
        private static final String DEFAULT_NETWORKING = Network.BRIDGE.name();
        private final List<PortMapping> portMappings;
        private final List<NetworkInfo> networkInfos;
        private final boolean useCustomDockerCommandShell;
        private final String customDockerCommandShell;
        private final boolean dockerPrivilegedMode;
        private final boolean dockerForcePullImage;
        private final boolean dockerImageCustomizable;

        @DataBoundConstructor
        public ContainerInfo(String type,
                             String dockerImage,
                             boolean dockerPrivilegedMode,
                             boolean dockerForcePullImage,
                             boolean dockerImageCustomizable,
                             boolean useCustomDockerCommandShell,
                             String customDockerCommandShell,
                             List<Volume> volumes,
                             List<Parameter> parameters,
                             String networking,
                             List<PortMapping> portMappings,
                             List<NetworkInfo> networkInfos) throws FormException {
            this.type = type;
            this.dockerImage = dockerImage;
            this.dockerPrivilegedMode = dockerPrivilegedMode;
            this.dockerForcePullImage = dockerForcePullImage;
            this.dockerImageCustomizable = dockerImageCustomizable;
            this.useCustomDockerCommandShell = useCustomDockerCommandShell;
            this.customDockerCommandShell = customDockerCommandShell;
            this.volumes = volumes;
            this.parameters = parameters;
            this.networkInfos = networkInfos;

            if (networking == null) {
                this.networking = DEFAULT_NETWORKING;
            } else {
                this.networking = networking;
            }

            if (Network.HOST.equals(Network.valueOf(networking))) {
                this.portMappings = Collections.emptyList();
            } else {
                this.portMappings = portMappings;
            }
        }

        public ContainerInfo copyWithDockerImage(String dockerImage) throws FormException {
            return new ContainerInfo(
                    type,
                    dockerImage,  // custom docker image
                    dockerPrivilegedMode,
                    dockerForcePullImage,
                    dockerImageCustomizable,
                    useCustomDockerCommandShell,
                    customDockerCommandShell,
                    volumes,
                    parameters,
                    networking,
                    portMappings,
                    networkInfos
            );
        }

        public String getType() {
            return type;
        }

        public String getDockerImage() {
            return dockerImage;
        }

        public List<Volume> getVolumes() {
            return volumes;
        }

        public List<Parameter> getParameters() {
            return parameters;
        }

        public List<NetworkInfo> getNetworkInfos() {
            return networkInfos;
        }

        public boolean hasNetworkInfos() {
            return networkInfos != null && !networkInfos.isEmpty();
        }

        public String getNetworking() {
            if (networking != null) {
                return networking;
            } else {
                return DEFAULT_NETWORKING;
            }
        }

        public List<PortMapping> getPortMappings() {
            if (portMappings != null) {
                return portMappings;
            } else {
                return Collections.emptyList();
            }
        }

        public boolean hasPortMappings() {
            return portMappings != null && !portMappings.isEmpty();
        }

        public boolean getDockerPrivilegedMode() {
            return dockerPrivilegedMode;
        }

        public boolean getDockerForcePullImage() {
            return dockerForcePullImage;
        }

        public boolean getDockerImageCustomizable() {
            return dockerImageCustomizable;
        }

        public boolean getUseCustomDockerCommandShell() {
            return useCustomDockerCommandShell;
        }

        public String getCustomDockerCommandShell() {
            return customDockerCommandShell;
        }

        @Override
        public boolean equals(Object o) {
            return EqualsBuilder.reflectionEquals(this, o);
        }

        @Override
        public int hashCode() {
            return HashCodeBuilder.reflectionHashCode(this);
        }

        @Override
        public String toString() {
            return ReflectionToStringBuilder.toString(this);
        }
    }

    public static class Parameter extends AbstractDescribableImpl<Parameter> {
        @Extension
        public static class DescriptorImpl extends Descriptor<Parameter> {
            public String getDisplayName() {
                return "";
            }
        }

        private final String key;
        private final String value;

        @DataBoundConstructor
        public Parameter(String key, String value) {
            this.key = key;
            this.value = value;
        }

        public String getKey() {
            return key;
        }

        public String getValue() {
            return value;
        }

        @Override
        public boolean equals(Object o) {
            return EqualsBuilder.reflectionEquals(this, o);
        }

        @Override
        public int hashCode() {
            return HashCodeBuilder.reflectionHashCode(this);
        }
    }

    public static class PortMapping extends AbstractDescribableImpl<PortMapping> {
        @Extension
        public static class DescriptorImpl extends Descriptor<PortMapping> {
            public String getDisplayName() {
                return "";
            }
        }

        // TODO validate 1 to 65535
        private final Integer containerPort;
        private final Integer hostPort;
        private final String protocol;

        @DataBoundConstructor
        public PortMapping(Integer containerPort, Integer hostPort, String protocol) {
            this.containerPort = containerPort;
            this.hostPort = hostPort;
            this.protocol = protocol;
        }

        public Integer getContainerPort() {
            return containerPort;
        }

        public Integer getHostPort() {
            return hostPort;
        }

        public String getProtocol() {
            return protocol;
        }

        @Override
        public String toString() {
            return (hostPort == null ? 0 : hostPort) + ":" + containerPort;
        }

        @Override
        public boolean equals(Object o) {
            return EqualsBuilder.reflectionEquals(this, o);
        }

        @Override
        public int hashCode() {
            return HashCodeBuilder.reflectionHashCode(this);
        }
    }

    public static class Volume extends AbstractDescribableImpl<Volume> {
        @Extension
        public static class DescriptorImpl extends Descriptor<Volume> {
            public String getDisplayName() {
                return "";
            }
        }

        private final String containerPath;
        private final String hostPath;
        private final boolean readOnly;

        @DataBoundConstructor
        public Volume(String containerPath, String hostPath, boolean readOnly) {
            this.containerPath = containerPath;
            this.hostPath = hostPath;
            this.readOnly = readOnly;
        }

        public String getContainerPath() {
            return containerPath;
        }

        public String getHostPath() {
            return hostPath;
        }

        public boolean isReadOnly() {
            return readOnly;
        }

        @Override
        public boolean equals(Object o) {
            return EqualsBuilder.reflectionEquals(this, o);
        }

        @Override
        public int hashCode() {
            return HashCodeBuilder.reflectionHashCode(this);
        }
    }

    public static class URI extends AbstractDescribableImpl<URI> {
        @Extension
        public static class DescriptorImpl extends Descriptor<URI> {
            public String getDisplayName() {
                return "";
            }
        }

        private final String value;
        private final boolean executable;
        private final boolean extract;

        @DataBoundConstructor
        public URI(String value, boolean executable, boolean extract) {
            this.value = value;
            this.executable = executable;
            this.extract = extract;
        }

        public String getValue() {
            return value;
        }

        public boolean isExecutable() {
            return executable;
        }

        public boolean isExtract() {
            return extract;
        }

        @Override
        public boolean equals(Object o) {
            return EqualsBuilder.reflectionEquals(this, o);
        }

        @Override
        public int hashCode() {
            return HashCodeBuilder.reflectionHashCode(this);
        }
    }

    public static class NetworkInfo extends AbstractDescribableImpl<NetworkInfo> {
        @Extension
        public static class DescriptorImpl extends Descriptor<NetworkInfo> {
            public String getDisplayName() {
                return "";
            }
        }

        private final String networkName;

        @DataBoundConstructor
        public NetworkInfo(String networkName) {
            this.networkName = networkName;
        }

        public String getNetworkName() {
            return networkName;
        }

        public boolean hasNetworkName() {
            return networkName != null && !networkName.isEmpty();
        }

        @Override
        public boolean equals(Object o) {
            return EqualsBuilder.reflectionEquals(this, o);
        }

        @Override
        public int hashCode() {
            return HashCodeBuilder.reflectionHashCode(this);
        }
    }
}
