# Changelog

## 1.0.0 (September 17th 2019)

-   Support for Mesos Containerizer

-   Suppression of offers when there is no workload in the queue

-   Support for Single Use agents
-   Support for USER networking
-   Enhanced metrics and logging for better observability

-   Fixed default long decline timeout to 60s (previously it was
    incorrectly set to 60k seconds)
-   Several bug fixes

NOTE:  Upgrading from previous versions of plugin to 1.0.0 is not
backwards compatible due to the addition of support for Mesos
containerizer. The plugin configuration needs to be manually fixed after
the upgrade for it to work.

## 0.18.1 (September 25th 2018)

This release includes both the 0.18 and 0.17.1 changes.

## 0.18 (September 25th 2018)

-   Fix security issues:
    [one](https://jenkins.io/security/advisory/2018-09-25/#SECURITY-1013%20(1)),
    [two](https://jenkins.io/security/advisory/2018-09-25/#SECURITY-1013%20(2))

**This release does not contain the changes from 0.17.1.**

## 0.17.1 (September 17th 2018)

-   Solves the problem of concurrent builds on a NFS based workspace. 

## 0.17 (June 15th 2018)

-   Additional compatibility fix for Jenkins
    2.103 [\#311](https://github.com/jenkinsci/mesos-plugin/pull/311).
    Unfortunately, due to the new serialization restrictions, the slave
    attributes may have to be redefined if you migrated to Jenkins
    2.102+ prior to upgrading this plugin.
-   Enhancements from Mesosphere : 
    -   Mesos offer handling and Jenkins slave lifecycle
    -   Introduce a proper offer processing thread and make it fault
        tolerant
    -   Fix threads waiting for Jenkins computers not dying on task
        failure

## 0.16 (March 26th 2018)

-   Additional compatibility fix for Jenkins
    2.103 [\#311](https://github.com/jenkinsci/mesos-plugin/pull/311).
    Unfortunately, due to the new serialization restrictions, the slave
    attributes may have to be redefined if you migrated to Jenkins
    2.102+ prior to upgrading this plugin.

## 0.15.1 (Jan 29th 2018)

-   Compatibility fix for Jenkins
    2.103 [\#310](https://github.com/jenkinsci/mesos-plugin/pull/310)

## 0.15.0 (July 14th 2017)

-   Support for Mesos maintenance primitives

-   Feature to include disk space for Jenkins slave task

-   Fix for  restart of frameworks when multiple Mesos clouds are used
    [\#270](https://github.com/jenkinsci/mesos-plugin/issues/270)

## 0.14.1 (Mar 11th 2017)

-   Bug fix in slave deletion logic
    [\#282](https://github.com/jenkinsci/mesos-plugin/issues/282)
-   Task launch with appropriate roles from offer
-   Documentation updates

## 0.14.0 (Jan 3rd 2017)

-   Support for overlay networks
-   Fallback to root url if url from request doesn't work
-   Support for Jenkins 2.27+ - Use JNLPLauncher to launch the slave
     [\#269](https://github.com/jenkinsci/mesos-plugin/issues/269)
-   Mesos single use slave enabled for Jenkins pipeline.
    [\#262](https://github.com/jenkinsci/mesos-plugin/issues/262)
-   Better logging
-   Base types for variables 
-   Documentation update with working links
-   Check for builds in queue before declining an offer

## 0.13.1 (Jul 27th 2016)

-   Compatibility fix when upgrading from version prior to 0.13.0
-   Minor logging tuning to reduce noise in main Jenkins logs

## 0.13.0 (Jul 11th 2016)

-   [\#229](https://github.com/jenkinsci/mesos-plugin/pull/229) Jenkins
    slave memory fix
-   [\#233](https://github.com/jenkinsci/mesos-plugin/pull/233) Add a
    minimum number of executors per slave
-   [\#228](https://github.com/jenkinsci/mesos-plugin/pull/228) Ability
    to customize docker image using labels
-   [13ac08](https://github.com/jenkinsci/mesos-plugin/commit/13ac08a06bb6a59b866554a90949bc6bf90ef196)
    Prevent the scheduler from starting if jenkinsMaster is null

## 0.12.0 (Mar 24th 2016)

-   [\#218](https://github.com/jenkinsci/mesos-plugin/pull/218)
    [\#219](https://github.com/jenkinsci/mesos-plugin/pull/219) Add
    missing inline helps
-   Fix a blind cast in MesosItemListener
-   Add an administrative monitor if slaves couldn't be provisioned (and
    corresponding health check if metrics plugin is installed)
-   Flag MesosSlave as non-instantiable (this hides the slave from 'New
    Node' page)
-   Use data binding for all form fields : makes the overall
    configuration easier to work with by splitting the jelly files
-   Add support for Node Properties : allows to declare Node Properties
    to be applied to the created slave, including custom environment
    variables. Replaces the
    implicit [\_JAVA\_OPTIONS](https://github.com/jenkinsci/mesos-plugin/commit/6db07a7ffe577cce8e88d70c0d338155e59e19be)
    added in 0.10.0
-   [\#220](https://github.com/jenkinsci/mesos-plugin/pull/220) Add
    missing equals/hashCode methods causing framework restart on
    configuration save

## 0.11.0 (Mar 7th 2016)

-   [\#211](https://github.com/jenkinsci/mesos-plugin/pull/211) - Wait
    for slave to come online to avoid provisioning of additional slaves
-   [\#213](https://github.com/jenkinsci/mesos-plugin/pull/213) - fixed
    NullPointerException due to empty label in cloud config
-   [\#214](https://github.com/jenkinsci/mesos-plugin/pull/214) -
    Default labelling for jobs
-   [\#212](https://github.com/jenkinsci/mesos-plugin/pull/212) -
    Introduced affinity for offers
-   [\#215](https://github.com/jenkinsci/mesos-plugin/pull/215) - Fix
    another case of duplicate framework registration
-   [\#210](https://github.com/jenkinsci/mesos-plugin/pull/210) - Update
    mesos requirement/compatibility to 0.27.0

## 0.10.1 (Feb 17th 2016)

-   [\#205](https://github.com/jenkinsci/mesos-plugin/pull/205) - Reduce
    log verbosity
-   [\#206](https://github.com/jenkinsci/mesos-plugin/pull/206) - Add
    escape-by-default to jelly files
-   [\#207](https://github.com/jenkinsci/mesos-plugin/pull/207) - Fix
    equals method for MesosCloud
-   [\#208](https://github.com/jenkinsci/mesos-plugin/pull/208) - Slaves
    shouldn't try to reconnect

## 0.10.0 (Feb 8th 2016)

-   [\#191](https://github.com/jenkinsci/mesos-plugin/pull/191) - Set
    \_JAVA\_OPTIONS to have max heap equals to executor memory
-   [\#162](https://github.com/jenkinsci/mesos-plugin/pull/162) -
    Prevent offer starvation (with some follow-up
    in [\#199](https://github.com/jenkinsci/mesos-plugin/pull/199))
-   [\#160](https://github.com/jenkinsci/mesos-plugin/pull/160) - Allow
    specifying role used by the framework
-   [\#193](https://github.com/jenkinsci/mesos-plugin/pull/193) - Remove
    libs from target directory
-   [\#192](https://github.com/jenkinsci/mesos-plugin/pull/192) - Update
    vagrantfile
-   [\#195](https://github.com/jenkinsci/mesos-plugin/pull/195) - Fix
    copy constructor
-   [\#196](https://github.com/jenkinsci/mesos-plugin/pull/196) - Fix a
    deadlock case
-   [\#197](https://github.com/jenkinsci/mesos-plugin/pull/197) - NPE if
    credentialsId is null
-   [1cf6c7](https://github.com/jenkinsci/mesos-plugin/commit/1cf6c7d7182327471b81dcf08998688db540f77d) Add
    a minimum time to live for scheduler
-   [0015bc](https://github.com/jenkinsci/mesos-plugin/commit/0015bcb314b6ba488ad5224d6629aa79825bea18) Move
    driver initialization to the main thread

## 0.9.0 (Jan 8th 2016)

-   Integration with credentials plugin
-   Add jenkins label to mesos slave task's name
-   Disable adding a slave manually
-   Allow provisioning mesos slaves for jobs with no labels
-   Fix for RuntimeException during Scheduler execution
-   Bug fixes and Unit Test fixes