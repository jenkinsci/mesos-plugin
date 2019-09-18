# Stage: Build
FROM gradle:5.4.1-jdk8 AS build
ADD . /home/gradle/project
WORKDIR /home/gradle/project
RUN gradle jpi


# Stage: Prod
FROM jenkins/jenkins:2.176.1 as prod
WORKDIR /tmp

# Environment variables used throughout this Dockerfile
#
# $JENKINS_HOME     will be the final destination that Jenkins will use as its
#                   data directory. This cannot be populated before Marathon
#                   has a chance to create the host-container volume mapping.
#
ENV JENKINS_FOLDER /usr/share/jenkins

# Build Args
ARG BLUEOCEAN_VERSION=1.17.0
ARG JENKINS_STAGING=/usr/share/jenkins/ref/
ARG MESOS_PLUG_HASH=5216d7ecef0bc8923ff510aec6659e2c7e7611cb

# Default policy according to https://wiki.jenkins.io/display/JENKINS/Configuring+Content+Security+Policy
ENV JENKINS_CSP_OPTS="sandbox; default-src 'none'; img-src 'self'; style-src 'self';"

USER root

# install dependencies
RUN apt-get update && apt-get install -y nginx python zip jq gettext-base
# update to newer git version
RUN echo "deb http://ftp.debian.org/debian testing main" >> /etc/apt/sources.list \
  && apt-get update && apt-get -t testing install -y git

# Override the default property for DNS lookup caching
RUN echo 'networkaddress.cache.ttl=60' >> ${JAVA_HOME}/jre/lib/security/java.security

# bootstrap scripts and needed dir setup
COPY dcos/scripts/bootstrap.py /usr/local/jenkins/bin/bootstrap.py
COPY dcos/scripts/dcos-account.sh /usr/local/jenkins/bin/dcos-account.sh
RUN mkdir -p "$JENKINS_HOME" "${JENKINS_FOLDER}/war"

# nginx setup
RUN mkdir -p /var/log/nginx/jenkins
COPY dcos/conf/nginx/nginx.conf.template /etc/nginx/nginx.conf.template

# Jenkins setup
COPY dcos/conf/jenkins/config.xml "${JENKINS_STAGING}/config.xml"
COPY dcos/conf/jenkins/jenkins.model.JenkinsLocationConfiguration.xml "${JENKINS_STAGING}/jenkins.model.JenkinsLocationConfiguration.xml"
COPY dcos/conf/jenkins/nodeMonitors.xml "${JENKINS_STAGING}/nodeMonitors.xml"
COPY dcos/scripts/init.groovy.d/mesos-auth.groovy "${JENKINS_STAGING}/init.groovy.d/mesos-auth.groovy"

# Add plugins
COPY dcos/conf/plugins.conf /tmp/
RUN sed -i "s/\${BLUEOCEAN_VERSION}/${BLUEOCEAN_VERSION}/g" /tmp/plugins.conf
RUN /usr/local/bin/install-plugins.sh < /tmp/plugins.conf

# Add Mesos plugin
COPY --from=build /home/gradle/project/build/libs/mesos.hpi "${JENKINS_STAGING}/plugins/mesos.hpi"

# Disable first-run wizard
RUN echo 2.0 > /usr/share/jenkins/ref/jenkins.install.UpgradeWizard.state

CMD /usr/local/jenkins/bin/bootstrap.py              \
  && envsubst '\$PORT0 \$PORT1 \$JENKINS_CONTEXT' < /etc/nginx/nginx.conf.template > /etc/nginx/nginx.conf && nginx \
  && . /usr/local/jenkins/bin/dcos-account.sh        \
  && java ${JVM_OPTS}                                \
     -Dhudson.model.DirectoryBrowserSupport.CSP="${JENKINS_CSP_OPTS}" \
     -Dhudson.udp=-1                                 \
     -Djava.awt.headless=true                        \
     -Dhudson.DNSMultiCast.disabled=true             \
     -Djenkins.install.runSetupWizard=false          \
     -jar ${JENKINS_FOLDER}/jenkins.war              \
     ${JENKINS_OPTS}                                 \
     --httpPort=${PORT1}                             \
     --webroot=${JENKINS_FOLDER}/war                 \
     --ajp13Port=-1                                  \
     --httpListenAddress=127.0.0.1                   \
     --ajp13ListenAddress=127.0.0.1                  \
     --prefix=${JENKINS_CONTEXT}
