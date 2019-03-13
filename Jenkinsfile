#!/usr/bin/env groovy

@Library('sec_ci_libs@v2-latest') _

def master_branches = ["master", "usi-jenkins", ] as String[]

ansiColor('xterm') {
  // using shakedown node because it's a lightweight alpine docker image instead of full VM
  node('shakedown') {
    stage("Verify author") {
      user_is_authorized(master_branches, '8b793652-f26a-422f-a9ba-0d1e47eb9d89', '#orchestration')
    }
  }
  node('mesos-med') {
    stage('Build') {
      try {
        checkout scm
        if (isUnix()) {
          sh './gradlew clean check'
        } else {
          bat 'gradlew.bat clean check'
        }
      } finally {
        junit(allowEmptyResults: true, testResults: 'build/test-results/test/*.xml')
      }
    } 
  }
}

