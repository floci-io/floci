package io.github.hectorvent.floci.services.lakeformation.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class ListPermissionsRequest {
    private String catalogId;
    private String includeRelated;
    private Integer maxResults;
    private String nextToken;
    private DataLakePrincipal principal;
    private Resource resource;
    private String resourceType;

    public String getCatalogId() {
        return catalogId;
    }

    public void setCatalogId(String catalogId) {
        this.catalogId = catalogId;
    }

    public String getIncludeRelated() {
        return includeRelated;
    }

    public void setIncludeRelated(String includeRelated) {
        this.includeRelated = includeRelated;
    }

    public Integer getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(Integer maxResults) {
        this.maxResults = maxResults;
    }

    public String getNextToken() {
        return nextToken;
    }

    public void setNextToken(String nextToken) {
        this.nextToken = nextToken;
    }

    public DataLakePrincipal getPrincipal() {
        return principal;
    }

    public void setPrincipal(DataLakePrincipal principal) {
        this.principal = principal;
    }

    public Resource getResource() {
        return resource;
    }

    public void setResource(Resource resource) {
        this.resource = resource;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }
}
