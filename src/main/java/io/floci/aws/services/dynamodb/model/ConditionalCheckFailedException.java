package io.floci.aws.services.dynamodb.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;


public class ConditionalCheckFailedException extends AwsException {

    private final JsonNode item;

    public ConditionalCheckFailedException(JsonNode Item) {
        super("ConditionalCheckFailedException", "The conditional request failed", 400);
        this.item = Item;
    }

    public JsonNode getItem() {
        return item;
    }
}
