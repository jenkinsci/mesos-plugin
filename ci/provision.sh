#!/usr/bin/env bash
set -x -e -o pipefail

MESOS_VERSION=$1


DISTRO=$(lsb_release -is | tr '[:upper:]' '[:lower:]')
CODENAME=$(lsb_release -cs)

# Add Mesosphere repo to the list
apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv E56151BF
echo "deb http://repos.mesosphere.io/${DISTRO} ${CODENAME} main" | \
    sudo tee /etc/apt/sources.list.d/mesosphere.list

# Some keys are missing from time to time - add them manually
sudo apt-key adv --keyserver keyserver.ubuntu.com --recv-keys 6B05F25D762E3157
apt-get -y update

# Install Mesos
apt-get -y install mesos="$MESOS_VERSION-2.0.3" java-common
mesos master --version
mesos agent --version

# Install Corretto JDK 11
wget -q https://d3pxv6yz143wms.cloudfront.net/11.0.2.9.3/java-11-amazon-corretto-jdk_11.0.2.9-3_amd64.deb
dpkg --install java-11-amazon-corretto-jdk_11.0.2.9-3_amd64.deb
update-alternatives --config java
update-alternatives --config javac
java -version
