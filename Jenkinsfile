#!/usr/bin/env groovy
node('JenkinsMarathonCI-Debian9-2018-12-17') {
    stage('Build') {
      try {
        checkout scm
        // Verify Docker is running.
        sh 'sudo -E docker --version'

        sh 'sudo -E docker run --rm --privileged -v "$(pwd):/var/build" -w /var/build mesos/mesos-mini:1.9.x ci/run.sh'
      } finally {
        junit allowEmptyResults: true, testResults: 'build/test-results/test/*.xml'

        // Compress and archive sandboxes.
        sh 'sudo -E ./gradlew zipSandboxes --info'
        sh 'sudo rm -rf sandboxes'
        archive includes: 'build/distributions/sandboxes-*.zip'

        publishHTML (target: [ alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'build/reports/spotbugs/', reportFiles: '*.html', reportName: 'SpotBugs' ])
      }
    } 
}

