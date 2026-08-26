package io.github.hectorvent.floci.services.lakeformation.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
@RegisterForReflection
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UpdateLFTagRequest {
    private String catalogId;
    private String tagKey;
    private List<String> tagValuesToAdd;
    private List<String> tagValuesToDelete;

    public String getCatalogId() {
        return catalogId;
    }

    public void setCatalogId(String catalogId) {
        this.catalogId = catalogId;
    }

    public String getTagKey() {
        return tagKey;
    }

    public void setTagKey(String tagKey) {
        this.tagKey = tagKey;
    }

    public List<String> getTagValuesToAdd() {
        return tagValuesToAdd;
    }

    public void setTagValuesToAdd(List<String> tagValuesToAdd) {
        this.tagValuesToAdd = tagValuesToAdd;
    }

    public List<String> getTagValuesToDelete() {
        return tagValuesToDelete;
    }

    public void setTagValuesToDelete(List<String> tagValuesToDelete) {
        this.tagValuesToDelete = tagValuesToDelete;
    }
}
