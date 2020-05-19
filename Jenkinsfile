#!/usr/bin/env groovy
node('docker') {
    stage('Build') {
      try {
        checkout scm
        sh './gradlew clean test --info'
      } finally {
        // Compress and archive sandboxes, JUnit and Spotbugs reports.
        //sh 'sudo -E ./gradlew zipSandboxes --info'
        //sh 'sudo rm -rf sandboxes'
        //archive includes: 'build/distributions/sandboxes-*.zip'

        junit allowEmptyResults: true, testResults: 'build/test-results/test/*.xml'

        publishHTML (target: [ alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'build/reports/spotbugs/', reportFiles: '*.html', reportName: 'SpotBugs' ])
      }
    } 
}

