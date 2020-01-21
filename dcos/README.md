# Running on DC/OS

This folder includes a Docker file and a [Marathon](https://mesosphere.github.io/marathon/) app 
definition which can be used to run Jenkins with this plugin on [DC/OS](https://dcos.io/).

### On DC/OS Enterprise

The `Dockerfile` defines a Docker image that supports DC/OS strict mode. It requires a service
account to run. To setup one up with the DC/OS CLI

1. Create service account secrets with
   ```
   dcos security org service-accounts keypair jenkins.private.pem jenkins.pub.pem
   ```
2. Create the actual service account called `jenkins`
   ```
   dcos security org service-accounts create -p jenkins.pub.pem -d "Jenkins Service Account" jenkins 
   ```
3. Store private key as secret so that the Jenkins master can access it
   ```
   dcos security secrets create -f ./jenkins.private.pem jenkins/private_key
   ```
4. Grant `jenkins` service account rights to start Mesos tasks:
   ```
   dcos security org users grant jenkins dcos:mesos:master:task:user:nobody create
   dcos security org users grant jenkins "dcos:mesos:master:framework:role:*" create
   ```
5. Deploy the Jenkins app defined in `./jenkins-app.json`
   ```
   dcos marathon app add jenkins-app.json
   ```

