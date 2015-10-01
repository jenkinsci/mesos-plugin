/*
 * Copyright 2015 CloudBees, Inc. and other contributors.
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

import hudson.Extension;
import hudson.slaves.Cloud;
import java.util.List;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest;

/**
 * Mesos {@link Cloud}, which cannot be reconfigured from the web interface.
 * @author Oleg Nenashev
 * @since TODO
 */
public class NonConfigurableMesosCloud extends MesosCloud {
 
    /**
     * Constructs a cloud from a parent Mesos Cloud
     * @param name Name of the cloud to be created
     */
    public NonConfigurableMesosCloud(String name, String nativeLibraryPath, String master, String description, 
            String frameworkName, String slavesUser, String principal, String secret, 
            List<MesosSlaveInfo> slaveInfos, boolean checkpoint, boolean onDemandRegistration, String jenkinsURL) 
            throws NumberFormatException {
        super(name, nativeLibraryPath, master, description, frameworkName, slavesUser, 
                principal, secret, slaveInfos, checkpoint, onDemandRegistration, jenkinsURL);
    }
    
    /**
     * Copy constructor.
     * @param name Name of the cloud to be created.
     * @param cloud Cloud to be copied
     */
    public NonConfigurableMesosCloud(String name, MesosCloud cloud) {
        super(name, cloud);
    }
    
    @Extension
    public static class DescriptorImpl extends MesosCloud.DescriptorImpl {
        @Override
        public String getDisplayName() {
            return Messages.NonConfigurableMesosCloud_displayName();
        }

        @Override
        public boolean configure(StaplerRequest request, JSONObject object) throws FormException {
            return true;
        }
        
        @Override
        public Cloud newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            // TODO: replace by getActieInstance in 1.590+
            final Jenkins jenkins = Jenkins.getInstance();
            if (jenkins == null) {
                throw new IllegalStateException("Jenkins instance is not ready");
            }
            
            // We prevent the cloud reconfiguration from the web UI
            String cloudName = req.getParameter("cloudName");
            return jenkins.getCloud(cloudName);
        }
    }
}
