package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public class GetCrawlersResponse {
    @JsonProperty("Crawlers")
    private List<Crawler> crawlers;

    @JsonProperty("NextToken")
    private String nextToken;

    public GetCrawlersResponse() {}

    public List<Crawler> getCrawlers() { return crawlers; }
    public void setCrawlers(List<Crawler> crawlers) { this.crawlers = crawlers; }

    public String getNextToken() { return nextToken; }
    public void setNextToken(String nextToken) { this.nextToken = nextToken; }
}
