package io.github.hectorvent.floci.services.efs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
@RegisterForReflection
public class DescribeAccessPointsRequest {
    private Integer maxResults;
    private String nextToken;
    private String accessPointId;
    private String fileSystemId;
    public Integer getMaxResults() { return maxResults; }
    public void setMaxResults(Integer maxResults) { this.maxResults = maxResults; }
    public String getNextToken() { return nextToken; }
    public void setNextToken(String nextToken) { this.nextToken = nextToken; }
    public String getAccessPointId() { return accessPointId; }
    public void setAccessPointId(String accessPointId) { this.accessPointId = accessPointId; }
    public String getFileSystemId() { return fileSystemId; }
    public void setFileSystemId(String fileSystemId) { this.fileSystemId = fileSystemId; }
}
