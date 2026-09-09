package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class GetCrawlerResponse {
    @JsonProperty("Crawler")
    private Crawler crawler;

    public GetCrawlerResponse() {}

    public GetCrawlerResponse(Crawler crawler) {
        this.crawler = crawler;
    }

    public Crawler getCrawler() { return crawler; }
    public void setCrawler(Crawler crawler) { this.crawler = crawler; }
}
