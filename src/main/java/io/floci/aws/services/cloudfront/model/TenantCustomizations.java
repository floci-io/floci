package io.floci.aws.services.cloudfront.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * The customizations of a distribution tenant: the WAF web ACL and ACM certificate it overrides
 * from its multi-tenant distribution, and its geographic restriction. A null member is a
 * customization the tenant does not set.
 */
@RegisterForReflection
public class TenantCustomizations {

    private String webAclAction;
    private String webAclArn;
    private String certificateArn;
    private String geoRestrictionType;
    private List<String> geoLocations;

    public TenantCustomizations() {}

    public String getWebAclAction() { return webAclAction; }
    public void setWebAclAction(String webAclAction) { this.webAclAction = webAclAction; }

    public String getWebAclArn() { return webAclArn; }
    public void setWebAclArn(String webAclArn) { this.webAclArn = webAclArn; }

    public String getCertificateArn() { return certificateArn; }
    public void setCertificateArn(String certificateArn) { this.certificateArn = certificateArn; }

    public String getGeoRestrictionType() { return geoRestrictionType; }
    public void setGeoRestrictionType(String geoRestrictionType) { this.geoRestrictionType = geoRestrictionType; }

    public List<String> getGeoLocations() { return geoLocations; }
    public void setGeoLocations(List<String> geoLocations) { this.geoLocations = geoLocations; }

    public boolean isEmpty() {
        return webAclAction == null && certificateArn == null && geoRestrictionType == null;
    }
}
