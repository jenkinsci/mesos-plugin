#!/usr/bin/env groovy

@Library('sec_ci_libs@v2-latest') _

def master_branches = ["master", ] as String[]

ansiColor('xterm') {
  // using shakedown node because it's a lightweight alpine docker image instead of full VM
  node('shakedown') {
    stage("Verify author") {
      user_is_authorized(master_branches, '8b793652-f26a-422f-a9ba-0d1e47eb9d89', '#eng-jenkins-builds')
    }
  }
  //node('mesos-med') {
  node('JenkinsMarathonCI-Debian9-2018-12-17') {
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
}

