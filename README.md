Jenkins on Mesos
----------------

The `mesos-jenkins` plugin allows Jenkins to dynamically launch Jenkins slaves on a
Mesos cluster depending on the workload!

Put simply, whenever the Jenkins `Build Queue` starts getting bigger, this plugin
automatically spins up additional Jenkins slave(s) on Mesos so that jobs can be
immediately scheduled! Similarly, when a Jenkins slave is idle for a long time it
is automatically shut down.


### Prerequisite ###

You need to have access to a running Mesos cluster. For instructions on setting up a Mesos cluster, please refer to the [Mesos website](www.mesos.apache.org).


### Building the plugin ###

Build the plugin as follows:

        $ mvn package

This should build the Mesos plugin (mesos.hpi) in the `target` folder.

> NOTE: If you want to build against a different version of Mesos than
> the default you'll need to update the `mesos` version in `pom.xml`.
> You should use the same (**recommended**) or compatible version as the
> one your Mesos cluster is running on.


### Installing the plugin ###

Go to 'Manage Plugins' page in the Jenkins Web UI and manually upload and
install the plugin.

Alternatively, you can just copy the plugin to your Jenkins plugins directory
(this might need a restart of Jenkins).

        $ cp target/mesos.hpi ${JENKINS_HOME}/plugins

If you simply want to play with the `mesos-jenkins` plugin, you can also bring up a local Jenkins instance with the plugin pre-installed as follows:

		$ mvn hpi:run


### Building the Mesos native library ##

First, [download](http://mesos.apache.org/downloads/) Mesos.

> NOTE: Ensure the Mesos version you download is same (**recommended**) or compatible with the `mesos` version in `pom.xml`.

Now, build it as follows:

		$ cd mesos
		$ mkdir build && cd build
		$ ../configure
		$ make

This should build the Mesos native library in the `build/src/.libs` folder.


### Vagrant ###

If you are just looking to play with Mesos and this plugin in a VM, you could do so with the included Vagrantfile.

		$ vagrant up
		$ vagrant ssh


### Configuring the plugin ###

Now go to 'Configure' page in Jenkins. If the plugin is successfully installed
you should see an option to 'Add a new cloud' at the bottom of the page. Add the
'Mesos Cloud' and give the path to the Mesos native library (e.g., libmesos.so on Linux or libmesos.dylib on OSX) (see the above section)
and the address (HOST:PORT) of a running Mesos master. Click 'Save' for the plugin
to connect to Mesos.

Login to the Mesos master's Web UI to verify that the plugin is registered as
'Jenkins Framework'.

### Mesos slave setup ###

Ensure Mesos slaves have a `jenkins` user or the user the Jenkins master is running as.

### Configuring Jenkins jobs ###

Finally, just add `mesos` label to the jobs (configure -> Restrict where this project can run checkbox) that you want to be run on a
Jenkins slave launched on Mesos.

Thats it!


_Please email user@mesos.apache.org with questions!_
