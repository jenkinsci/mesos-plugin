package org.jenkinsci.plugins.mesos;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.google.common.collect.Lists;
import hudson.model.Node;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

public class MesosCloudTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    /**
     * Test that submitting configuration from UI doesn't change the existing configuration.
     * @throws Exception in case any exception is thrown during configuration
     */
    @Test
    public void configRoundTrip() throws Exception {
        assertTrue(r.jenkins.getPluginManager().getPlugin("mesos").isActive());
        MesosCloud mesosCloud = buildMesosCloud();
        r.jenkins.clouds.add(mesosCloud);
        r.configRoundtrip();
        r.assertEqualDataBoundBeans(mesosCloud, r.jenkins.getCloud("MesosCloud"));
    }

    private MesosCloud buildMesosCloud() {
        StandardUsernamePasswordCredentials credentials = createCredentials();
        List<MesosSlaveInfo> slaveInfos = buildMesosSlaveInfos();
        return new MesosCloud(
                "nativeLibraryPath",
                "master",
                "description",
                "frameworkName",
                "role",
                "slavesUser",
                credentials.getId(),
                null,
                null,
                slaveInfos,
                false,
                true,
                "",
                "100000");
    }

    private StandardUsernamePasswordCredentials createCredentials() {
        // create credentials
        StandardUsernamePasswordCredentials newCredentials = new UsernamePasswordCredentialsImpl(
                CredentialsScope.SYSTEM, null, null, "principal", "secret");
        SystemCredentialsProvider.getInstance().getCredentials().add(newCredentials);
        return newCredentials;
    }

    private List<MesosSlaveInfo> buildMesosSlaveInfos() {
        List<MesosSlaveInfo.URI> uris = buildUris();
        MesosSlaveInfo.ContainerInfo containerInfo = buildContainerInfo();
        return Lists.newArrayList(new MesosSlaveInfo(
                "mesos",
                Node.Mode.NORMAL,
                "0.1",
                "512",
                "2",
                "0.1",
                "128",
                "jenkins",
                "3",
                "",
                "-Xms16m -XX:+UseConcMarkSweepGC -Djava.net.preferIPv4Stack=true",
                "jnlpArgs",
                null,
                containerInfo,
                uris));
    }

    private MesosSlaveInfo.ContainerInfo buildContainerInfo() {
        List<MesosSlaveInfo.Volume> volumes = buildVolumes();
        List<MesosSlaveInfo.Parameter> parameters = buildParameters();
        List<MesosSlaveInfo.PortMapping> portMappings = buildPortMappings();
        return new MesosSlaveInfo.ContainerInfo(
                "DOCKER",
                "dockerImage",
                false,
                false,
                false,
                "customDockerCommandShell",
                volumes,
                parameters,
                "BRIDGE",
                portMappings);
    }

    private List<MesosSlaveInfo.PortMapping> buildPortMappings() {
        return Lists.newArrayList(new MesosSlaveInfo.PortMapping(123, 123, "tcp"));
    }

    private List<MesosSlaveInfo.Parameter> buildParameters() {
        return Lists.newArrayList(new MesosSlaveInfo.Parameter("key", "value"));
    }

    private List<MesosSlaveInfo.Volume> buildVolumes() {
        return Lists.newArrayList(new MesosSlaveInfo.Volume("containerPath", "hostPath", false));
    }

    private ArrayList<MesosSlaveInfo.URI> buildUris() {
        return Lists.newArrayList(new MesosSlaveInfo.URI("uri", false, false));
    }

}
