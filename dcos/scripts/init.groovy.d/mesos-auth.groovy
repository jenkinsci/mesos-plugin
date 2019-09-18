import com.cloudbees.plugins.credentials.*
import com.cloudbees.plugins.credentials.domains.*
import com.cloudbees.plugins.credentials.impl.*
import hudson.tasks.*
import jenkins.model.*
import org.jenkinsci.plugins.mesos.MesosCloud

def changePassword = { userName ->
  def cloud = MesosCloud.get()
  def credentialsId = cloud.getCredentialsId()
  def credId = "mesos-${userName}"

  if (credentialsId && credentialsId == credId) {
    // do nothing if credential already exists
    println "--> [mesos] credentials already selected"
  } else {
    // create a new credential with an expected ID
    println "--> [mesos] creating new credentials"
    String randomPwd = org.apache.commons.lang.RandomStringUtils.random(9, true, true)

    mesosFrameworkCreds = new UsernamePasswordCredentialsImpl(
      CredentialsScope.GLOBAL,
      "mesos-${userName}",
      "mesos authentication",
      userName, randomPwd)
    SystemCredentialsProvider.getInstance().getStore().addCredentials(Domain.global(), mesosFrameworkCreds)
    cloud.setCredentialsId(mesosFrameworkCreds.getId())
    Jenkins.getInstance().save()
    cloud.restartMesos()

    println "--> [mesos] creating new credentials... done"
  }
}

// the env var is set by DCOS when using a service account to run Jenkins
def accountCreds = System.getenv("DCOS_SERVICE_ACCOUNT_CREDENTIAL")
def sleepTimeStr = System.getenv("DCOS_JENKINS_MESOS_PLUGIN_BOOT_TIME")
def sleepTime = sleepTimeStr == null ? 60000 : Integer.parseInt(sleepTimeStr)
if (accountCreds) {
  Thread.start {
    // wait 60s, this gives the mesos plugin time to start
    sleep sleepTime
    def credURL = new URL(accountCreds)
    def credFile = new File(credURL.toURI())
    def credJSON = new groovy.json.JsonSlurper().parseText(credFile.text)
    if (credJSON && credJSON.uid) {
        changePassword(credJSON.uid)
    } else {
      println "--> [mesos] Failed to read principal from credentials file"
    }
  }
} else {
  println "--> [mesos] No DC/OS account detected; skipping mesos auth"
}
