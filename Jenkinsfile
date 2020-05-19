#!/usr/bin/env groovy
node('maven') {
    stage('Build') {
      try {
        checkout scm
        sh './gradlew clean javadoc check -x integrationTest --info'
      } finally {
        junit allowEmptyResults: true, testResults: 'build/test-results/test/*.xml'

        publishHTML (target: [ alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'build/reports/spotbugs/', reportFiles: '*.html', reportName: 'SpotBugs' ])
      }
    } 
}

