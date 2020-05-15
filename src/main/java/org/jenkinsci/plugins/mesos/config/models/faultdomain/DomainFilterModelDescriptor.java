package org.jenkinsci.plugins.mesos.config.models.faultdomain;

import hudson.model.Descriptor;

/**
 * Descriptor for instances of a domain filter. This is used in the agent spec template to collect
 * all descriptors by their base class.
 */
public abstract class DomainFilterModelDescriptor extends Descriptor<DomainFilterModel> {}
