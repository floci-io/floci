package io.floci.aws.services.wafv2.model;

import java.util.Map;

/** What the four resources WAF v2 stores share: a web ACL, an IP set, a regex pattern set and a rule group. */
public interface WafV2Resource {

    String getId();

    String getName();

    String getScope();

    String getArn();

    /** The Region the resource was created in, which a resource stored before WAF v2 kept it may lack. */
    String getRegion();

    Map<String, String> getTags();

    void setLockToken(String lockToken);
}
