package io.floci.aws.services.dynamodb;

import io.floci.aws.core.common.AwsException;

public class ItemNestingExceededException extends AwsException {

    public ItemNestingExceededException() {
        super("ValidationException", DynamoDbAttributeValueValidator.NESTING_EXCEEDED, 400);
    }
}
