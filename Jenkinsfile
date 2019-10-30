#!/usr/bin/env groovy
node('JenkinsMarathonCI-Debian9-2018-12-17') {
    stage('Build') {
      try {
        checkout scm
        sh 'sudo -E docker run -d --rm --privileged -v "$(pwd):/var/build" --name mini mesos/mesos-mini:1.9.x'
        sh 'sudo -E docker exec -w /var/build -it mini ci/run.sh'
      } finally {
        sh 'sudo docker kill mini'
        junit allowEmptyResults: true, testResults: 'build/test-results/test/*.xml'

        // Compress and archive sandboxes.
        sh 'sudo -E ./gradlew zipSandboxes --info'
        sh 'sudo rm -rf sandboxes'
        archive includes: 'build/distributions/sandboxes-*.zip'

        publishHTML (target: [ alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'build/reports/spotbugs/', reportFiles: '*.html', reportName: 'SpotBugs' ])
      }
    } 
}

