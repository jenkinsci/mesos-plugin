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

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Slave;
import hudson.remoting.VirtualChannel;
import hudson.slaves.SlaveComputer;

import java.io.IOException;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import jenkins.model.Jenkins;
import jenkins.slaves.EncryptedSlaveAgentJnlpFile;

import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.WebMethod;

public class MesosComputer extends SlaveComputer {
  private static final Logger LOGGER = Logger.getLogger(MesosComputer.class.getName());

  public MesosComputer(Slave slave) {
    super(slave);
  }

  @Override
  public MesosSlave getNode() {
    return (MesosSlave) super.getNode();
  }

  @Override
  public HttpResponse doDoDelete() throws IOException {
    checkPermission(DELETE);
      MesosSlave node = getNode();
      if (node != null) {
          node.terminate();
      }
    return new HttpRedirect("..");
  }

  @Override
  @WebMethod(name="slave-agent.jnlp")
  public HttpResponse doSlaveAgentJnlp(StaplerRequest req, StaplerResponse res) throws IOException, ServletException {
	  return new EncryptedSlaveAgentJnlpFile(this, "mesos-slave-agent.jnlp.jelly", getName(), CONNECT);
  }

  /**
   * Delete the slave, terminate the instance. Can be called either by doDoDelete() or from MesosRetentionStrategy.
   *
   * @throws InterruptedException
   */
  public void deleteSlave() throws IOException, InterruptedException {
      LOGGER.info("Terminating " + getName() + " slave");
      MesosSlave slave = getNode();

      // Slave already deleted
      if (slave == null) return;

      VirtualChannel channel = slave.getChannel();
      if (channel != null) {
          channel.close();
      }
      slave.terminate();
      getJenkins().removeNode(slave);
  }

    @NonNull
    private static Jenkins getJenkins() {
        Jenkins jenkins = Jenkins.getInstance();
        if (jenkins == null) {
            throw new IllegalStateException("Jenkins is null");
        }
        return jenkins;
    }
}
