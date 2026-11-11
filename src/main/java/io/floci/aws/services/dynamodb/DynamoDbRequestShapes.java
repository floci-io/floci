package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestShapeCheck;
import io.floci.aws.core.common.RequestShapeCheck.ByPosition;
import io.floci.aws.core.common.RequestShapeModel;
import io.floci.aws.core.common.RequestShapeModel.Shape;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Rejects a DynamoDB request member of the wrong JSON type with the SerializationException
 * DynamoDB answers before it runs the operation. The input shapes come from the vendored
 * {@code aws/dynamodb-request-shapes.json}, generated from botocore's DynamoDB model by
 * {@code tools/aws/regen_dynamodb_shapes.py}, and are walked by {@link RequestShapeCheck}.
 *
 * <p>AWS reads the body in order and reports the first bad member, without naming it.
 */
public final class DynamoDbRequestShapes {

    static final String RESOURCE_NAME = "aws/dynamodb-request-shapes.json";

    private static final String UNKNOWN_ERROR = "UnknownError";
    private static final String SEARCH_VECTORS = "SearchVectors";
    // How the SearchVectors frontend names the type a null required member should have had.
    private static final Map<String, String> SEARCH_VECTORS_TYPE_NAMES = Map.of(
            "string", "a string", "list", "a sequence", "integer", "i32");
    private static final JsonFactory JSON = new JsonFactory();
    private static final ByPosition SCALAR_FOR_CONTAINER = new ByPosition("Unexpected field type", UNKNOWN_ERROR);

    private static final RequestShapeCheck.Messages MESSAGES = new RequestShapeCheck.Messages(
            "SerializationException",
            "Start of structure or map found where not expected",
            UNKNOWN_ERROR,
            new ByPosition("Unexpected field type", "Unexpected value type in payload"),
            SCALAR_FOR_CONTAINER,
            SCALAR_FOR_CONTAINER,
            "%s cannot be converted to %s",
            true,
            "Unexpected token received from parser",
            "only base-64-encoded strings are convertible to bytes",
            Set.of("string", "integer", "long", "double", "timestamp", "boolean", "blob"));

    private static final class Holder {
        static final RequestShapeModel MODEL = RequestShapeModel.load(RESOURCE_NAME, "DynamoDB request shapes");
    }

    private DynamoDbRequestShapes() {
    }

    /** Checks a DynamoDB request body against the input shape of {@code action}, if the model has one. */
    public static void check(String action, JsonNode request) {
        check(Holder.MODEL, action, request);
    }

    static void check(RequestShapeModel model, String action, JsonNode request) {
        String input = model.operations().get(action);
        if (input != null) {
            RequestShapeCheck.check(model, input, request, MESSAGES);
        }
    }

    /**
     * As {@link #check(String, JsonNode)}, given the body as sent. SearchVectors has its own frontend,
     * which also refuses a null or omitted required member and names the line and byte column where
     * it stopped reading.
     */
    public static void check(String action, JsonNode request, String body) {
        if (SEARCH_VECTORS.equals(action) && !hasSearchVectorsMembers(Holder.MODEL, request)) {
            readSearchVectors(Holder.MODEL, request, body == null ? "" : body, true);
        } else {
            check(action, request);
        }
    }

    /**
     * Refuses a null or omitted required SearchVectors member of a request that reached the operation
     * without a JSON body, from AppSync, Step Functions or CBOR. The positions are those of the compact
     * JSON an SDK sends.
     */
    public static void requireSearchVectorsMembers(JsonNode request) {
        if (!hasSearchVectorsMembers(Holder.MODEL, request)) {
            readSearchVectors(Holder.MODEL, request, request.toString(), false);
        }
    }

    private static boolean hasSearchVectorsMembers(RequestShapeModel model, JsonNode request) {
        return searchVectorsInput(model).required().stream().allMatch(request::hasNonNull);
    }

    private static Shape searchVectorsInput(RequestShapeModel model) {
        return model.shapes().get(model.operations().get(SEARCH_VECTORS));
    }

    /** Reads {@code body} in order, checking each member's JSON type as well when {@code memberTypes}. */
    private static void readSearchVectors(RequestShapeModel model, JsonNode request, String body,
                                          boolean memberTypes) {
        if (body.isBlank()) {
            throw new AwsException("ValidationException", "EOF while parsing a value at line "
                    + (body.chars().filter(c -> c == '\n').count() + 1)
                    + " column " + (body.length() - body.lastIndexOf('\n') - 1), 400);
        }
        Shape input = searchVectorsInput(model);
        try (JsonParser parser = JSON.createParser(body.getBytes(StandardCharsets.UTF_8))) {
            parser.nextToken();
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                String memberShape = input.members().get(name);
                if (parser.nextToken() == JsonToken.VALUE_NULL && input.required().contains(name)) {
                    throw new AwsException("SerializationException", "invalid type: null, expected "
                            + SEARCH_VECTORS_TYPE_NAMES.get(model.shapes().get(memberShape).type())
                            + " at " + position(parser.currentTokenLocation(), "null".length() - 1), 400);
                }
                parser.skipChildren();
                if (memberTypes && memberShape != null) {
                    RequestShapeCheck.check(model, memberShape, request.get(name), MESSAGES);
                }
            }
            for (String member : new TreeSet<>(input.required())) {
                if (!request.has(member)) {
                    throw new AwsException("ValidationException", "missing field `" + member + "` at "
                            + position(parser.currentTokenLocation(), 0), 400);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String position(JsonLocation location, int columnOffset) {
        return "line " + location.getLineNr() + " column " + (location.getColumnNr() + columnOffset);
    }
}
