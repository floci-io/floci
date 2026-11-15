package io.floci.aws.services.dynamodb;

import io.floci.aws.core.common.AwsException;

/** An update operand the item cannot serve. A transact member carrying one cancels instead of failing up front. */
public class UpdateOperandException extends AwsException {

    private UpdateOperandException(String message) {
        super("ValidationException", message, 400);
    }

    static UpdateOperandException absentAttribute() {
        return new UpdateOperandException(
                "The provided expression refers to an attribute that does not exist in the item");
    }

    static UpdateOperandException incorrectType() {
        return new UpdateOperandException("An operand in the update expression has an incorrect data type");
    }
}
