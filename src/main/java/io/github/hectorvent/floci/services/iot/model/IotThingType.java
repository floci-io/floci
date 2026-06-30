package io.github.hectorvent.floci.services.iot.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class IotThingType {

    private String thingTypeName;
    private String thingTypeArn;
    private String thingTypeId;
    private String description;
    private List<String> searchableAttributes = new ArrayList<>();
    private boolean deprecated;
    private Instant creationDate;
    private Instant deprecatedDate;

    public String getThingTypeName() { return thingTypeName; }
    public void setThingTypeName(String thingTypeName) { this.thingTypeName = thingTypeName; }

    public String getThingTypeArn() { return thingTypeArn; }
    public void setThingTypeArn(String thingTypeArn) { this.thingTypeArn = thingTypeArn; }

    public String getThingTypeId() { return thingTypeId; }
    public void setThingTypeId(String thingTypeId) { this.thingTypeId = thingTypeId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getSearchableAttributes() { return searchableAttributes; }
    public void setSearchableAttributes(List<String> searchableAttributes) { this.searchableAttributes = searchableAttributes == null ? new ArrayList<>() : new ArrayList<>(searchableAttributes); }

    public boolean isDeprecated() { return deprecated; }
    public void setDeprecated(boolean deprecated) { this.deprecated = deprecated; }

    public Instant getCreationDate() { return creationDate; }
    public void setCreationDate(Instant creationDate) { this.creationDate = creationDate; }

    public Instant getDeprecatedDate() { return deprecatedDate; }
    public void setDeprecatedDate(Instant deprecatedDate) { this.deprecatedDate = deprecatedDate; }
}
