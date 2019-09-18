#!/usr/bin/env python2
"""
Reconfigures a Jenkins master running in Docker at container runtime.
"""

from __future__ import print_function

import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


def mesos_dns_taskname(jenkins_service_name, marathon_name, nginx_port):
    marathon_dns_url = 'http://{}.{}.mesos:{}'
    elems = jenkins_service_name.strip('/').split('/')
    elems.reverse()
    service_name = '-'.join(elems)
    return marathon_dns_url.format(service_name, marathon_name, nginx_port)


def populate_jenkins_config_xml(config_xml, name, port, role, user, marathon_name):
    """Modifies a Jenkins master's 'config.xml' at runtime. Essentially, this
    replaces certain configuration options of the Mesos plugin, such as the
    framework name and the Jenkins URL that agents use to connect back to the
    master.

    :param config_xml: the path to Jenkins' 'config.xml' file
    :param name: the name of the framework, e.g. 'jenkins'
    :param port: the Mesos port the task is running on
    :param role: The role passed to the internal Jenkins configuration that denotes which resources can be launched
    :param user: the user the task is running on
    :param marathon_name: the name of the Marathon framework the Jenkins master is deployed from. Change when using a MoM.
    """
    tree, root = _get_xml_root(config_xml)
    mesos = root.find('./clouds/org.jenkinsci.plugins.mesos.MesosCloud')

    assert mesos, "Cannot find MesosCloud node in {}".format(config_xml)

    #_find_and_set(mesos, './masterMasterUrl', master)
    _find_and_set(mesos, './frameworkName', name)
    # This used to be host and port. Switching over to DNS Name to address COPS-3395.
    _find_and_set(mesos, './jenkinsUrl', mesos_dns_taskname(name, marathon_name, port))
    _find_and_set(mesos, './role', role)
    _find_and_set(mesos, './agentUser', user)

    tree.write(config_xml)


def populate_jenkins_location_config(location_xml, url):
    """Modifies a Jenkins master's location config at runtime. This
    replaces the value of 'jenkinsUrl' with url.

    :param location_xml: the path to Jenkins'
        'jenkins.model.JenkinsLocationConfiguration.xml' file
    :type location_xml: str
    :param url: the Jenkins instance URL
    :type url: str
    """
    tree, root = _get_xml_root(location_xml)
    _find_and_set(root, 'jenkinsUrl', url)
    tree.write(location_xml)


def populate_known_hosts(hosts, dest_file):
    """Gather SSH public key from one or more hosts and write out the
    known_hosts file.

    :param hosts: a string of hosts separated by whitespace
    :param dest_file: absolute path to the SSH known hosts file
    """
    dest_dir = os.path.dirname(dest_file)

    if not os.path.exists(dest_dir):
        os.makedirs(dest_dir)

    command = ['ssh-keyscan'] + hosts.split()
    subprocess.call(
        command, stdout=open(dest_file, 'w'), stderr=open(os.devnull, 'w'))


def main():
    try:
        jenkins_agent_user = os.environ['JENKINS_AGENT_USER']
        jenkins_agent_role = os.environ['JENKINS_AGENT_ROLE']
        jenkins_home_dir = os.environ['JENKINS_HOME']
        jenkins_framework_name = os.environ['JENKINS_FRAMEWORK_NAME']
        marathon_nginx_port = os.environ['PORT0']
        ssh_known_hosts = os.environ['SSH_KNOWN_HOSTS']
        marathon_name = os.environ['MARATHON_NAME']
    except KeyError as e:
        # Since each of the environment variables above are set either in the
        # DCOS marathon.json or by Marathon itself, the user should never get
        # to this point.
        print("ERROR: missing required environment variable {}.".format(e.args[0]))
        return 1

    # optional environment variables
    jenkins_root_url = os.getenv(
        'JENKINS_ROOT_URL',
        mesos_dns_taskname(jenkins_framework_name, marathon_name, marathon_nginx_port))

    populate_jenkins_config_xml(
        os.path.join(jenkins_home_dir, 'config.xml'),
        jenkins_framework_name,
        marathon_nginx_port,
        jenkins_agent_role,
        jenkins_agent_user,
        marathon_name)

    populate_jenkins_location_config(os.path.join(
        jenkins_home_dir, 'jenkins.model.JenkinsLocationConfiguration.xml'),
        jenkins_root_url)

    populate_known_hosts(ssh_known_hosts, '/etc/ssh/ssh_known_hosts')


def _get_xml_root(config_xml):
    """Return the ET tree and root XML element.

    :param config_xml: path to config XML file
    :type config_xml: str
    :return: a tuple (tree,root)
    :rtype: tuple
    """
    tree = ET.parse(config_xml)
    root = tree.getroot()
    return tuple([tree, root])


def _find_and_set(element, term, new_text, write_if_empty=False):
    """Find the desired term within the XML element and replace
    its text with text.

    :param element: XML element
    :type element: xml.etree.ElementTree.Element
    :param term: XML element to find
    :type term: str
    :param new_text: New element text
    :type new_text: str
    :param write_if_empty : If set to True, the value is updated only if empty.
                            If set to False, the value is always updated.
    :type write_if_empty: bool
    """
    if not write_if_empty or write_if_empty and not element.find(term).text:
        element.find(term).text = new_text


if __name__ == '__main__':
    sys.exit(main())
