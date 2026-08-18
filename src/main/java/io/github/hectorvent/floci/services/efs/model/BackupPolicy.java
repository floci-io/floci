package io.github.hectorvent.floci.services.efs.model;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public class BackupPolicy {
    private String status;
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
