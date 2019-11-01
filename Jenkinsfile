#!/usr/bin/env groovy
node('docker') {
    stage('Build') {
      try {
        checkout scm
        // Verify Docker is running.
        sh 'sudo -E docker --version'

        sh 'sudo -E ./ci/provision.sh 1.7.0'
        sh 'sudo -E ./gradlew check checkTocs --info'
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

