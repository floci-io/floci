package io.github.hectorvent.floci.services.efs.model;

import java.util.List;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public class DescribeAccessPointsResponse {
    private List<AccessPointDescription> accessPoints;
    private String nextToken;
    public List<AccessPointDescription> getAccessPoints() { return accessPoints; }
    public void setAccessPoints(List<AccessPointDescription> accessPoints) { this.accessPoints = accessPoints; }
    public String getNextToken() { return nextToken; }
    public void setNextToken(String nextToken) { this.nextToken = nextToken; }
}
