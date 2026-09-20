package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.core.common.AwsException;

/** An update over 400KB, which AWS can only find once the transaction is running, so it cancels. */
public class UpdatedItemTooLargeException extends AwsException {

    public UpdatedItemTooLargeException() {
        super("ValidationException", DynamoDbItemSize.UPDATE_EXCEEDED, 400);
    }
}
