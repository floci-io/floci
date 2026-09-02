package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class LineageConfiguration {
    @JsonProperty("CrawlerLineageSettings")
    private String crawlerLineageSettings;

    public LineageConfiguration() {}

    public String getCrawlerLineageSettings() { return crawlerLineageSettings; }
    public void setCrawlerLineageSettings(String crawlerLineageSettings) { this.crawlerLineageSettings = crawlerLineageSettings; }
}
