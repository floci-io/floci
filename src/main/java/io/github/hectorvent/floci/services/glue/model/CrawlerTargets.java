package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public class CrawlerTargets {
    @JsonProperty("CatalogTargets")
    private List<CatalogTarget> catalogTargets;

    @JsonProperty("DeltaTargets")
    private List<DeltaTarget> deltaTargets;

    @JsonProperty("DynamoDBTargets")
    private List<DynamoDBTarget> dynamoDBTargets;

    @JsonProperty("HudiTargets")
    private List<HudiTarget> hudiTargets;

    @JsonProperty("IcebergTargets")
    private List<IcebergTarget> icebergTargets;

    @JsonProperty("JdbcTargets")
    private List<JdbcTarget> jdbcTargets;

    @JsonProperty("MongoDBTargets")
    private List<MongoDBTarget> mongoDBTargets;

    @JsonProperty("S3Targets")
    private List<S3Target> s3Targets;

    public CrawlerTargets() {}

    public List<CatalogTarget> getCatalogTargets() { return catalogTargets; }
    public void setCatalogTargets(List<CatalogTarget> catalogTargets) { this.catalogTargets = catalogTargets; }

    public List<DeltaTarget> getDeltaTargets() { return deltaTargets; }
    public void setDeltaTargets(List<DeltaTarget> deltaTargets) { this.deltaTargets = deltaTargets; }

    public List<DynamoDBTarget> getDynamoDBTargets() { return dynamoDBTargets; }
    public void setDynamoDBTargets(List<DynamoDBTarget> dynamoDBTargets) { this.dynamoDBTargets = dynamoDBTargets; }

    public List<HudiTarget> getHudiTargets() { return hudiTargets; }
    public void setHudiTargets(List<HudiTarget> hudiTargets) { this.hudiTargets = hudiTargets; }

    public List<IcebergTarget> getIcebergTargets() { return icebergTargets; }
    public void setIcebergTargets(List<IcebergTarget> icebergTargets) { this.icebergTargets = icebergTargets; }

    public List<JdbcTarget> getJdbcTargets() { return jdbcTargets; }
    public void setJdbcTargets(List<JdbcTarget> jdbcTargets) { this.jdbcTargets = jdbcTargets; }

    public List<MongoDBTarget> getMongoDBTargets() { return mongoDBTargets; }
    public void setMongoDBTargets(List<MongoDBTarget> mongoDBTargets) { this.mongoDBTargets = mongoDBTargets; }

    public List<S3Target> getS3Targets() { return s3Targets; }
    public void setS3Targets(List<S3Target> s3Targets) { this.s3Targets = s3Targets; }
}
