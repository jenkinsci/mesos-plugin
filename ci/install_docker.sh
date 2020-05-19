#!/usr/bin/env bash
set -x -e -o pipefail

curl -fsSL https://download.docker.com/linux/ubuntu/gpg | apt-key add -
add-apt-repository "deb [arch=amd64] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable"

apt-get -y update
apt-get -y install docker-ce docker-ce-cli containerd.io

docker --version

# Add user to docker group
gpasswd -a runner docker
