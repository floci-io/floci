package io.floci.aws.services.dynamodb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class KeySchemaElement {

    private String attributeName;
    private String keyType; // HASH or RANGE

    public KeySchemaElement() {}

    public KeySchemaElement(String attributeName, String keyType) {
        this.attributeName = attributeName;
        this.keyType = keyType;
    }

    public String getAttributeName() { return attributeName; }
    public void setAttributeName(String attributeName) { this.attributeName = attributeName; }

    public String getKeyType() { return keyType; }
    public void setKeyType(String keyType) { this.keyType = keyType; }
}
