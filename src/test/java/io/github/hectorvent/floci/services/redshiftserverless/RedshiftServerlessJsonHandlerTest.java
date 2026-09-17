package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshiftserverless.model.Namespace;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the wire layer the service tests cannot reach: how values are shaped on the way out.
 * The timestamp case is a regression guard. {@code creationDate} was first emitted as
 * epoch seconds, the awsJson1.1 default, but {@code Namespace.creationDate} carries
 * {@code TimestampFormatTrait(ISO_8601)} (other members of this model vary; check each). The AWS CLI accepted the number because
 * botocore coerces it, so an integration test asserting only "not null" stayed green while
 * strict SDKs rejected the response.
 */
class RedshiftServerlessJsonHandlerTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "123456789012";

    private final ObjectMapper mapper = new ObjectMapper();
    private RedshiftServerlessJsonHandler handler;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        AccountAwareStorageBackend<Namespace> store = AccountAwareStorageBackend.inMemory(ACCOUNT_ID);
        when(storageFactory.create(eq("redshiftserverless"), eq("redshiftserverless-namespaces.json"),
                any(TypeReference.class))).thenReturn((AccountAwareStorageBackend) store);

        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.buildArn(eq("redshift-serverless"), any(String.class), any(String.class)))
                .thenAnswer(invocation -> "arn:aws:redshift-serverless:"
                        + invocation.getArgument(1, String.class) + ":" + ACCOUNT_ID + ":"
                        + invocation.getArgument(2, String.class));
        handler = new RedshiftServerlessJsonHandler(
                new RedshiftServerlessService(storageFactory, regionResolver), mapper);
    }

    @Test
    void creationDateIsAnIso8601StringNotEpochSeconds() {
        JsonNode namespace = body(create("timestamp-ns")).get("namespace");

        JsonNode creationDate = namespace.get("creationDate");
        assertTrue(creationDate.isTextual(),
                "creationDate must be a JSON string; a number is what strict SDKs reject");
        assertTrue(creationDate.textValue().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"),
                "unexpected creationDate shape: " + creationDate.textValue());
        // Parsed by the same ISO-8601 reader an SDK uses, so a well-formed string carrying a
        // wrong offset or a stale clock cannot pass on shape alone.
        Instant parsed = Instant.parse(creationDate.textValue());
        assertTrue(Duration.between(parsed, Instant.now()).abs().toMinutes() < 5,
                "creationDate is not close to now, check the zone offset: " + creationDate.textValue());
    }

    @Test
    void creationDateSurvivesAReadBackThroughGetNamespace() {
        create("reread-ns");
        JsonNode fetched = body(handler.handle("GetNamespace", request("namespaceName", "reread-ns"), REGION));
        assertTrue(fetched.get("namespace").get("creationDate").isTextual());
    }

    @Test
    void listNamespacesEmitsNextTokenOnlyWhenAPageRemains() {
        create("page-one-ns");
        create("page-two-ns");

        ObjectNode paged = request("maxResults", null);
        paged.put("maxResults", 1);
        JsonNode firstPage = body(handler.handle("ListNamespaces", paged, REGION));
        assertEquals(1, firstPage.get("namespaces").size());
        assertTrue(firstPage.hasNonNull("nextToken"), "a remaining page must advertise nextToken");

        ObjectNode second = mapper.createObjectNode();
        second.put("maxResults", 1);
        second.put("nextToken", firstPage.get("nextToken").textValue());
        JsonNode lastPage = body(handler.handle("ListNamespaces", second, REGION));
        assertEquals(1, lastPage.get("namespaces").size());
        assertFalse(lastPage.has("nextToken"), "the final page must not advertise nextToken");
    }

    @Test
    void aStringMaxResultsIsAValidationExceptionEvenWhenItLooksLikeANumber() {
        // "12" is the case that actually pins the type guard. A non-numeric string such as
        // "twelve" coerces to 0 through asInt() and is rejected downstream by the pagination
        // bound anyway, so it cannot tell a present guard from an absent one.
        for (String malformed : new String[] {"twelve", "12"}) {
            ObjectNode bad = mapper.createObjectNode();
            bad.put("maxResults", malformed);

            Response response = handler.handle("ListNamespaces", bad, REGION);

            assertEquals(400, response.getStatus(), "maxResults=\"" + malformed + "\" must be rejected");
            assertEquals("ValidationException", errorType(response),
                    "maxResults=\"" + malformed + "\" must be a ValidationException");
        }
    }

    @Test
    void anUnknownActionIsReportedAsUnknownOperation() {
        Response response = handler.handle("CreateWorkgroup", mapper.createObjectNode(), REGION);

        assertEquals(400, response.getStatus());
        assertEquals("UnknownOperationException", errorType(response));
    }

    @Test
    void aPresentCollectionMemberOfTheWrongTypeIsRejectedPerMember() {
        create("wrong-type-ns");

        for (String member : new String[] {"iamRoles", "logExports"}) {
            for (String malformed : new String[] {"\"a-string\"", "7", "{\"k\":\"v\"}", "true"}) {
                ObjectNode request = (ObjectNode) parse(
                        "{\"namespaceName\":\"wrong-type-ns\"," + quoted(member) + ":" + malformed + "}");

                Response response = handler.handle("UpdateNamespace", request, REGION);

                assertEquals(400, response.getStatus(),
                        member + "=" + malformed + " must not be accepted");
                assertEquals("ValidationException", errorType(response),
                        member + "=" + malformed + " must be a ValidationException");
            }
        }
    }

    @Test
    void aWrongTypedCollectionIsNotSilentlyTreatedAsOmitted() {
        ObjectNode created = (ObjectNode) parse("{\"namespaceName\":\"not-omitted-ns\","
                + "\"adminUsername\":\"admin\",\"iamRoles\":[\"arn:aws:iam::000000000000:role/one\"]}");
        body(handler.handle("CreateNamespace", created, REGION));

        Response response = handler.handle("UpdateNamespace",
                (ObjectNode) parse("{\"namespaceName\":\"not-omitted-ns\",\"iamRoles\":\"role/one\"}"), REGION);

        assertEquals(400, response.getStatus(),
                "a malformed iamRoles must fail the request, not quietly keep the stored roles");
        JsonNode unchanged = body(handler.handle("GetNamespace",
                request("namespaceName", "not-omitted-ns"), REGION)).get("namespace");
        assertEquals(1, unchanged.get("iamRoles").size(), "the rejected update must not have applied");
    }

    @Test
    void nonStringElementsInACollectionAreRejected() {
        create("bad-element-ns");

        Response response = handler.handle("UpdateNamespace",
                (ObjectNode) parse("{\"namespaceName\":\"bad-element-ns\",\"iamRoles\":[7]}"), REGION);

        assertEquals(400, response.getStatus());
        assertEquals("ValidationException", errorType(response));
    }

    @Test
    void aWrongTypedTagsOrTagKeysMemberIsRejected() {
        JsonNode namespace = body(create("tag-type-ns")).get("namespace");
        String arn = namespace.get("namespaceArn").textValue();

        Response tagged = handler.handle("TagResource",
                (ObjectNode) parse("{\"resourceArn\":" + quoted(arn) + ",\"tags\":\"env=dev\"}"), REGION);
        assertEquals(400, tagged.getStatus());
        assertEquals("ValidationException", errorType(tagged));

        Response untagged = handler.handle("UntagResource",
                (ObjectNode) parse("{\"resourceArn\":" + quoted(arn) + ",\"tagKeys\":\"env\"}"), REGION);
        assertEquals(400, untagged.getStatus());
        assertEquals("ValidationException", errorType(untagged));
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("bad test fixture: " + json, e);
        }
    }

    private static String quoted(String value) {
        return '"' + value + '"';
    }

    private Response create(String namespaceName) {
        ObjectNode request = request("namespaceName", namespaceName);
        request.put("adminUsername", "admin");
        return handler.handle("CreateNamespace", request, REGION);
    }

    private ObjectNode request(String field, String value) {
        ObjectNode request = mapper.createObjectNode();
        if (value != null) {
            request.put(field, value);
        }
        return request;
    }

    private JsonNode body(Response response) {
        assertEquals(200, response.getStatus(), "expected a successful response, got " + response.getEntity());
        return mapper.valueToTree(response.getEntity());
    }

    private String errorType(Response response) {
        return mapper.valueToTree(response.getEntity()).path("__type").asText(null);
    }
}
