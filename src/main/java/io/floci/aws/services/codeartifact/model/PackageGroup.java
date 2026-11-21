package io.floci.aws.services.codeartifact.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A package group: a domain-wide (not per-repository) origin-control policy applied to every
 * package whose format/namespace/name coordinate matches {@code pattern}, per the CodeArtifact
 * User Guide's own package group definition syntax (see {@code PackageGroupPatterns}). Only three
 * restriction types ever appear in {@code restrictions}: {@code PUBLISH}, {@code
 * EXTERNAL_UPSTREAM}, and {@code INTERNAL_UPSTREAM}; a type missing from the map has never been
 * set and defaults to {@code INHERIT} the same way an explicit entry would, resolved the same way
 * {@code CodeArtifactService}'s own effective-mode computation already treats both.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class PackageGroup {
    private String pattern;
    private String arn;
    private String domainName;
    private String domainOwner;
    private String region;
    private String contactInfo;
    private String description;
    private long createdTime;
    private Map<String, PackageGroupRestriction> restrictions = new LinkedHashMap<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public PackageGroup() {
    }

    public PackageGroup(String pattern, String arn, String domainName, String domainOwner, String region,
                         String contactInfo, String description, long createdTime) {
        this.pattern = pattern;
        this.arn = arn;
        this.domainName = domainName;
        this.domainOwner = domainOwner;
        this.region = region;
        this.contactInfo = contactInfo;
        this.description = description;
        this.createdTime = createdTime;
    }

    public String getPattern() {
        return pattern;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getDomainName() {
        return domainName;
    }

    public void setDomainName(String domainName) {
        this.domainName = domainName;
    }

    public String getDomainOwner() {
        return domainOwner;
    }

    public void setDomainOwner(String domainOwner) {
        this.domainOwner = domainOwner;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getContactInfo() {
        return contactInfo;
    }

    public void setContactInfo(String contactInfo) {
        this.contactInfo = contactInfo;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public long getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTime(long createdTime) {
        this.createdTime = createdTime;
    }

    public Map<String, PackageGroupRestriction> getRestrictions() {
        return restrictions;
    }

    public void setRestrictions(Map<String, PackageGroupRestriction> restrictions) {
        this.restrictions = restrictions;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags;
    }
}
