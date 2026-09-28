package io.github.hectorvent.floci.services.dynamodb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SearchSchemaElement {

    private String attributeName;
    private String searchSchemaElementType; // HASH or INLINE_FILTER

    public SearchSchemaElement() {}

    public SearchSchemaElement(String attributeName, String searchSchemaElementType) {
        this.attributeName = attributeName;
        this.searchSchemaElementType = searchSchemaElementType;
    }

    public String getAttributeName() { return attributeName; }
    public void setAttributeName(String attributeName) { this.attributeName = attributeName; }

    public String getSearchSchemaElementType() { return searchSchemaElementType; }
    public void setSearchSchemaElementType(String searchSchemaElementType) {
        this.searchSchemaElementType = searchSchemaElementType;
    }
}
