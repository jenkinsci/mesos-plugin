Jenkins on Mesos
----------------

The `mesos-jenkins` plugin allows Jenkins to dynamically launch Jenkins slaves on a
Mesos cluster depending on the workload!

Put simply, whenever the Jenkins `Build Queue` starts getting bigger, this plugin
automatically spins up additional Jenkins slave(s) on Mesos so that jobs can be
immediately scheduled! Similarly, when a Jenkins slave is idle for a long time it
is automatically shut down.


### Prerequisite ###

You need to have access to a running Mesos cluster. For instructions on setting up a Mesos cluster, please refer to the [Mesos website](http://mesos.apache.org).


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

### Adding Slave Info ###

By default one 'Slave Info' will be created with default values for each field.
You can update the values/Add  more 'Slave Info'/Delete 'Slave Info' by clicking on 'Advanced'.
'Slave Info' can hold required information(Executor CPU, Executor Mem etc) for slave that need to be matched against Mesos offers.
Label name is the key between the job and the required slave to execute the job.
Ex: Heavy jobs can be assigned  label 'powerful_slave'(which has 'Slave Info' 20 Executor CPU, 10240M Executor Mem etc)
and light weight jobs can be assigned label 'light_weight_slave'(which has  'Slave Info' 1 Executor CPU, 128M Executor Mem etc).

### Mesos slave attributes ###

Mesos slaves can be tagged with attributes. This feature allows the Jenkins scheduler to pick specific
Mesos slaves based on attributes specified in JSON format. Ex. {"clusterType":"jenkinsSlave"}

### Mesos slave checkpointing ###

Checkpointing can now be enabled by setting the "Slave Checkpointing" option to yes in the cloud config. This will allow the Jenkins
master to finish running its slave jobs even if the Mesos slave process temporarily goes down.

### Configuring Jenkins jobs ###

Finally, just add the label name you have configured in Mesos cloud configuration -> Advanced -> Slave Info -> Label String (default is `mesos`) 
to the jobs (configure -> Restrict where this project can run checkbox) that you want to run on a specific slave type inside Mesos cluster.

Thats it!


_Please email user@mesos.apache.org with questions!_
