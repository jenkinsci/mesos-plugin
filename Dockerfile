FROM jenkins:2.7.1

ENV MESOS_VERSION=0.28.1\*

# Mesos plugin needs mesos binary to be present
USER root
RUN apt-key adv --keyserver keyserver.ubuntu.com --recv E56151BF && \
    sh -c "echo deb http://repos.mesosphere.io/debian jessie main > /etc/apt/sources.list.d/mesosphere.list" && \
    apt-get update && \
    apt-get install -y mesos=$MESOS_VERSION && \
    rm -rf /var/lib/apt/lists/*

USER jenkins

# Disable executors
COPY src/main/resources/docker/executors.groovy /usr/share/jenkins/ref/init.groovy.d/executors.groovy

# Install mesos plugins and its dependencies
RUN /usr/local/bin/install-plugins.sh mesos metrics credentials jackson2-api

# Other useful plugins: pipeline, git and their dependencies
RUN /usr/local/bin/install-plugins.sh \
  ace-editor:1.1 \
  antisamy-markup-formatter:1.5 \
  authentication-tokens:1.3 \
  branch-api:1.10 \
  cloudbees-folder:5.12 \
  credentials:2.1.4 \
  docker-commons:1.4.0 \
  docker-workflow:1.6 \
  durable-task:1.11 \
  git-client:1.19.7 \
  git-server:1.7 \
  git:2.5.2 \
  handlebars:1.1.1 \
  icon-shim:2.0.3 \
  jackson2-api:2.7.3 \
  jquery-detached:1.2.1 \
  junit:1.15 \
  mailer:1.17 \
  matrix-auth:1.4 \
  matrix-project:1.7.1 \
  mesos:0.13.0 \
  metrics:3.1.2.8 \
  momentjs:1.1.1 \
  pipeline-build-step:2.2 \
  pipeline-input-step:2.0 \
  pipeline-rest-api:1.5 \
  pipeline-stage-step:2.1 \
  pipeline-stage-view:1.5 \
  scm-api:1.2 \
  script-security:1.21 \
  ssh-credentials:1.12 \
  structs:1.2 \
  windows-slaves:1.1 \
  workflow-aggregator:2.2 \
  workflow-api:2.1 \
  workflow-basic-steps:2.0 \
  workflow-cps-global-lib:2.1 \
  workflow-cps:2.9 \
  workflow-durable-task-step:2.3 \
  workflow-job:2.3 \
  workflow-multibranch:2.8 \
  workflow-scm-step:2.2 \
  workflow-step-api:2.2 \
  workflow-support:2.2

ENTRYPOINT ["/bin/tini", "--", "/usr/local/bin/jenkins.sh"]

