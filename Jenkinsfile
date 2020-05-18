#!/usr/bin/env groovy
node('docker') {
    stage('Build') {

      try {
        checkout scm
        sh 'sudo mkdir -p local_docker_storage'
        sh 'sudo -E docker run -d --rm --privileged -v "$(pwd):/var/build" -v local_docker_storage:/var/lib/docker --name mini mesos/mesos-mini:1.9.x'
	timeout(time: 45, unit: 'MINUTES') {
          sh 'sudo -E docker exec --privileged -w /var/build mini ci/run.sh'
	}
      } finally {
        // Compress and archive sandboxes, JUnit and Spotbugs reports.
        sh 'sudo -E ./gradlew zipSandboxes --info'
        sh 'sudo rm -rf sandboxes'
        archive includes: 'build/distributions/sandboxes-*.zip'

        junit allowEmptyResults: true, testResults: 'build/test-results/test/*.xml'

        publishHTML (target: [ alwaysLinkToLastBuild: false, keepAll: true, reportDir: 'build/reports/spotbugs/', reportFiles: '*.html', reportName: 'SpotBugs' ])

        // Kill Mini Mesos last so we have everything archived in case of an error.
        sh 'sudo docker kill mini'
        sh 'sudo rm -rf local_docker_storage'
      }
    } 
}

