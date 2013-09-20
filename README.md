Jenkins on Mesos
----------------

This Jenkins plugin allows Jenkins to dynamically launch Jenkins slaves on a
Mesos cluster depending on the workload!

Put simply, whenever the Jenkins `Build Queue` starts getting bigger, this plugin
automatically spins up additional Jenkins slave(s) on Mesos so that jobs can be
immediately scheduled! Similarly, when a Jenkins slave is idle for a long time it
is automatically shut down.


### Prerequisite ###

You need to have access to a running Mesos cluster. For instructions on setting up a Mesos cluster, please refer to the [Mesos website](www.mesos.apache.org).


### Building the plugin ###

Now build the plugin:

        $ mvn package

This should build the Mesos plugin (mesos.hpi) in the 'target' folder.

> NOTE: If you want to build against a different version of Mesos than
> the default you'll need to update the `mesos` version in `pom.xml`.
> It is recommended to use the same/compatible version as the one your
> Mesos cluster is running on.


### Installing the plugin ###

Go to 'Manage Plugins' page in Jenkins Web UI and manually upload and
install the plugin.

Alternatively, you can just copy the plugin to your Jenkins plugins directory
(this might need a restart of Jenkins).

        $ cp target/mesos.hpi ${JENKINS_HOME}/plugins


### Building the Mesos native library ##

This plugin needs access to the Mesos native library. You can build it as follows:

		$ git clone git://git.apache.org/mesos.git
		$ cd mesos
		$ git checkout 0.13.0  # Use the same/compatible version as the one in pom.xml.
		$ ./bootstrap
		$ mkdir build && cd build
		$ ../configure
		$ make

This should build the Mesos native library in the 'build/src/.libs' folder.


### Configuring the plugin ###

Now go to 'Configure' page in Jenkins. If the plugin is successfully installed
you should see an option to 'Add a new cloud' at the bottom of the page. Add the
'Mesos Cloud' and give the path to the Mesos native library (see the above section)
and the address (HOST:PORT) of a running Mesos master. Click 'Save' for the plugin
to connect to Mesos.

Login to the Mesos master's Web UI to verify that the plugin is registered as
'Jenkins Framework'.


### Configuring Jenkins jobs ###

Finally, just add 'mesos' label to the jobs that you want to be run on a
Jenkins slave launched on Mesos.

Thats it!


_Please email user@mesos.apache.org with questions!_
