package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshift.RedshiftIamDbUserResolver;
import io.github.hectorvent.floci.services.redshift.TempCredential;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import io.github.hectorvent.floci.services.redshiftserverless.model.Namespace;
import io.github.hectorvent.floci.services.redshiftserverless.model.Workgroup;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
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
    private RedshiftIamDbUserResolver iamDbUserResolver;

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
        AccountAwareStorageBackend<Workgroup> workgroupStore = AccountAwareStorageBackend.inMemory(ACCOUNT_ID);
        when(storageFactory.create(eq("redshiftserverless"), eq("redshiftserverless-workgroups.json"),
                any(TypeReference.class))).thenReturn((AccountAwareStorageBackend) workgroupStore);
        RedshiftServerlessEndpoints endpoints = mock(RedshiftServerlessEndpoints.class);
        when(endpoints.allocate()).thenReturn(new Endpoint("localhost", 7100));
        RedshiftServerlessRuntime runtime = mock(RedshiftServerlessRuntime.class);
        when(runtime.generatePassword()).thenReturn("Generated123");
        when(runtime.start(any(String.class), any(String.class), any(String.class), any(String.class),
                any(String.class), any(String.class), any(Endpoint.class), anyBoolean(), any()))
                .thenReturn(new RedshiftServerlessRuntime.Backend("127.0.0.1", 55432));
        when(runtime.issueCredential(any(), any(), any(), any(), anyInt())).thenAnswer(invocation ->
                new TempCredential(invocation.getArgument(3), "tempPassword1",
                        Instant.ofEpochSecond(1_800_000_000L), List.of()));
        iamDbUserResolver = mock(RedshiftIamDbUserResolver.class);
        when(iamDbUserResolver.resolveDbUser("AUTH")).thenReturn("IAM:alice");
        EmulatorConfig config = mock(EmulatorConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.storage().persistentPath()).thenReturn(System.getProperty("java.io.tmpdir"));
        handler = new RedshiftServerlessJsonHandler(
                new RedshiftServerlessService(storageFactory, regionResolver, endpoints, runtime, config), mapper,
                iamDbUserResolver);
    }

    @Test
    void workgroupTimestampIsAnIso8601StringAndTheShapeFollowsTheApiModel() {
        create("shape-ns");

        JsonNode workgroup = body(handler.handle("CreateWorkgroup", parse("""
                {"workgroupName":"shape-wg","namespaceName":"shape-ns","baseCapacity":32,"maxCapacity":64,
                 "configParameters":[{"parameterKey":"datestyle","parameterValue":"ISO, MDY"}],
                 "securityGroupIds":["sg-1"],"subnetIds":["subnet-1"],
                 "pricePerformanceTarget":{"status":"ENABLED","level":50},
                 "tags":[{"key":"env","value":"dev"}]}
                """), REGION)).get("workgroup");

        assertTrue(workgroup.get("creationDate").isTextual());
        assertTrue(workgroup.get("creationDate").textValue()
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));
        assertEquals("shape-wg", workgroup.get("workgroupName").textValue());
        assertEquals("shape-ns", workgroup.get("namespaceName").textValue());
        assertEquals("AVAILABLE", workgroup.get("status").textValue());
        assertEquals(32, workgroup.get("baseCapacity").intValue());
        assertEquals(64, workgroup.get("maxCapacity").intValue());
        assertEquals(5439, workgroup.get("port").intValue());
        assertEquals("localhost", workgroup.get("endpoint").get("address").textValue());
        assertEquals(7100, workgroup.get("endpoint").get("port").intValue());
        assertEquals("datestyle", workgroup.get("configParameters").get(0).get("parameterKey").textValue());
        assertEquals("sg-1", workgroup.get("securityGroupIds").get(0).textValue());
        assertEquals(50, workgroup.get("pricePerformanceTarget").get("level").intValue());
        assertEquals("ENABLED", workgroup.get("pricePerformanceTarget").get("status").textValue());
        assertTrue(workgroup.get("workgroupArn").textValue().contains(":workgroup/"));
        assertFalse(workgroup.has("pendingTrackName"));
        assertFalse(workgroup.has("workgroupVersion"));
    }

    @Test
    void getCredentialsReturnsEpochSecondNumbersAndTheCallersDatabaseUser() {
        create("cred-shape-ns");
        body(handler.handle("CreateWorkgroup",
                parse("{\"workgroupName\":\"cred-shape-wg\",\"namespaceName\":\"cred-shape-ns\"}"), REGION));

        JsonNode credentials = body(handler.handle("GetCredentials",
                parse("{\"workgroupName\":\"cred-shape-wg\",\"dbName\":\"dev\",\"durationSeconds\":1800}"),
                REGION, "AUTH"));

        assertEquals("IAM:alice", credentials.get("dbUser").textValue());
        assertEquals("tempPassword1", credentials.get("dbPassword").textValue());
        assertTrue(credentials.get("expiration").isNumber(),
                "expiration has no timestamp trait, so it is epoch seconds as a JSON number");
        assertEquals(1_800_000_000L, credentials.get("expiration").longValue());
        assertEquals(1_800_000_000L, credentials.get("nextRefreshTime").longValue());
    }

    @Test
    void getCredentialsRejectsABadDurationAMissingWorkgroupAndACustomDomain() {
        create("cred-err-ns");
        body(handler.handle("CreateWorkgroup",
                parse("{\"workgroupName\":\"cred-err-wg\",\"namespaceName\":\"cred-err-ns\"}"), REGION));

        Response tooShort = handler.handle("GetCredentials",
                parse("{\"workgroupName\":\"cred-err-wg\",\"durationSeconds\":60}"), REGION, "AUTH");
        assertEquals(400, tooShort.getStatus());
        assertEquals("ValidationException", errorType(tooShort));

        Response wrongType = handler.handle("GetCredentials",
                parse("{\"workgroupName\":\"cred-err-wg\",\"durationSeconds\":\"900\"}"), REGION, "AUTH");
        assertEquals(400, wrongType.getStatus());

        Response missing = handler.handle("GetCredentials", parse("{\"workgroupName\":\"absent-wg\"}"), REGION, "AUTH");
        assertEquals(404, missing.getStatus());
        assertEquals("ResourceNotFoundException", errorType(missing));

        Response customDomain = handler.handle("GetCredentials",
                parse("{\"customDomainName\":\"wg.example.com\"}"), REGION, "AUTH");
        assertEquals(400, customDomain.getStatus());
        assertEquals("ValidationException", errorType(customDomain));
    }

    @Test
    void theAdminPasswordIsAcceptedOnCreateAndUpdateAndNeverReturned() {
        JsonNode created = body(handler.handle("CreateNamespace",
                parse("{\"namespaceName\":\"pw-shape-ns\",\"adminUsername\":\"root\",\"adminUserPassword\":\"Secret123\"}"),
                REGION)).get("namespace");
        assertFalse(created.has("adminUserPassword"));

        JsonNode updated = body(handler.handle("UpdateNamespace",
                parse("{\"namespaceName\":\"pw-shape-ns\",\"adminUserPassword\":\"Changed123\"}"), REGION))
                .get("namespace");
        assertFalse(updated.has("adminUserPassword"));
    }

    @Test
    void listWorkgroupsReturnsTheWorkgroupsMember() {
        create("list-shape-ns");
        body(handler.handle("CreateWorkgroup",
                parse("{\"workgroupName\":\"list-shape-wg\",\"namespaceName\":\"list-shape-ns\"}"), REGION));

        JsonNode listed = body(handler.handle("ListWorkgroups", mapper.createObjectNode(), REGION));

        assertEquals("list-shape-wg", listed.get("workgroups").get(0).get("workgroupName").textValue());
        assertFalse(listed.has("nextToken"));
    }

    @Test
    void wrongTypedWorkgroupMembersAreRejected() {
        create("typed-ns");
        for (String member : new String[] {
                "\"baseCapacity\":\"32\"", "\"baseCapacity\":3.5", "\"port\":\"5439\"",
                "\"publiclyAccessible\":\"true\"", "\"enhancedVpcRouting\":1",
                "\"configParameters\":\"datestyle\"", "\"configParameters\":[\"datestyle\"]",
                "\"pricePerformanceTarget\":\"ENABLED\"", "\"securityGroupIds\":\"sg-1\"",
                "\"subnetIds\":[7]", "\"tags\":\"env=dev\""}) {
            Response response = handler.handle("CreateWorkgroup", parse(
                    "{\"workgroupName\":\"typed-wg\",\"namespaceName\":\"typed-ns\"," + member + "}"), REGION);

            assertEquals(400, response.getStatus(), member + " must be rejected");
            assertEquals("ValidationException", errorType(response), member);
        }
    }

    @Test
    void updateWorkgroupKeepsOmittedMembersAndReportsAPendingTrack() {
        create("upd-shape-ns");
        body(handler.handle("CreateWorkgroup",
                parse("{\"workgroupName\":\"upd-shape-wg\",\"namespaceName\":\"upd-shape-ns\",\"baseCapacity\":32}"),
                REGION));

        JsonNode updated = body(handler.handle("UpdateWorkgroup",
                parse("{\"workgroupName\":\"upd-shape-wg\",\"trackName\":\"trailing\"}"), REGION)).get("workgroup");

        assertEquals(32, updated.get("baseCapacity").intValue());
        assertEquals("current", updated.get("trackName").textValue());
        assertEquals("trailing", updated.get("pendingTrackName").textValue());
    }

    @Test
    void deleteWorkgroupReturnsTheDeletedWorkgroupInTheDeletingState() {
        create("del-shape-ns");
        body(handler.handle("CreateWorkgroup",
                parse("{\"workgroupName\":\"del-shape-wg\",\"namespaceName\":\"del-shape-ns\"}"), REGION));

        JsonNode deleted = body(handler.handle("DeleteWorkgroup",
                request("workgroupName", "del-shape-wg"), REGION)).get("workgroup");

        assertEquals("DELETING", deleted.get("status").textValue());
        Response missing = handler.handle("GetWorkgroup", request("workgroupName", "del-shape-wg"), REGION);
        assertEquals(404, missing.getStatus());
        assertEquals("ResourceNotFoundException", errorType(missing));
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
        Response response = handler.handle("CreateScheduledAction", mapper.createObjectNode(), REGION);

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
