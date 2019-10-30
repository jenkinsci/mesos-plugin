#!/usr/bin/env bash
set -o errexit -o nounset -o pipefail

# From entrypoint.sh
MOUNT_TABLE=$(cat /proc/self/mountinfo)
DOCKER_CGROUP_MOUNTS=$(echo "${MOUNT_TABLE}" | grep /sys/fs/cgroup | grep docker)
DOCKER_CGROUP=$(echo "${DOCKER_CGROUP_MOUNTS}" | head -n 1 | cut -d' ' -f 4)
CGROUP_SUBSYSTEMS=$(echo "${DOCKER_CGROUP_MOUNTS}" | cut -d' ' -f 5)

echo "${CGROUP_SUBSYSTEMS}" |
while IFS= read -r SUBSYSTEM; do
  mkdir -p "${SUBSYSTEM}${DOCKER_CGROUP}"
  mount --bind "${SUBSYSTEM}" "${SUBSYSTEM}${DOCKER_CGROUP}"
done

# Java
yum install -y wget
wget -q https://d3pxv6yz143wms.cloudfront.net/8.232.09.1/java-1.8.0-amazon-corretto-devel-1.8.0_232.b09-1.x86_64.rpm
yum localinstall -y java-1.8.0-amazon-corretto-devel-1.8.0_232.b09-1.x86_64.rpm

JAVA_HOME=/usr/lib/jvm/java-1.8.0-amazon-corretto ./gradlew check  --info


