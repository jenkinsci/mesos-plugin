#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail

systemctl stop mesos-master
systemctl stop mesos-slave

# Java
yum install -y wget
wget -q https://d3pxv6yz143wms.cloudfront.net/8.232.09.1/java-1.8.0-amazon-corretto-devel-1.8.0_232.b09-1.x86_64.rpm
yum localinstall -y java-1.8.0-amazon-corretto-devel-1.8.0_232.b09-1.x86_64.rpm

JAVA_HOME=/usr/lib/jvm/java-1.8.0-amazon-corretto ./gradlew check  --info


