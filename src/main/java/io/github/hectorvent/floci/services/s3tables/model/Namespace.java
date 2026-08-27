package io.github.hectorvent.floci.services.s3tables.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public class Namespace {
    private String name;
    private String createdAt;
    private Map<String, S3Table> tables = new LinkedHashMap<>();

    public Namespace() {
    }

    public Namespace(String name, String createdAt) {
        this.name = name;
        this.createdAt = createdAt;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public Map<String, S3Table> getTables() { return tables; }
    public void setTables(Map<String, S3Table> tables) { this.tables = tables == null ? new LinkedHashMap<>() : new LinkedHashMap<>(tables); }
}
