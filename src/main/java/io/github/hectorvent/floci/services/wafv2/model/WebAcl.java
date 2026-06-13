package io.github.hectorvent.floci.services.wafv2.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WAF v2 Web ACL. Recursive sub-structures (DefaultAction, Rules, VisibilityConfig,
 * CaptchaConfig, ChallengeConfig, AssociationConfig, CustomResponseBodies) are stored
 * as raw JSON strings and re-emitted verbatim — Phase 1 does not parse rule logic.
 * {@code lockToken} backs the optimistic-concurrency contract; it is returned beside
 * the resource on Get and rotated on Update, never embedded in the wire WebACL shape.
 */
@RegisterForReflection
public class WebAcl {

    private String id;
    private String name;
    private String arn;
    private String scope;
    private String description;
    private String defaultAction;
    private String rules;
    private String visibilityConfig;
    private long capacity;
    private String customResponseBodies;
    private String captchaConfig;
    private String challengeConfig;
    private List<String> tokenDomains = new ArrayList<>();
    private String associationConfig;
    private String dataProtectionConfig;
    private String labelNamespace;
    private String lockToken;
    private String region;
    private Instant creationTime;
    private Map<String, String> tags = new HashMap<>();

    public WebAcl() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getDefaultAction() { return defaultAction; }
    public void setDefaultAction(String defaultAction) { this.defaultAction = defaultAction; }

    public String getRules() { return rules; }
    public void setRules(String rules) { this.rules = rules; }

    public String getVisibilityConfig() { return visibilityConfig; }
    public void setVisibilityConfig(String visibilityConfig) { this.visibilityConfig = visibilityConfig; }

    public long getCapacity() { return capacity; }
    public void setCapacity(long capacity) { this.capacity = capacity; }

    public String getCustomResponseBodies() { return customResponseBodies; }
    public void setCustomResponseBodies(String customResponseBodies) { this.customResponseBodies = customResponseBodies; }

    public String getCaptchaConfig() { return captchaConfig; }
    public void setCaptchaConfig(String captchaConfig) { this.captchaConfig = captchaConfig; }

    public String getChallengeConfig() { return challengeConfig; }
    public void setChallengeConfig(String challengeConfig) { this.challengeConfig = challengeConfig; }

    public List<String> getTokenDomains() { return tokenDomains; }
    public void setTokenDomains(List<String> tokenDomains) { this.tokenDomains = tokenDomains; }

    public String getAssociationConfig() { return associationConfig; }
    public void setAssociationConfig(String associationConfig) { this.associationConfig = associationConfig; }

    public String getDataProtectionConfig() { return dataProtectionConfig; }
    public void setDataProtectionConfig(String dataProtectionConfig) { this.dataProtectionConfig = dataProtectionConfig; }

    public String getLabelNamespace() { return labelNamespace; }
    public void setLabelNamespace(String labelNamespace) { this.labelNamespace = labelNamespace; }

    public String getLockToken() { return lockToken; }
    public void setLockToken(String lockToken) { this.lockToken = lockToken; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
