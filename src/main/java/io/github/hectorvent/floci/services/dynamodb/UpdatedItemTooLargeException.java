package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.core.common.AwsException;

/** An update over 400KB. A transact member carrying one cancels instead of failing up front. */
public class UpdatedItemTooLargeException extends AwsException {

    public UpdatedItemTooLargeException() {
        super("ValidationException", DynamoDbItemSize.UPDATE_EXCEEDED, 400);
    }
}
