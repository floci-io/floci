package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.List;

@RegisterForReflection
public class Crawler {
    @JsonProperty("Classifiers")
    private List<String> classifiers;

    @JsonProperty("Configuration")
    private String configuration;

    @JsonProperty("CrawlElapsedTime")
    private Long crawlElapsedTime;

    @JsonProperty("CrawlerSecurityConfiguration")
    private String crawlerSecurityConfiguration;

    @JsonProperty("CreationTime")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant creationTime;

    @JsonProperty("DatabaseName")
    private String databaseName;

    @JsonProperty("Description")
    private String description;

    @JsonProperty("LakeFormationConfiguration")
    private LakeFormationConfiguration lakeFormationConfiguration;

    @JsonProperty("LastCrawl")
    private LastCrawlInfo lastCrawl;

    @JsonProperty("LastUpdated")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant lastUpdated;

    @JsonProperty("LineageConfiguration")
    private LineageConfiguration lineageConfiguration;

    @JsonProperty("Name")
    private String name;

    @JsonProperty("RecrawlPolicy")
    private RecrawlPolicy recrawlPolicy;

    @JsonProperty("Role")
    private String role;

    @JsonProperty("Schedule")
    private Schedule schedule;

    @JsonProperty("SchemaChangePolicy")
    private SchemaChangePolicy schemaChangePolicy;

    @JsonProperty("State")
    private String state;

    @JsonProperty("TablePrefix")
    private String tablePrefix;

    @JsonProperty("Targets")
    private CrawlerTargets targets;

    @JsonProperty("Version")
    private Long version;

    public Crawler() {}

    public List<String> getClassifiers() { return classifiers; }
    public void setClassifiers(List<String> classifiers) { this.classifiers = classifiers; }

    public String getConfiguration() { return configuration; }
    public void setConfiguration(String configuration) { this.configuration = configuration; }

    public Long getCrawlElapsedTime() { return crawlElapsedTime; }
    public void setCrawlElapsedTime(Long crawlElapsedTime) { this.crawlElapsedTime = crawlElapsedTime; }

    public String getCrawlerSecurityConfiguration() { return crawlerSecurityConfiguration; }
    public void setCrawlerSecurityConfiguration(String crawlerSecurityConfiguration) { this.crawlerSecurityConfiguration = crawlerSecurityConfiguration; }

    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }

    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public LakeFormationConfiguration getLakeFormationConfiguration() { return lakeFormationConfiguration; }
    public void setLakeFormationConfiguration(LakeFormationConfiguration lakeFormationConfiguration) { this.lakeFormationConfiguration = lakeFormationConfiguration; }

    public LastCrawlInfo getLastCrawl() { return lastCrawl; }
    public void setLastCrawl(LastCrawlInfo lastCrawl) { this.lastCrawl = lastCrawl; }

    public Instant getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(Instant lastUpdated) { this.lastUpdated = lastUpdated; }

    public LineageConfiguration getLineageConfiguration() { return lineageConfiguration; }
    public void setLineageConfiguration(LineageConfiguration lineageConfiguration) { this.lineageConfiguration = lineageConfiguration; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public RecrawlPolicy getRecrawlPolicy() { return recrawlPolicy; }
    public void setRecrawlPolicy(RecrawlPolicy recrawlPolicy) { this.recrawlPolicy = recrawlPolicy; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public Schedule getSchedule() { return schedule; }
    public void setSchedule(Schedule schedule) { this.schedule = schedule; }

    public SchemaChangePolicy getSchemaChangePolicy() { return schemaChangePolicy; }
    public void setSchemaChangePolicy(SchemaChangePolicy schemaChangePolicy) { this.schemaChangePolicy = schemaChangePolicy; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getTablePrefix() { return tablePrefix; }
    public void setTablePrefix(String tablePrefix) { this.tablePrefix = tablePrefix; }

    public CrawlerTargets getTargets() { return targets; }
    public void setTargets(CrawlerTargets targets) { this.targets = targets; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
