package io.floci.aws.services.ses.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class BulkEmailEntryResult {

    public enum Status {
        SUCCESS,
        MESSAGE_REJECTED,
        MAIL_FROM_DOMAIN_NOT_VERIFIED,
        CONFIGURATION_SET_DOES_NOT_EXIST,
        TEMPLATE_DOES_NOT_EXIST,
        ACCOUNT_SUSPENDED,
        ACCOUNT_THROTTLED,
        ACCOUNT_DAILY_QUOTA_EXCEEDED,
        INVALID_SENDING_POOL_NAME,
        ACCOUNT_SENDING_PAUSED,
        CONFIGURATION_SET_SENDING_PAUSED,
        INVALID_PARAMETER,
        TRANSIENT_FAILURE,
        FAILED;

        public String toV1String() {
            if (this == INVALID_PARAMETER) {
                return "InvalidParameterValue";
            }
            StringBuilder sb = new StringBuilder();
            for (String part : name().split("_")) {
                sb.append(part.charAt(0));
                sb.append(part.substring(1).toLowerCase());
            }
            return sb.toString();
        }
    }

    private final Status status;
    private final String messageId;
    private final String error;

    private BulkEmailEntryResult(Status status, String messageId, String error) {
        this.status = status;
        this.messageId = messageId;
        this.error = error;
    }

    public static BulkEmailEntryResult success(String messageId) {
        return new BulkEmailEntryResult(Status.SUCCESS, messageId, null);
    }

    public static BulkEmailEntryResult failure(Status status, String error) {
        return new BulkEmailEntryResult(status, null, error);
    }

    public Status getStatus() {
        return status;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getError() {
        return error;
    }
}
