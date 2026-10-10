package io.github.hectorvent.floci.services.efs.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
@RegisterForReflection
public class DescribeTagsResponse {

    private String marker;
    private String nextMarker;
    private List<Tag> tags;

    public String getMarker() {
        return marker;
    }

    public void setMarker(String marker) {
        this.marker = marker;
    }

    public String getNextMarker() {
        return nextMarker;
    }

    public void setNextMarker(String nextMarker) {
        this.nextMarker = nextMarker;
    }

    public List<Tag> getTags() {
        return tags;
    }

    public void setTags(List<Tag> tags) {
        this.tags = tags;
    }
}