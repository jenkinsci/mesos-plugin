package org.jenkinsci.plugins.mesos.config.models.faultdomain;

import com.mesosphere.usi.core.models.faultdomain.DomainFilter;
import com.mesosphere.usi.core.models.faultdomain.HomeRegionFilter$;
import hudson.Extension;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * This is just a wrapper around {@link com.mesosphere.usi.core.models.faultdomain.HomeRegionFilter}
 * since the USI model does not implement {@link hudson.model.Describable}.
 */
public class Home extends DomainFilterModel {
  @DataBoundConstructor
  public Home() {}

  @Override
  public DomainFilter getFilter() {
    return HomeRegionFilter$.MODULE$;
  }

  @Extension
  public static class DescriptorImpl extends DomainFilterModelDescriptor {
    public String getDisplayName() {
      return "Home Region";
    }
  }
}
