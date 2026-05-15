package io.github.hectorvent.floci.services.bcmdataexports.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.Map;

/**
 * AWS BCM Data Exports {@code Export} record.
 *
 * @see <a href="https://docs.aws.amazon.com/aws-cost-management/latest/APIReference/API_bcm-data-exports_Export.html">Export</a>
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Export {

    private String exportArn;
    private String name;
    private String description;
    private DataQuery dataQuery;
    private DestinationConfiguration destinationConfigurations;
    private RefreshCadence refreshCadence;
    private long createdAt;
    private long lastUpdatedAt;
    private String exportStatus;            // HEALTHY | UNHEALTHY
    private Map<String, String> resourceTags = new HashMap<>();
    /**
     * Snapshot of the account that owned the request that created this export.
     * Used by the background emission scheduler to locate the export under the
     * right account partition. Not surfaced in AWS wire responses.
     */
    private String ownerAccountId;

    public Export() {
    }

    public String getExportArn() { return exportArn; }
    public void setExportArn(String v) { this.exportArn = v; }

    public String getName() { return name; }
    public void setName(String v) { this.name = v; }

    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }

    public DataQuery getDataQuery() { return dataQuery; }
    public void setDataQuery(DataQuery v) { this.dataQuery = v; }

    public DestinationConfiguration getDestinationConfigurations() { return destinationConfigurations; }
    public void setDestinationConfigurations(DestinationConfiguration v) { this.destinationConfigurations = v; }

    public RefreshCadence getRefreshCadence() { return refreshCadence; }
    public void setRefreshCadence(RefreshCadence v) { this.refreshCadence = v; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long v) { this.createdAt = v; }

    public long getLastUpdatedAt() { return lastUpdatedAt; }
    public void setLastUpdatedAt(long v) { this.lastUpdatedAt = v; }

    public String getExportStatus() { return exportStatus; }
    public void setExportStatus(String v) { this.exportStatus = v; }

    public Map<String, String> getResourceTags() { return resourceTags; }
    public void setResourceTags(Map<String, String> v) { this.resourceTags = v; }

    public String getOwnerAccountId() { return ownerAccountId; }
    public void setOwnerAccountId(String v) { this.ownerAccountId = v; }
}
