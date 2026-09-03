package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class GetCrawlersRequest {
    @JsonProperty("MaxResults")
    private Integer maxResults;

    @JsonProperty("NextToken")
    private String nextToken;

    public GetCrawlersRequest() {}

    public Integer getMaxResults() { return maxResults; }
    public void setMaxResults(Integer maxResults) { this.maxResults = maxResults; }

    public String getNextToken() { return nextToken; }
    public void setNextToken(String nextToken) { this.nextToken = nextToken; }
}
