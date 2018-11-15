#!/bin/bash
mkdir -p maven-repo
docker-compose run builder /data/build.sh
