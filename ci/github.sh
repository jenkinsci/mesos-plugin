#!/usr/bin/env bash
docker run -d --rm --privileged -v "$(pwd):/var/build" --name mini mesos/mesos-mini:1.9.x
docker ps
docker exec -w /var/build mini ci/run.sh
