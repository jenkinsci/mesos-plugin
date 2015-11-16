# -*- mode: ruby -*-
# vi: set ft=ruby :

$script = <<SCRIPT

 # Exit on any errors.
 set -e

 # Install prerequisites for mesos.
 echo "Installing prerequisite packages for mesos..."

 apt-get -y update
 apt-get -y install openjdk-6-jdk
 apt-get -y install libcurl3
 apt-get -y install zookeeperd
 apt-get -y install libapr1-dev
 apt-get -y install libsvn-dev

 # Download mesos.
 MESOS_VERSION="0.21.1-1.0"
 echo "Downloading mesos ${MESOS_VERSION}..."
 wget http://downloads.mesosphere.io/master/ubuntu/12.04/mesos_${MESOS_VERSION}.ubuntu1204_amd64.deb

 echo "Installing mesos..."
 dpkg --install mesos_${MESOS_VERSION}.ubuntu1204_amd64.deb
 echo "Done"

 # Symlink /usr/lib/libjvm.so for mesos.
 ln -s /usr/lib/jvm/java-6-openjdk-amd64/jre/lib/amd64/server/libjvm.so /usr/lib/libjvm.so

 echo "Starting mesos master"
 start mesos-master

 echo "Starting mesos slave"
 start mesos-slave

 echo "Installing prerequisite packages for mesos-jenkins plugin..."
 apt-get -y install maven

 # Acquire latest code (either from local source on host, or latest release download) of mesos-jenkins plugin.
 MESOS_PLUGIN_VERSION="local"
 # MESOS_PLUGIN_VERSION="0.5.0" (0.5.0 is latest but not compatible with 0.6.0-SNAPSHOT changes, once
 # release has been performed this should be updated to 0.6.0 and then all should work well again)

 if [ $MESOS_PLUGIN_VERSION = "local" ]; then
    mkdir -p mesos-plugin-mesos-${MESOS_PLUGIN_VERSION}
    cp -r /vagrant/src mesos-plugin-mesos-${MESOS_PLUGIN_VERSION}/src
    cp -r /vagrant/pom.xml mesos-plugin-mesos-${MESOS_PLUGIN_VERSION}/pom.xml
    chown -R vagrant:vagrant mesos-plugin-mesos-${MESOS_PLUGIN_VERSION}
 else
   echo "Downloading mesos-jenkins ${MESOS_JENKINS_VERSION}..."
   wget https://github.com/jenkinsci/mesos-plugin/archive/mesos-${MESOS_PLUGIN_VERSION}.tar.gz
   tar -zxf mesos-${MESOS_PLUGIN_VERSION}.tar.gz && rm -f mesos-${MESOS_PLUGIN_VERSION}.tar.gz
   chown -R vagrant:vagrant mesos-plugin-mesos-${MESOS_PLUGIN_VERSION}
 fi

 # Build mesos-jenkins plugin.
 echo "Building mesos-jenkins plugin"
 # TODO(vinod): Update the mesos version in pom.xml.
 su - vagrant -c "cd mesos-plugin-mesos-${MESOS_PLUGIN_VERSION} && mvn package"
 echo "Done"

 echo "****************************************************************"
 echo "Successfully provisioned the machine."
 echo "You can run the Jenkins server with plugin installed as follows:"
 echo "> vagrant ssh"
 echo "> cd mesos-plugin-mesos-${MESOS_PLUGIN_VERSION}"
 echo "> mvn hpi:run"
 echo "****************************************************************"
 echo "NOTE: Configure the plugin as follows:"
 echo "From local host go to localhost:8080/jenkins/configure"
 echo "Mesos native library path: /usr/local/lib/libmesos.so"
 echo "Mesos Master [hostname:port]: zk://localhost:2181/mesos"
 echo "****************************************************************"


SCRIPT


# Vagrantfile API/syntax version. Don't touch unless you know what you're doing!
VAGRANTFILE_API_VERSION = "2"

Vagrant.configure(VAGRANTFILE_API_VERSION) do |config|
  # All Vagrant configuration is done here. The most common configuration
  # options are documented and commented below. For a complete reference,
  # please see the online documentation at vagrantup.com.

  # Every Vagrant virtual environment requires a box to build off of.
  config.vm.box = "ubuntu/precise64"

  # The url from where the 'config.vm.box' box will be fetched if it
  # doesn't already exist on the user's system.
  config.vm.box_url = "http://cloud-images.ubuntu.com/vagrant/trusty/current/trusty-server-cloudimg-amd64-vagrant-disk1.box"

  # Forward mesos ports.
  config.vm.network "forwarded_port", guest: 5050, host: 5050
  config.vm.network "forwarded_port", guest: 5051, host: 5051

  # Forward jenkins port.
  config.vm.network "forwarded_port", guest: 8080, host: 8080

  # Provision the system.
  config.vm.provision "shell", inline: $script

  config.vm.provider :virtualbox do |vb|
     # Use VBoxManage to customize the VM. For example to change memory:
     vb.customize ["modifyvm", :id, "--memory", "2048"]
     vb.customize ["modifyvm", :id, "--cpus", "2"]
  end

end
