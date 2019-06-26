# Stage: Build
FROM gradle:5.4.1-jdk8 AS build
ADD . /home/gradle/project
WORKDIR /home/gradle/project
RUN gradle jpi


# Stage: Prod
FROM jenkins/jenkins:2.164

# Default policy according to https://wiki.jenkins.io/display/JENKINS/Configuring+Content+Security+Policy
ENV JENKINS_CSP_OPTS="sandbox; default-src 'none'; img-src 'self'; style-src 'self';"

# Install plugin
ENV JENKINS_REF=/usr/share/jenkins/ref/
COPY --from=build /home/gradle/project/build/libs/mesos.hpi "${JENKINS_REF}/plugins/mesos.hpi"

# Disable first-run wizard
RUN echo 2.0 > "${JENKINS_REF}/jenkins.install.UpgradeWizard.state"