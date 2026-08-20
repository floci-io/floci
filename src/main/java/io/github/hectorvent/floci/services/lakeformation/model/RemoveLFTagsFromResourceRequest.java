package io.github.hectorvent.floci.services.lakeformation.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

@RegisterForReflection
public class RemoveLFTagsFromResourceRequest {
    private String catalogId;
    private List<LFTagPair> lfTags;
    private Resource resource;

    public String getCatalogId() {
        return catalogId;
    }

    public void setCatalogId(String catalogId) {
        this.catalogId = catalogId;
    }

    public List<LFTagPair> getLfTags() {
        return lfTags;
    }

    public void setLfTags(List<LFTagPair> lfTags) {
        this.lfTags = lfTags;
    }

    public Resource getResource() {
        return resource;
    }

    public void setResource(Resource resource) {
        this.resource = resource;
    }
}
