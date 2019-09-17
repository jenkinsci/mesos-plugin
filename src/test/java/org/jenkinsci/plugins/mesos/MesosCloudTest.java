/*
 * Copyright 2018 CloudBees, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jenkinsci.plugins.mesos;

import hudson.XmlFile;
import hudson.model.Node;
import hudson.slaves.NodeProperty;
import java.io.File;
import java.util.Collections;
import java.util.List;
import jenkins.model.Jenkins;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.Rule;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.recipes.LocalData;

public class MesosCloudTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    @Issue("JENKINS-50303")
    @Test
    public void configRoundtrip() throws Exception {
        String slaveAttributes = "{\"somekey\":\"somevalue\"}";
        MesosCloud cloud = new MesosCloud(
            "<nativeLibraryPath>", "<master>", "<description>", "<frameworkName>", "<role>", "<slavesUser>", "", "<principal>", /* TODO why is secret still in the DBC?? */null,
            Collections.singletonList(new MesosSlaveInfo("<labelString>", Node.Mode.NORMAL, "4", "1024", "1", "1", "1", "0.0", "1024", "<remoteFSRoot>", "1", slaveAttributes, "<jvmArgs>", "<jnlpArgs>", "<defaultSlave>",
                    new MesosSlaveInfo.ContainerInfo(/* not actually used, should really be using f:optionalProperty */"DOCKER", "<dockerImage>", true,true, true, true, true, "<customDockerCommandShell>",
                            Collections.singletonList(new MesosSlaveInfo.Volume("<containerPath>", "<hostPath>", true)),
                            Collections.singletonList(new MesosSlaveInfo.Parameter("<key>", "<value>")),
                            "BRIDGE",
                            Collections.singletonList(new MesosSlaveInfo.PortMapping(23, 46, "udp")),
                            Collections.singletonList(new MesosSlaveInfo.NetworkInfo("<networkName>"))),
                    Collections.singletonList(new MesosSlaveInfo.URI("<value>", true, true)),
                    Collections.<NodeProperty<?>>emptyList())),
            true, true, false, "<jenkinsURL>", "1234", "<cloudID>");
        r.jenkins.clouds.add(cloud);
        r.configRoundtrip();
        r.assertEqualDataBoundBeans(Collections.singletonList(cloud), r.jenkins.clouds);
        assertThat(new XmlFile(Jenkins.XSTREAM, new File(r.jenkins.root, "config.xml")).asString(), containsString(slaveAttributes.replace("\"", "&quot;")));
    }

    @Test
    public void configRoundTripUCR() throws Exception {
        String slaveAttributes = "{\"somekey\":\"somevalue\"}";
        MesosCloud cloud = new MesosCloud(
                "<nativeLibraryPath>", "<master>", "<description>", "<frameworkName>", "<role>", "<slavesUser>", "", "<principal>", /* TODO why is secret still in the DBC?? */null,
                Collections.singletonList(new MesosSlaveInfo("<labelString>", Node.Mode.NORMAL, "4", "1024", "1", "1", "1", "0.0", "1024", "<remoteFSRoot>", "1", slaveAttributes, "<jvmArgs>", "<jnlpArgs>", "<defaultSlave>",
                        new MesosSlaveInfo.ContainerInfo(/* not actually used, should really be using f:optionalProperty */"MESOS", "<dockerImage>", true, true, true, true, true, "<customDockerCommandShell>",
                                Collections.singletonList(new MesosSlaveInfo.Volume("<containerPath>", "<hostPath>", true)),
                                Collections.singletonList(new MesosSlaveInfo.Parameter("<key>", "<value>")),
                                "BRIDGE",
                                Collections.singletonList(new MesosSlaveInfo.PortMapping(23, 46, "udp")),
                                Collections.singletonList(new MesosSlaveInfo.NetworkInfo("<networkName>"))),
                        Collections.singletonList(new MesosSlaveInfo.URI("<value>", true, true)),
                        Collections.<NodeProperty<?>>emptyList())),
                true, true, false, "<jenkinsURL>", "1234", "<cloudID>");
        r.jenkins.clouds.add(cloud);
        r.configRoundtrip();
        r.assertEqualDataBoundBeans(Collections.singletonList(cloud), r.jenkins.clouds);
        assertThat(new XmlFile(Jenkins.XSTREAM, new File(r.jenkins.root, "config.xml")).asString(), containsString(slaveAttributes.replace("\"", "&quot;")));
    }

    @Issue("JENKINS-50303")
    @LocalData
    @Test
    public void oldData() throws Exception {
        assertEquals(1, r.jenkins.clouds.size());
        List<MesosSlaveInfo> slaveInfos = ((MesosCloud) r.jenkins.clouds.get(0)).getSlaveInfos();
        assertEquals(1, slaveInfos.size());
        assertThat(String.valueOf(slaveInfos.get(0).getSlaveAttributes()), anyOf(is("{\"x\":\"y\"}"), is("null")));
    }

}
