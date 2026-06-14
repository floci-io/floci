package io.github.hectorvent.floci.services.wafv2.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** WAF v2 regex pattern set. {@code regularExpressionList} holds the regex strings. */
@RegisterForReflection
public class RegexPatternSet {

    private String id;
    private String name;
    private String arn;
    private String scope;
    private String description;
    private List<String> regularExpressionList = new ArrayList<>();
    private String lockToken;
    private String region;
    private Map<String, String> tags = new HashMap<>();

    public RegexPatternSet() {}

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

    public List<String> getRegularExpressionList() { return regularExpressionList; }
    public void setRegularExpressionList(List<String> regularExpressionList) {
        this.regularExpressionList = regularExpressionList;
    }

    public String getLockToken() { return lockToken; }
    public void setLockToken(String lockToken) { this.lockToken = lockToken; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
