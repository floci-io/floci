package io.github.hectorvent.floci.services.efs.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public class DeleteAccessPointRequest {
    private String accessPointId;
    public String getAccessPointId() { return accessPointId; }
    public void setAccessPointId(String accessPointId) { this.accessPointId = accessPointId; }
}
