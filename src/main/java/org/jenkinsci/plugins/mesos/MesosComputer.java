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

import hudson.model.Slave;
import hudson.slaves.SlaveComputer;

import java.io.IOException;

import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;

public class MesosComputer extends SlaveComputer {
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
    if (getNode() != null)
      getNode().terminate();
    return new HttpRedirect("..");
  }
}
