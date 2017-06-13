package org.jenkinsci.plugins.mesos;

import antlr.ANTLRException;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.slaves.NodeProperty;
import jenkins.model.Jenkins;
import org.apache.mesos.Protos;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;

import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.when;


@RunWith(PowerMockRunner.class)
@PrepareForTest( { Jenkins.class })
public class MesosSlaveInfoTest {
    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        // Simulate basic Jenkins env
        Jenkins jenkins = Mockito.mock(Jenkins.class);

        when(jenkins.getLabelAtom(any(String.class))).thenAnswer(new Answer<LabelAtom>() {
            @Override
            public LabelAtom answer(InvocationOnMock invocation) throws Throwable {
                return new LabelAtom((String) invocation.getArguments()[0]);
            }
        });

        when(jenkins.getLabel(any(String.class))).thenAnswer(new Answer<LabelAtom>() {
            @Override
            public LabelAtom answer(InvocationOnMock invocation) throws Throwable {
                return new LabelAtom((String) invocation.getArguments()[0]);
            }
        });

        PowerMockito.mockStatic(Jenkins.class);
        Mockito.when(Jenkins.getInstance()).thenReturn(jenkins);
    }


    private MesosSlaveInfo buildMesosSlaveInfo(String label, boolean customizable) throws IOException, Descriptor.FormException {
        MesosSlaveInfo.ContainerInfo container = new MesosSlaveInfo.ContainerInfo(
                "",
                "",
                Boolean.FALSE,
                Boolean.FALSE,
                customizable,
                false,
                "",
                new LinkedList<MesosSlaveInfo.Volume>(),
                new LinkedList<MesosSlaveInfo.Parameter>(),
                Protos.ContainerInfo.DockerInfo.Network.BRIDGE.name(),
                null,
                new LinkedList<MesosSlaveInfo.NetworkInfo>()
        );
        return new MesosSlaveInfo(
                label,
                Node.Mode.EXCLUSIVE,
                "1",
                "1",
                "1",
                "1",
                "1",
                "500",
                "1",
                "",
                "1",
                "",
                "",
                "",
                "false",
                container,
                new LinkedList<MesosSlaveInfo.URI>(),
                new ArrayList<NodeProperty<?>>()
        );
    }

    private static Label getLabel(String name) {
        Iterator<LabelAtom> i = Label.parse(name).iterator();

        return i.hasNext() ? i.next() : null;
    }

    @Test
    public void matchesLabelTest() throws IOException, Descriptor.FormException, ANTLRException {
        assertFalse(buildMesosSlaveInfo("worker", false).matchesLabel(null));
        assertFalse(buildMesosSlaveInfo("worker", false).matchesLabel(getLabel(null)));

        assertFalse(buildMesosSlaveInfo("worker-2", false).matchesLabel(getLabel("worker")));
        assertTrue(buildMesosSlaveInfo("worker", false).matchesLabel(getLabel("worker")));

        assertFalse(buildMesosSlaveInfo("worker-2", true).matchesLabel(getLabel("worker")));
        assertTrue(buildMesosSlaveInfo("worker", true).matchesLabel(getLabel("worker")));

        assertFalse(buildMesosSlaveInfo("worker", false).matchesLabel(getLabel("worker:example-domain.com/name-of-1-image:3.2.r3-version")));
        assertTrue(buildMesosSlaveInfo("worker", true).matchesLabel(getLabel("worker:example-domain.com/name-of-1-image:3.2.r3-version")));
        assertTrue(buildMesosSlaveInfo("worker:example-domain.com/name-of-1-image:3.2.r3-version", false).matchesLabel(getLabel("worker:example-domain.com/name-of-1-image:3.2.r3-version")));
        assertTrue(buildMesosSlaveInfo("worker:example-domain.com/name-of-1-image:3.2.r3-version", true).matchesLabel(getLabel("worker:example-domain.com/name-of-1-image:3.2.r3-version")));

        assertFalse(buildMesosSlaveInfo("worker", false).matchesLabel(getLabel("worker:example-domain.com/name-of-1-image")));
        assertTrue(buildMesosSlaveInfo("worker", true).matchesLabel(getLabel("worker:example-domain.com/name-of-1-image")));
        assertTrue(buildMesosSlaveInfo("worker:example-domain.com/name-of-1-image", true).matchesLabel(getLabel("worker:example-domain.com/name-of-1-image")));
        assertTrue(buildMesosSlaveInfo("worker:example-domain.com/name-of-1-image", false).matchesLabel(getLabel("worker:example-domain.com/name-of-1-image")));


        assertTrue(buildMesosSlaveInfo(null, false).matchesLabel(null));
        assertFalse(buildMesosSlaveInfo("label", false).matchesLabel(null));
        assertFalse(buildMesosSlaveInfo(null, false).matchesLabel(getLabel("label")));

        assertTrue(buildMesosSlaveInfo(null, true).matchesLabel(null));
        assertFalse(buildMesosSlaveInfo("label", true).matchesLabel(null));
        assertFalse(buildMesosSlaveInfo(null, true).matchesLabel(getLabel("label")));
    }

    @Test
    public void getMesosSlaveInfoForLabelTest() throws IOException, Descriptor.FormException, ANTLRException {
        assertEquals("worker", buildMesosSlaveInfo("worker", false).getMesosSlaveInfoForLabel(getLabel("worker")).getLabelString());
        assertEquals("worker", buildMesosSlaveInfo("worker", true).getMesosSlaveInfoForLabel(getLabel("worker")).getLabelString());

        assertNull(buildMesosSlaveInfo("worker", false).getMesosSlaveInfoForLabel(getLabel("worker2")));
        assertNull(buildMesosSlaveInfo("worker", true).getMesosSlaveInfoForLabel(getLabel("worker1")));

        assertNull(buildMesosSlaveInfo("worker", false).getMesosSlaveInfoForLabel(getLabel("worker:example-domain.com/name-of-1-image:3.2.r3-version")));
        assertEquals("worker:example-domain.com/name-of-1-image:3.2.r3-version", buildMesosSlaveInfo("worker", true).getMesosSlaveInfoForLabel(getLabel("worker:example-domain.com/name-of-1-image:3.2.r3-version")).getLabelString());

        assertEquals("worker:a.com/b:1", buildMesosSlaveInfo("worker:a.com/b:1", true).getMesosSlaveInfoForLabel(getLabel("worker:a.com/b:1")).getLabelString());
        assertEquals("worker:a.com/b:1", buildMesosSlaveInfo("worker:a.com/b:1", false).getMesosSlaveInfoForLabel(getLabel("worker:a.com/b:1")).getLabelString());


        assertNull(buildMesosSlaveInfo(null, false).getMesosSlaveInfoForLabel(null).getLabelString());
        assertNull(buildMesosSlaveInfo("label", false).getMesosSlaveInfoForLabel(null));
        assertNull(buildMesosSlaveInfo(null, false).getMesosSlaveInfoForLabel(getLabel("label")));

        assertNull(buildMesosSlaveInfo(null, true).getMesosSlaveInfoForLabel(null).getLabelString());
        assertNull(buildMesosSlaveInfo("label", true).getMesosSlaveInfoForLabel(null));
        assertNull(buildMesosSlaveInfo(null, true).getMesosSlaveInfoForLabel(getLabel("label")));
    }

    @Test
    public void getNetworkNameTest() throws IOException, Descriptor.FormException, ANTLRException {
        MesosSlaveInfo info = buildMesosSlaveInfo("label", false);
        MesosSlaveInfo.NetworkInfo networkInfo = new MesosSlaveInfo.NetworkInfo("exampleNetwork");
        info.getContainerInfo().getNetworkInfos().add(networkInfo);
        assertEquals(info.getContainerInfo().getNetworkInfos().get(0).getNetworkName(), "exampleNetwork");
    }
}
