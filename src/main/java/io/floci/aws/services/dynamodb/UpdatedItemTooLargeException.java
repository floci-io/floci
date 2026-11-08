package io.floci.aws.services.dynamodb;

import io.floci.aws.core.common.AwsException;

/** An update over 400KB. A transact member carrying one cancels instead of failing up front. */
public class UpdatedItemTooLargeException extends AwsException {

    public UpdatedItemTooLargeException() {
        super("ValidationException", DynamoDbItemSize.UPDATE_EXCEEDED, 400);
    }
}
