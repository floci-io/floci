package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
public class CreateCrawlerRequest {
    @JsonProperty("Classifiers")
    private List<String> classifiers;

    @JsonProperty("Configuration")
    private String configuration;

    @JsonProperty("CrawlerSecurityConfiguration")
    private String crawlerSecurityConfiguration;

    @JsonProperty("DatabaseName")
    private String databaseName;

    @JsonProperty("Description")
    private String description;

    @JsonProperty("LakeFormationConfiguration")
    private LakeFormationConfiguration lakeFormationConfiguration;

    @JsonProperty("LineageConfiguration")
    private LineageConfiguration lineageConfiguration;

    @JsonProperty("Name")
    private String name;

    @JsonProperty("RecrawlPolicy")
    private RecrawlPolicy recrawlPolicy;

    @JsonProperty("Role")
    private String role;

    @JsonProperty("Schedule")
    private String schedule;

    @JsonProperty("SchemaChangePolicy")
    private SchemaChangePolicy schemaChangePolicy;

    @JsonProperty("TablePrefix")
    private String tablePrefix;

    @JsonProperty("Tags")
    private Map<String, String> tags;

    @JsonProperty("Targets")
    private CrawlerTargets targets;

    public CreateCrawlerRequest() {}

    public List<String> getClassifiers() { return classifiers; }
    public void setClassifiers(List<String> classifiers) { this.classifiers = classifiers; }

    public String getConfiguration() { return configuration; }
    public void setConfiguration(String configuration) { this.configuration = configuration; }

    public String getCrawlerSecurityConfiguration() { return crawlerSecurityConfiguration; }
    public void setCrawlerSecurityConfiguration(String crawlerSecurityConfiguration) { this.crawlerSecurityConfiguration = crawlerSecurityConfiguration; }

    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public LakeFormationConfiguration getLakeFormationConfiguration() { return lakeFormationConfiguration; }
    public void setLakeFormationConfiguration(LakeFormationConfiguration lakeFormationConfiguration) { this.lakeFormationConfiguration = lakeFormationConfiguration; }

    public LineageConfiguration getLineageConfiguration() { return lineageConfiguration; }
    public void setLineageConfiguration(LineageConfiguration lineageConfiguration) { this.lineageConfiguration = lineageConfiguration; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public RecrawlPolicy getRecrawlPolicy() { return recrawlPolicy; }
    public void setRecrawlPolicy(RecrawlPolicy recrawlPolicy) { this.recrawlPolicy = recrawlPolicy; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getSchedule() { return schedule; }
    public void setSchedule(String schedule) { this.schedule = schedule; }

    public SchemaChangePolicy getSchemaChangePolicy() { return schemaChangePolicy; }
    public void setSchemaChangePolicy(SchemaChangePolicy schemaChangePolicy) { this.schemaChangePolicy = schemaChangePolicy; }

    public String getTablePrefix() { return tablePrefix; }
    public void setTablePrefix(String tablePrefix) { this.tablePrefix = tablePrefix; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public CrawlerTargets getTargets() { return targets; }
    public void setTargets(CrawlerTargets targets) { this.targets = targets; }
}
