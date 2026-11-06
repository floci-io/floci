package io.floci.aws.services.sqs.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class MessageAttributeValue {

    private String stringValue;
    private byte[] binaryValue;
    private String dataType;

    public MessageAttributeValue() {}

    public MessageAttributeValue(String stringValue, String dataType) {
        this.stringValue = stringValue;
        this.dataType = dataType;
    }

    public MessageAttributeValue(byte[] binaryValue, String dataType) {
        this.binaryValue = binaryValue;
        this.dataType = dataType;
    }

    public String getStringValue() { return stringValue; }
    public void setStringValue(String stringValue) { this.stringValue = stringValue; }

    public byte[] getBinaryValue() { return binaryValue; }
    public void setBinaryValue(byte[] binaryValue) { this.binaryValue = binaryValue; }

    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }

    @Override
    public String toString() {
        if (stringValue != null) {
            return stringValue;
        }
        if (binaryValue != null) {
            return "<binary:" + binaryValue.length + "B>";
        }
        return "";
    }
}
