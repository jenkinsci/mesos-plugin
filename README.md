<p align="center">
  <a href='https://ci.jenkins.io/job/Plugins/job/mesos-plugin/job/master/'><img src='https://ci.jenkins.io/buildStatus/icon?job=Plugins%2Fmesos-plugin%2Fmaster'></a>
  <a href="https://cloud.docker.com/u/mesosphere/repository/docker/mesosphere/jenkins/general"><img alt="Docker Pulls" src="https://img.shields.io/docker/pulls/mesosphere/jenkins.svg"></a>
</p>

Jenkins on Mesos
----------------

The `jenkins-mesos` plugin allows Jenkins to dynamically launch Jenkins slaves on a
Mesos cluster depending on the workload!

Put simply, whenever the Jenkins `Build Queue` starts getting bigger, this plugin
automatically spins up additional Jenkins agent(s) on Mesos so that jobs can be
immediately scheduled! Similarly, when a Jenkins slave is idle for a long time it
is automatically shut down.

## Table of Contents
<!-- toc -->
- __[Prerequisite](#prerequisite)__
- __[Installing the Plugin](#installing-the-plugin)__
  - __[Configuring the Plugin](#configuring-the-plugin)__
  - __[Adding Agent Specs](#adding-agent-specs)__
  - __[DC/OS Authentication](#dcos-authentication)__
  - __[Configuring Jenkins Jobs](#configuring-jenkins-jobs)__
  - __[Docker Containers](#docker-containers)__
  - __[Docker Configuration](#docker-configuration)__
  - __[Over provisioning flags](#over-provisioning-flags)__
- __[Single-Use Slave](#single-use-slave)__
  - __[Freestyle jobs](#freestyle-jobs)__
  - __[Pipeline jobs](#pipeline-jobs)__
- __[Plugin Development](#plugin-development)__
  - __[Building the plugin](#building-the-plugin)__
  - __[Testing On DC/OS Enterprise](#testing-on-dcos-enterprise)__
- __[Release](#release)__
<!-- /toc -->


## Prerequisite ##

You need to have access to a running Mesos cluster. For instructions on setting up a Mesos cluster, please refer to the [Mesos website](http://mesos.apache.org).

## Installing the Plugin ##

* Go to 'Manage Plugins' page in the Jenkins Web UI, you'll find the plugin in the 'Available' tab under the name 'mesos'.

* (Optional) Install the metrics plugin which is an optional dependency of this plugin, used for additional but not essential features.

### Configuring the Plugin ###

Now go to 'Configure' page in Jenkins. If the plugin is successfully installed
you should see an option to 'Add a new cloud' at the bottom of the page.

1. Add the 'Mesos Cloud'.
2. Give the path to the address `http://HOST:PORT` of a running Mesos master. On DC/OS this can be as simple as `https://leader.mesos:5050`.
3. Set the user name agents should start as. Ensure that the Mesos agents have have the user available.
4. Set the Jenkins URL.
5. Click `Save`.

You can click `Test Conection` to see if the Mesos client of the plugin can find the Mesos master.

If the Mesos master uses a secured connection with a custom certificate you can supply it under
`Use a custom SSL certificate`.

### Adding Agent Specs ###

An `Agent Spec` describes a Jenkins node for Mesos.

You can update the values/Add  more 'Agent Specs'/Delete 'Agent Specs' by clicking on 'Advanced'.
'Agent Specs' can hold required information(Executor CPU, Executor Mem etc) for an agent that needs
to be matched against Mesos offers.
Label name is the key between the job and the required agent to execute the job. See [Configuring Jenkins Jobs](#configuring-jenkins-jobs).
For instance, heavy jobs can be assigned  label 'powerful_agent'(which has 20 Executor CPU, 10240M Executor Mem etc)
and light weight jobs can be assigned label 'light_weight_agent'(which has  1 Executor CPU, 128M Executor Mem etc).

The [Jenkins Configuration as Code](https://jenkins.io/projects/jcasc/) in [dcos/conf/jenkins](dcos/conf/jenkins/configuration.yaml) configures a Linux agent based on the [amazoncorretto:8](https://hub.docker.com/_/amazoncorretto) Docker image and a Windows agent based on [mesosphere/jenkins-windows-node:latest](https://hub.docker.com/repository/docker/mesosphere/jenkins-windows-node/) Docker image. See https://github.com/jeschkies/hello-world-fsharp/blob/master/Jenkinsfile for an example build.

### DC/OS Authentication ###

The plugin can authenticate with a [DC/OS](https://docs.d2iq.com/mesosphere/dcos/1.13/security/ent/service-auth/) enterprise cluster. 
Simply run the environment variables `DCOS_SERVICE_ACCOUNT` containing the service account name and
`DCOS_SERVICE_ACCOUNT_PRIVATE_KEY` containing the private key for the service account. See [On DC/OS Enterprise](#on-dcos-enterprise) for details.

### Configuring Jenkins Jobs ###

Finally, just add the label name you have configured in Mesos cloud configuration -> Advanced -> Slave Info -> Label String (default is `mesos`) 
to the jobs (configure -> Restrict where this project can run checkbox) that you want to run on a specific slave type inside Mesos cluster.

### Docker Containers ###

By default, the Jenkins slaves are run in the default Mesos container. To run the Jenkins agent inside a Docker container, there are two options.

	1) "Use Native Docker Containerizer" : Select this option if Mesos slave(s) are configured with "--containerizers=docker" (recommended).

	2) "Use External Containerizer" : Select this option if Mesos slave(s) are configured with "--containerizers=external".

### Docker Configuration ###

#### Volumes ####

At a minimum, a container path must be entered to mount the volume. A host path can also be specified to bind mount the container path to the host path. This will allow persistence of data between slaves on the same node. The default setting is read-write, but an option is provided for read-only use.

#### Parameters ####

Additional parameters are available for the `docker run` command, but there are too many and they change too often to list all separately. This section allows you to provide any parameter you want. Ensure that your Docker version on your Mesos slaves is compatible with the parameters you add and that the values are correctly formatted. Use the full-word parameter and not the shortcut version, as these may not work properly. Also, exclude the preceding double-dash on the parameter name. For example, enter `volumes-from` and `my_container_name` to recieve the volumes from `my_container_name`. Of course `my_container_name` must already be on the Mesos slave where the Jenkins slave will run. This shouldn't cause problems in a homogenous environment where Jenkins slaves only run on particular Mesos slaves.

### Over provisioning flags ###

By default, Jenkins spawns slaves conservatively. Say, if there are 2 builds in queue, it won't spawn 2 executors immediately. It will spawn one executor and wait for sometime for the first executor to be freed before deciding to spawn the second executor. Jenkins makes sure every executor it spawns is utilized to the maximum.
If you want to override this behaviour and spawn an executor for each build in queue immediately without waiting, you can use these flags during Jenkins startup:
`-Dhudson.slaves.NodeProvisioner.MARGIN=50 -Dhudson.slaves.NodeProvisioner.MARGIN0=0.85`

## Single-Use Slave ##

### Freestyle jobs ###

In the Build Environment settings, you may select "Mesos Single-Use Slave" to schedule disposal of the slave after the build finishes.

### Pipeline jobs ###

To schedule slave disposal from a Pipeline job:

    node('mylabel') {
        wrap([$class: 'MesosSingleUseSlave']) {
            // build actions
        }
    }


## Plugin Development

### Building the plugin ###

Build the plugin as follows:

    $ ./gradlew check

This should build the Mesos plugin as `mesos.hpi` in the `target` folder. A test Jenkins server can be
started with

    $ ./gradlew server 

The integration tests require an installation of Mesos and Docker. You can run just the unit tests with

    $ ./gradlew test

and the integration tests with

    $ ./gradlew integrationTest

The code is formatted following the [Google Style Guide](https://github.com/google/styleguide).

### Testing On DC/OS Enterprise

See the [dcos folder](dcos-testing/README.md).

## Release

You must have publish rights and the credentials set in `~/.m2/settings.xml` and `~/.jenkins-ci.org` as described
[here](https://wiki.jenkins.io/display/JENKINS/Hosting+Plugins#HostingPlugins-Releasingtojenkins-ci.org).

To release this plugin

1. Set the version in `build.gradle`.
2. Publish the plugin with `./gradlew publish`.

