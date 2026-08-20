package io.github.hectorvent.floci.services.lakeformation.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

@RegisterForReflection
public class ListLFTagsResponse {
    private List<LFTagPair> lfTags;
    private String nextToken;

    public List<LFTagPair> getLfTags() {
        return lfTags;
    }

    public void setLfTags(List<LFTagPair> lfTags) {
        this.lfTags = lfTags;
    }

    public String getNextToken() {
        return nextToken;
    }

    public void setNextToken(String nextToken) {
        this.nextToken = nextToken;
    }
}
