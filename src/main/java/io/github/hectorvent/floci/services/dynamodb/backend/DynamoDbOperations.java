package io.github.hectorvent.floci.services.dynamodb.backend;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The one raw entry point for DynamoDB and DynamoDB Streams wire operations. {@code action} is a
 * free string on purpose, so an alternative engine can forward every operation it supports. An AWS
 * error surfaces either as a thrown {@code AwsException}, which propagates unchanged, or as a
 * non-2xx {@link Reply} carrying the AWS error body. Implementations must not mutate the request body.
 *
 * <p>{@code execute} throws {@code Exception} because the native handler throws checked Jackson
 * exceptions, which the CBOR controller maps to a 400 SerializationException; wrapping them would
 * change wire behavior.
 */
public interface DynamoDbOperations {

    enum Api { DYNAMODB, DYNAMODB_STREAMS }

    record Scope(String accountId, String region) {
        public Scope {
            if (accountId == null || accountId.isBlank()) {
                throw new IllegalArgumentException("accountId is required");
            }
            if (region == null || region.isBlank()) {
                throw new IllegalArgumentException("region is required");
            }
        }
    }

    record Call(Scope scope, Api api, String action, JsonNode body) {
        public Call {
            Objects.requireNonNull(scope, "scope is required");
            Objects.requireNonNull(api, "api is required");
            Objects.requireNonNull(action, "action is required");
        }
    }

    record Reply(int status, JsonNode body, Map<String, List<String>> headers) {
        public Reply {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    Reply execute(Call call) throws Exception;
}
