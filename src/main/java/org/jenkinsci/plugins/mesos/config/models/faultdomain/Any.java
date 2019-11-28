package org.jenkinsci.plugins.mesos.config.models.faultdomain;

import com.mesosphere.usi.core.models.faultdomain.AnyDomain$;
import com.mesosphere.usi.core.models.faultdomain.DomainFilter;
import hudson.Extension;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * This is just a wrapper around {@link com.mesosphere.usi.core.models.faultdomain.AnyDomain} since
 * the USI model does not implement {@link hudson.model.Describable}.
 */
public class Any extends DomainFilterModel {
  @DataBoundConstructor
  public Any() {}

  @Override
  public DomainFilter getFilter() {
    return AnyDomain$.MODULE$;
  }

  @Extension
  public static class DescriptorImpl extends DomainFilterModelDescriptor {
    public String getDisplayName() {
      return "Any Domain";
    }
  }
}
