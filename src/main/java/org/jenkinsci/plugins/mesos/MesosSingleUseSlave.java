package org.jenkinsci.plugins.mesos;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.slaves.OfflineCause;
import hudson.tasks.BuildWrapperDescriptor;
import jenkins.tasks.SimpleBuildWrapper;

import java.io.IOException;
import java.util.logging.Logger;

import org.kohsuke.stapler.DataBoundConstructor;

/**
 This file is part of the JCloud Jenkins Plugin. (https://github.com/jenkinsci/jclouds-plugin)
 commit id 20c6ca884abb172b27d0af82545c8915ce08f618.

 This file was modified to work with the Mesos Jenkins plugin and based off the JClouds Jenkins Plugin.

 According to the Jenkins Plugin guide (https://wiki.jenkins-ci.org/display/JENKINS/Before+starting+a+new+plugin),
 If no license is defined in the form of a header, LICENSE file, or in the license section, the code is assumed to be
 under The MIT License.

 The MIT License (MIT)

 Permission is hereby granted, free of charge, to any person obtaining a copy
 of this software and associated documentation files (the "Software"), to deal
 in the Software without restriction, including without limitation the rights
 to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 copies of the Software, and to permit persons to whom the Software is
 furnished to do so, subject to the following conditions:

 The above copyright notice and this permission notice shall be included in
 all copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 THE SOFTWARE.
 */
public class MesosSingleUseSlave extends SimpleBuildWrapper {
    private static final Logger LOGGER = Logger.getLogger(MesosSingleUseSlave.class.getName());

    @DataBoundConstructor
    public MesosSingleUseSlave() {
    }

    @Override
    public void setUp(Context context, Run<?, ?> build, FilePath workspace, Launcher launcher, TaskListener listener, EnvVars initialEnvironment) throws IOException, InterruptedException {
        context.setDisposer(new MesosSingleUseSlaveDisposer());

        Computer computer = workspace.toComputer();
        if (computer != null) {
            if (MesosComputer.class.isInstance(computer)) {
                String msg = "Marking " + computer.getName() + " as single-use.";
                LOGGER.warning(msg);
                listener.getLogger().println(msg);

                MesosSlave mesosSlave = (MesosSlave) computer.getNode();
                if (mesosSlave != null) {
                    mesosSlave.setSingleUse(true);
                }
            } else {
                listener.getLogger().println("Not able to set single-use slave, this is a " + computer.getClass());
            }
        }
    }

    @Extension
    public static final class DescriptorImpl extends BuildWrapperDescriptor {
        @Override
        public String getDisplayName() {
            return "Mesos Single-Use Slave";
        }

        @Override
        @SuppressWarnings("rawtypes")
        public boolean isApplicable(AbstractProject item) {
            return true;
        }
    }

    private static class MesosSingleUseSlaveDisposer extends Disposer {
        @Override
        public void tearDown(Run<?, ?> build, FilePath workspace, Launcher launcher, TaskListener listener) throws IOException, InterruptedException {
            Computer computer = workspace.toComputer();
            if (computer == null) {
                throw new IllegalStateException("Computer is null");
            }
            if (MesosComputer.class.isInstance(computer)) {
                String msg = "Taking single-use slave " + computer.getName() + " offline.";
                LOGGER.warning(msg);
                listener.getLogger().println(msg);
                computer.setTemporarilyOffline(true, OfflineCause.create(Messages._MesosSingleUseSlave_OfflineCause()));
            } else {
                listener.getLogger().println("Not a single-use slave, this is a " + computer.getClass());
            }
        }
    }
}
