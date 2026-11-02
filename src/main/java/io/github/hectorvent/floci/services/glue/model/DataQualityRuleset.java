package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * A data quality ruleset as GetDataQualityRuleset reports it: a named DQDL ruleset, optionally
 * bound to a table. Floci stores rulesets; it does not evaluate them.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DataQualityRuleset {

    /** The table a ruleset is written for. */
    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TargetTable(@JsonProperty("TableName") String tableName,
                              @JsonProperty("DatabaseName") String databaseName,
                              @JsonProperty("CatalogId") String catalogId) {}

    private String name;
    private String description;
    private String ruleset;
    private TargetTable targetTable;
    private String dataQualitySecurityConfiguration;
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant createdOn;
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant lastModifiedOn;

    public DataQualityRuleset() {}

    @JsonProperty("Name")
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    @JsonProperty("Description")
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    @JsonProperty("Ruleset")
    public String getRuleset() { return ruleset; }
    public void setRuleset(String ruleset) { this.ruleset = ruleset; }

    @JsonProperty("TargetTable")
    public TargetTable getTargetTable() { return targetTable; }
    public void setTargetTable(TargetTable targetTable) { this.targetTable = targetTable; }

    @JsonProperty("DataQualitySecurityConfiguration")
    public String getDataQualitySecurityConfiguration() { return dataQualitySecurityConfiguration; }
    public void setDataQualitySecurityConfiguration(String name) { this.dataQualitySecurityConfiguration = name; }

    @JsonProperty("CreatedOn")
    public Instant getCreatedOn() { return createdOn; }
    public void setCreatedOn(Instant createdOn) { this.createdOn = createdOn; }

    @JsonProperty("LastModifiedOn")
    public Instant getLastModifiedOn() { return lastModifiedOn; }
    public void setLastModifiedOn(Instant lastModifiedOn) { this.lastModifiedOn = lastModifiedOn; }
}
