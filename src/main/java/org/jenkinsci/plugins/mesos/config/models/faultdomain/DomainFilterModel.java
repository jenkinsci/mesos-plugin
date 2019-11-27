package org.jenkinsci.plugins.mesos.config.models.faultdomain;

import com.mesosphere.usi.core.models.faultdomain.DomainFilter;
import hudson.model.AbstractDescribableImpl;

/**
 * A simple config model that enables a hetero descriptor list. See {@link StringDomainFilter},
 * {@link Any}, {@link Home} and the usage in MesosAgentSpecTemplate/config.jelly for usage.
 *
 * @see <a
 *     href="https://www.previous.cloudbees.com/blog/introducing-variability-jenkins-plugins">Introducing
 *     Variability into Jenkins Plugins</a>
 */
public abstract class DomainFilterModel extends AbstractDescribableImpl<DomainFilterModel> {
  public abstract DomainFilter getFilter();
}
