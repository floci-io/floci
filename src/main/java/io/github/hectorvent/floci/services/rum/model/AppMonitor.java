package io.github.hectorvent.floci.services.rum.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/** A CloudWatch RUM app monitor. Serialized with AWS's UpperCamelCase member names. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public class AppMonitor {
    private String id;
    private String name;
    private String domain;
    private List<String> domainList;
    private String state;
    private String platform;
    private String created;
    private String lastModified;
    private Map<String, String> tags;
    private JsonNode appMonitorConfiguration;
    private JsonNode dataStorage;
    private JsonNode customEvents;
    private JsonNode deobfuscationConfiguration;

    public AppMonitor() {
    }

    public AppMonitor(
            String id,
            String name,
            String domain,
            List<String> domainList,
            String state,
            String platform,
            String created,
            String lastModified,
            Map<String, String> tags,
            JsonNode appMonitorConfiguration,
            JsonNode dataStorage,
            JsonNode customEvents,
            JsonNode deobfuscationConfiguration) {
        this.id = id;
        this.name = name;
        this.domain = domain;
        setDomainList(domainList);
        this.state = state;
        this.platform = platform;
        this.created = created;
        this.lastModified = lastModified;
        setTags(tags);
        setAppMonitorConfiguration(appMonitorConfiguration);
        setDataStorage(dataStorage);
        setCustomEvents(customEvents);
        setDeobfuscationConfiguration(deobfuscationConfiguration);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public List<String> getDomainList() {
        return domainList;
    }

    public void setDomainList(List<String> domainList) {
        this.domainList = domainList == null ? null : List.copyOf(domainList);
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getCreated() {
        return created;
    }

    public void setCreated(String created) {
        this.created = created;
    }

    public String getLastModified() {
        return lastModified;
    }

    public void setLastModified(String lastModified) {
        this.lastModified = lastModified;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags == null ? null : Map.copyOf(tags);
    }

    public JsonNode getAppMonitorConfiguration() {
        return copy(appMonitorConfiguration);
    }

    public void setAppMonitorConfiguration(JsonNode appMonitorConfiguration) {
        this.appMonitorConfiguration = copy(appMonitorConfiguration);
    }

    public JsonNode getDataStorage() {
        return copy(dataStorage);
    }

    public void setDataStorage(JsonNode dataStorage) {
        this.dataStorage = copy(dataStorage);
    }

    public JsonNode getCustomEvents() {
        return copy(customEvents);
    }

    public void setCustomEvents(JsonNode customEvents) {
        this.customEvents = copy(customEvents);
    }

    public JsonNode getDeobfuscationConfiguration() {
        return copy(deobfuscationConfiguration);
    }

    public void setDeobfuscationConfiguration(JsonNode deobfuscationConfiguration) {
        this.deobfuscationConfiguration = copy(deobfuscationConfiguration);
    }

    private static JsonNode copy(JsonNode value) {
        return value == null ? null : value.deepCopy();
    }
}
