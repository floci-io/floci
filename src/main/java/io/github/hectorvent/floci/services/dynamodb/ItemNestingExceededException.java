package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.core.common.AwsException;

public class ItemNestingExceededException extends AwsException {

    public ItemNestingExceededException() {
        super("ValidationException", DynamoDbAttributeValueValidator.NESTING_EXCEEDED, 400);
    }
}
