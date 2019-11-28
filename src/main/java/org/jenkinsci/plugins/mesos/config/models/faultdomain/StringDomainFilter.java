package org.jenkinsci.plugins.mesos.config.models.faultdomain;

import com.mesosphere.usi.core.models.faultdomain.DomainFilter;
import hudson.Extension;
import org.apache.mesos.v1.Protos.DomainInfo;
import org.kohsuke.stapler.DataBoundConstructor;

/** This is a simple {@link DomainFilter} that matches the agent region and zone with a string. */
public class StringDomainFilter extends DomainFilterModel implements DomainFilter {

  private final String region;
  private final String zone;

  @DataBoundConstructor
  public StringDomainFilter(String region, String zone) {
    this.region = region;
    this.zone = zone;
  }

  @Override
  public DomainFilter getFilter() {
    return this;
  }

  /**
   * Application of the domain filter.
   *
   * @param masterDomain The domain info of the master.
   * @param nodeDomain The domain info of and offer.
   * @return true if the region and zone is equal to the provided region and zone, false otherwise.
   */
  @Override
  public boolean apply(DomainInfo masterDomain, DomainInfo nodeDomain) {
    return this.region.equals(nodeDomain.getFaultDomain().getRegion().getName())
        && this.zone.equals(nodeDomain.getFaultDomain().getZone().getName());
  }

  @Extension
  public static final class DescriptorImpl extends DomainFilterModelDescriptor {

    public String getDisplayName() {
      return "String Matching";
    }
  }
}
