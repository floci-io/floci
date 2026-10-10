package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.ServicePrincipals;

/**
 * The {@code userIdentity} AWS writes on the records of an item that time to live deleted. A consumer
 * tells a TTL deletion from a {@code DeleteItem} by it. Each output names the identity's members its
 * own way, and {@link Shape} holds the names of every output.
 */
public final class DynamoDbTtlIdentity {

    private static final String USER_IDENTITY = "userIdentity";

    /** An output a record is read from, with the member names its identity has there. */
    public enum Shape {
        /** The DynamoDB Streams API, whose {@code Identity} members AWS returns capitalised. */
        STREAMS_API("Type", "PrincipalId"),
        /**
         * The Lambda and EventBridge Pipes event record, and the Kinesis Data Streams destination
         * payload: lowercase names, as measured against AWS.
         */
        EVENT_RECORD("type", "principalId");

        private final String type;
        private final String principalId;

        Shape(String type, String principalId) {
            this.type = type;
            this.principalId = principalId;
        }
    }

    private DynamoDbTtlIdentity() {
    }

    public static void putOn(ObjectNode record, Shape shape) {
        record.putObject(USER_IDENTITY)
                .put(shape.type, "Service")
                .put(shape.principalId, ServicePrincipals.of("dynamodb"));
    }

    /** A copy of a Streams API record, its identity renamed to the event record shape Lambda and Pipes deliver. */
    public static ObjectNode toEventRecord(JsonNode streamsRecord) {
        ObjectNode record = streamsRecord.deepCopy();
        if (record.get(USER_IDENTITY) instanceof ObjectNode identity) {
            ObjectNode event = record.putObject(USER_IDENTITY);
            event.set(Shape.EVENT_RECORD.type, identity.get(Shape.STREAMS_API.type));
            event.set(Shape.EVENT_RECORD.principalId, identity.get(Shape.STREAMS_API.principalId));
        }
        return record;
    }
}
