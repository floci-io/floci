package io.github.hectorvent.floci.services.lambdaweb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceCallAuthorizer;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunction;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunctionEndpoint;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunctionRevision;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LambdaWebServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String ARN = "arn:aws:lambda:us-east-1:000000000000:web-function/app";
    private static final String ROLE = "arn:aws:iam::000000000000:role/web";
    private static final byte[] NOT_A_ZIP = "this is not a zip\n".getBytes(StandardCharsets.UTF_8);

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ServiceCallAuthorizer serviceCalls = mock(ServiceCallAuthorizer.class);

    @TempDir
    Path storageDir;

    private LambdaWebService service() {
        return service(new MemoryStorageFactory());
    }

    private LambdaWebService service(StorageFactory storage) {
        return service(storage, "http://localhost:4566");
    }

    private LambdaWebService service(StorageFactory storage, String baseUrl) {
        return service(storage, baseUrl, null);
    }

    private LambdaWebService service(StorageFactory storage, String baseUrl, S3Service s3Service) {
        return service(storage, baseUrl, s3Service, mock(LambdaWebExecutionRole.class));
    }

    private LambdaWebService service(StorageFactory storage, String baseUrl, S3Service s3Service,
                                     LambdaWebExecutionRole executionRole) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.effectiveBaseUrl()).thenReturn(baseUrl);
        LambdaWebService service = new LambdaWebService(new RegionResolver(REGION, ACCOUNT_ID), storage, config,
                s3Service, new LambdaWebCode(storageDir.resolve("code")), mapper, null, executionRole, serviceCalls);
        service.initializeStorage();
        return service;
    }

    private ObjectNode revisionConfig(int timeoutSeconds) {
        ObjectNode config = mapper.createObjectNode();
        ObjectNode build = config.putObject("buildConfig");
        build.putObject("codeConfig").putObject("s3Object").put("bucket", "code").put("key", "app.zip");
        build.putObject("runtimeConfig").put("runtime", "nodejs24.x");
        config.putObject("serviceConfig").put("executionRoleArn", ROLE).put("timeoutSeconds", timeoutSeconds);
        return config;
    }

    private ObjectNode revisionConfig(String executionRoleArn) {
        ObjectNode config = revisionConfig(30);
        ((ObjectNode) config.get("serviceConfig")).put("executionRoleArn", executionRoleArn);
        return config;
    }

    private ObjectNode endpointConfig(String name) {
        return mapper.createObjectNode().put("endpointName", name).put("endpointType", "HomeRegion")
                .put("authType", "ApplicationManaged").put("autoDeploymentMode", "LatestRevision");
    }

    private ObjectNode createRequest(String name) {
        ObjectNode request = mapper.createObjectNode().put("functionName", name);
        request.set("revisionConfig", revisionConfig(30));
        request.set("endpointConfig", endpointConfig("dev"));
        return request;
    }

    private static String message(Runnable call) {
        return assertThrows(AwsException.class, call::run).getMessage();
    }

    private static AwsException error(Runnable call) {
        return assertThrows(AwsException.class, call::run);
    }

    @Test
    void shapeErrorsAreCountedAndJoinedBeforeAnyBusinessRule() {
        ObjectNode request = createRequest("-bad-name-");
        ((ObjectNode) request.path("revisionConfig").path("buildConfig").path("codeConfig").path("s3Object"))
                .put("bucket", "b");
        AwsException e = error(() -> service().createFunction(REGION, request));
        assertEquals("ValidationException", e.getErrorCode());
        assertEquals("2 validation errors detected: Value 'b' at 'revisionConfig.buildConfig.codeConfig.s3Object.bucket'"
                + " failed to satisfy constraint: Member must have length greater than or equal to 3; Value 'b' at"
                + " 'revisionConfig.buildConfig.codeConfig.s3Object.bucket' failed to satisfy constraint: Member must"
                + " satisfy regular expression pattern: ([a-z0-9][a-z0-9\\.\\-]{1,61}[a-z0-9])", e.getMessage());
    }

    @Test
    void endpointConfigMembersAreRequiredInTheOrderAwsReportsThem() {
        ObjectNode request = createRequest("app");
        request.putObject("endpointConfig");
        assertEquals("3 validation errors detected: Value null at 'endpointConfig.endpointType' failed to satisfy"
                + " constraint: Member must not be null; Value null at 'endpointConfig.endpointName' failed to satisfy"
                + " constraint: Member must not be null; Value null at 'endpointConfig.authType' failed to satisfy"
                + " constraint: Member must not be null", message(() -> service().createFunction(REGION, request)));

        ObjectNode noAuth = createRequest("app");
        ((ObjectNode) noAuth.get("endpointConfig")).remove("authType");
        assertEquals("1 validation error detected: Value null at 'endpointConfig.authType' failed to satisfy constraint:"
                + " Member must not be null", message(() -> service().createFunction(REGION, noAuth)));
    }

    @Test
    void integerMembersAreNotCoercedFromOtherTypes() {
        LambdaWebService service = service();
        ObjectNode text = createRequest("app");
        ((ObjectNode) text.path("revisionConfig").path("serviceConfig")).put("timeoutSeconds", "60");
        AwsException e = error(() -> service.createFunction(REGION, text));
        assertEquals("SerializationException", e.getErrorCode());
        assertEquals("STRING_VALUE can not be converted to an Integer", e.getMessage());

        ObjectNode bool = createRequest("app");
        ((ObjectNode) bool.path("revisionConfig").path("serviceConfig")).put("timeoutSeconds", true);
        assertEquals("TRUE_VALUE can not be converted to an Integer", message(() -> service.createFunction(REGION, bool)));

        ObjectNode big = createRequest("app");
        ((ObjectNode) big.path("revisionConfig").path("serviceConfig")).put("timeoutSeconds", 4294967299L);
        assertEquals("1 validation error detected: Value '2147483647' at 'revisionConfig.serviceConfig.timeoutSeconds'"
                + " failed to satisfy constraint: Member must have value less than or equal to 900",
                message(() -> service.createFunction(REGION, big)));

        ObjectNode number = createRequest("app");
        ((ObjectNode) number.path("revisionConfig").path("serviceConfig")).put("executionRoleArn", 12345);
        assertEquals("NUMBER_VALUE can not be converted to a String", message(() -> service.createFunction(REGION, number)));
    }

    @Test
    void mapMembersMustBeObjects() {
        LambdaWebService service = service();
        ObjectNode list = createRequest("app");
        list.putArray("tags").add("env=test");
        AwsException e = error(() -> service.createFunction(REGION, list));
        assertEquals("SerializationException", e.getErrorCode());
        assertEquals("Start of list found where not expected", e.getMessage());

        ObjectNode text = createRequest("app");
        text.put("tags", "x");
        assertEquals("Expected map or null", message(() -> service.createFunction(REGION, text)));

        ObjectNode weights = endpointConfig("dev").put("autoDeploymentMode", "Disabled");
        weights.putObject("revisionWeights").put("revisionId", "rev-aaaaa").put("weight", 100);
        assertEquals("Start of structure or map found where not expected.",
                message(() -> service.createEndpoint(REGION, "app", weights)));
    }

    @Test
    void tagValuesAreValidatedAsAMap() {
        ObjectNode request = createRequest("app");
        request.putObject("tags").put("team", "has;semicolon");
        assertEquals("1 validation error detected: Value '{team=has;semicolon}' at 'tags' failed to satisfy constraint:"
                + " Map value must satisfy constraint: [Member must have length less than or equal to 256, Member must"
                + " have length greater than or equal to 0, Member must satisfy regular expression pattern:"
                + " [a-zA-Z0-9 _\\.:/=+\\-@]*]", message(() -> service().createFunction(REGION, request)));
    }

    @Test
    void tagRulesFollowTheAwsTagMessage() {
        LambdaWebService service = service();
        service.createFunction(REGION, createRequest("app"));
        String rule = "One or more of your tags are invalid. Please ensure none of your tag keys start with \"aws:\","
                + " none are empty, and there are no duplicate tag keys.";
        ObjectNode nullValue = mapper.createObjectNode();
        nullValue.putObject("Tags").putNull("env");
        assertEquals(rule, message(() -> service.tagResource(REGION, ARN, nullValue)));
        ObjectNode reserved = mapper.createObjectNode();
        reserved.putObject("Tags").put("aws:cloudformation:stack-name", "s");
        assertEquals(rule, message(() -> service.tagResource(REGION, ARN, reserved)));

        ObjectNode empty = mapper.createObjectNode();
        empty.putObject("Tags").put("team", "");
        service.tagResource(REGION, ARN, empty);
        assertEquals("", service.listTags(REGION, ARN).get("team"));

        assertEquals("2 validation errors detected: Value 'not-an-arn' at 'resource' failed to satisfy constraint: Member"
                + " must satisfy regular expression pattern: arn:[^:]+:lambda:[^:]+:\\d{12}:web-function/[^/]+; Value"
                + " null at 'tags' failed to satisfy constraint: Member must not be null",
                message(() -> service.tagResource(REGION, "not-an-arn", mapper.createObjectNode())));
    }

    @Test
    void untagValidatesEveryKey() {
        LambdaWebService service = service();
        service.createFunction(REGION, createRequest("app"));
        assertEquals("1 validation error detected: Value '[]' at 'tagKeys' failed to satisfy constraint: Member must"
                + " satisfy constraint: [Member must have length less than or equal to 128, Member must have length"
                + " greater than or equal to 1, Member must satisfy regular expression pattern: [a-zA-Z0-9 _\\.:/=+\\-@]+]",
                message(() -> service.untagResource(REGION, ARN, List.of(""))));
        assertEquals("1 validation error detected: Value '[bad#key]' at 'tagKeys' failed to satisfy constraint: Member"
                + " must satisfy constraint: [Member must have length less than or equal to 128, Member must have length"
                + " greater than or equal to 1, Member must satisfy regular expression pattern: [a-zA-Z0-9 _\\.:/=+\\-@]+]",
                message(() -> service.untagResource(REGION, ARN, List.of("bad#key"))));
        assertEquals("1 validation error detected: Value null at 'tagKeys' failed to satisfy constraint: Member must not"
                + " be null", message(() -> service.untagResource(REGION, ARN, null)));
        service.untagResource(REGION, ARN, List.of("absent"));
    }

    @Test
    void environmentVariablesAreASensitiveMap() {
        LambdaWebService service = service();
        ObjectNode badKey = revisionConfig(30);
        ((ObjectNode) badKey.get("serviceConfig")).putObject("environmentVariables").put("1BAD", "x");
        assertEquals("1 validation error detected: Value at 'serviceConfig.environmentVariables' failed to satisfy"
                + " constraint: Map keys must satisfy constraint: [Member must have length greater than or equal to 1,"
                + " Member must satisfy regular expression pattern: [a-zA-Z]([a-zA-Z0-9_])*]",
                message(() -> service.createRevision(REGION, "app", badKey)));

        ObjectNode both = revisionConfig(30);
        ((ObjectNode) both.get("serviceConfig")).putObject("environmentVariables").put("1BAD", "");
        assertEquals("2 validation errors detected: Value at 'serviceConfig.environmentVariables' failed to satisfy"
                + " constraint: Map keys must satisfy constraint: [Member must have length greater than or equal to 1,"
                + " Member must satisfy regular expression pattern: [a-zA-Z]([a-zA-Z0-9_])*]; Value at"
                + " 'serviceConfig.environmentVariables' failed to satisfy constraint: Map value must satisfy constraint:"
                + " [Member must have length greater than or equal to 1]",
                message(() -> service.createRevision(REGION, "app", both)));

        ObjectNode nullValue = revisionConfig(30);
        ((ObjectNode) nullValue.get("serviceConfig")).putObject("environmentVariables").putNull("FOO").put("BAR", "1");
        service.createFunction(REGION, createRequest("app"));
        WebFunctionRevision revision = service.createRevision(REGION, "app", nullValue);
        assertEquals(Map.of("BAR", "1"), revision.serviceConfig.environmentVariables);
    }

    @Test
    void requestRulesRunBeforeTheFunctionLookup() {
        LambdaWebService service = service();
        ObjectNode reserved = revisionConfig(30);
        ((ObjectNode) reserved.get("serviceConfig")).putObject("environmentVariables").put("AWS_REGION", "x");
        assertEquals("The environmentVariables cannot contain reserved keys. The request specified the reserved keys"
                + " [AWS_REGION].", message(() -> service.createRevision(REGION, "missing", reserved)));

        ObjectNode rate = endpointConfig("dev").put("autoDeploymentMode", "Disabled");
        rate.putArray("revisionWeights").addObject().put("revisionId", "rev-aaaaa").put("weight", 100);
        rate.putObject("throttleConfig").put("rateLimit", 150);
        assertEquals("The rateLimit 150 is invalid. Supported rate limits are: [0, 100, 200, 300, 400, 500, 600, 700,"
                + " 800, 900, 1000, 2000, 3000, 4000, 5000, 6000, 7000, 8000, 9000, 10000].",
                message(() -> service.createEndpoint(REGION, "missing", rate)));

        ObjectNode update = mapper.createObjectNode();
        update.putObject("throttleConfig").put("rateLimit", 150);
        assertEquals("ResourceNotFoundException",
                error(() -> service.updateEndpoint(REGION, "missing", "dev", update)).getErrorCode());
    }

    @Test
    void executionRoleIsCheckedInTheOrderAwsChecksIt() {
        String missingRole = "arn:aws:iam::000000000000:role/missing";
        LambdaWebExecutionRole executionRole = mock(LambdaWebExecutionRole.class);
        doThrow(new AwsException("ValidationException", "cannot be assumed", 400))
                .when(executionRole).requireAssumable(REGION, missingRole);
        LambdaWebService service = service(new MemoryStorageFactory(), "http://localhost:4566", null, executionRole);
        service.createFunction(REGION, createRequest("app"));

        ObjectNode otherAccount = revisionConfig("arn:aws:iam::111122223333:role/web");
        ((ObjectNode) otherAccount.get("serviceConfig")).putObject("environmentVariables").put("AWS_REGION", "x");
        assertEquals("Invalid function name or ARN 'bad.name'",
                message(() -> service.createRevision(REGION, "bad.name", otherAccount)));
        AwsException denied = error(() -> service.createRevision(REGION, "missing", otherAccount));
        assertEquals("AccessDeniedException", denied.getErrorCode());
        assertEquals(403, denied.getHttpStatus());
        assertEquals("Cross-account pass role is not allowed.", denied.getMessage());

        ObjectNode reserved = revisionConfig(missingRole);
        ((ObjectNode) reserved.get("serviceConfig")).putObject("environmentVariables").put("AWS_REGION", "x");
        assertEquals("The environmentVariables cannot contain reserved keys. The request specified the reserved keys"
                + " [AWS_REGION].", message(() -> service.createRevision(REGION, "app", reserved)));

        ObjectNode runtime = revisionConfig(missingRole);
        ((ObjectNode) runtime.path("buildConfig").path("runtimeConfig")).put("runtime", "nodejs22.x");
        assertEquals("cannot be assumed", message(() -> service.createRevision(REGION, "missing", runtime)));
        assertEquals("cannot be assumed", message(() -> service.createRevision(REGION, "app", runtime)));

        ObjectNode existing = createRequest("app");
        existing.set("revisionConfig", revisionConfig(missingRole));
        assertEquals("cannot be assumed", message(() -> service.createFunction(REGION, existing)));
        ObjectNode multiRegion = createRequest("other");
        multiRegion.set("revisionConfig", revisionConfig(missingRole));
        ((ObjectNode) multiRegion.get("endpointConfig")).put("endpointType", "MultiRegion");
        assertEquals("cannot be assumed", message(() -> service.createFunction(REGION, multiRegion)));
    }

    @Test
    void aRejectedEndpointUpdateChangesNothing() {
        LambdaWebService service = service();
        service.createFunction(REGION, createRequest("app"));

        ObjectNode partial = mapper.createObjectNode().put("authType", "IamAuth").put("description", "d");
        partial.putObject("throttleConfig").put("rateLimit", 150);
        assertEquals("ValidationException", error(() -> service.updateEndpoint(REGION, "app", "dev", partial)).getErrorCode());
        WebFunctionEndpoint endpoint = service.getEndpoint(REGION, "app", "dev");
        assertEquals("ApplicationManaged", endpoint.authType);
        assertNull(endpoint.description);
        assertNull(endpoint.updateStatus);

        ObjectNode unknownRevision = mapper.createObjectNode().put("autoDeploymentMode", "Disabled");
        unknownRevision.putArray("revisionWeights").addObject().put("revisionId", "rev-zzzzz").put("weight", 100);
        assertEquals("The revision rev-zzzzz does not exist.",
                message(() -> service.updateEndpoint(REGION, "app", "dev", unknownRevision)));
        assertEquals("LatestRevision", service.getEndpoint(REGION, "app", "dev").autoDeploymentMode);
    }

    @Test
    void listsPageByNameWithCursorTokens() {
        LambdaWebService service = service();
        for (String name : List.of("charlie", "alpha", "bravo")) {
            service.createFunction(REGION, createRequest(name));
        }
        ObjectNode request = mapper.createObjectNode().put("maxResults", 2);
        PaginatedResult<WebFunction> first = service.listFunctions(REGION, request);
        assertEquals(List.of("alpha", "bravo"), first.items().stream().map(f -> f.functionName).toList());
        assertNotNull(first.nextToken());

        request.put("nextToken", first.nextToken());
        PaginatedResult<WebFunction> second = service.listFunctions(REGION, request);
        assertEquals(List.of("charlie"), second.items().stream().map(f -> f.functionName).toList());
        assertNull(second.nextToken());

        assertEquals(3, service.accountSettings(REGION).functionCount());
        assertEquals(0, service.accountSettings("eu-west-1").functionCount());

        ObjectNode unknown = mapper.createObjectNode().put("nextToken", "00000000-0000-0000-0000-000000000000");
        assertEquals("The nextToken 00000000-0000-0000-0000-000000000000 is invalid or has expired.",
                message(() -> service.listFunctions(REGION, unknown)));
        // The item a page ended on is gone: the next page starts past it in sort order, as on AWS.
        service.deleteFunction(REGION, "bravo");
        assertEquals(List.of("charlie"),
                service.listFunctions(REGION, request).items().stream().map(f -> f.functionName).toList());
    }

    @Test
    void pageTokensBelongToTheRequestThatIssuedThem() {
        LambdaWebService service = service();
        service.createFunction(REGION, createRequest("alpha"));
        service.createFunction(REGION, createRequest("bravo"));
        service.createRevision(REGION, "alpha", revisionConfig(60));
        ObjectNode request = mapper.createObjectNode().put("maxResults", 1);
        String functionsToken = service.listFunctions(REGION, request).nextToken();
        String mismatch = "The nextToken " + functionsToken + " does not match the request. Subsequent pagination"
                + " requests must use the same parameters as the initial request.";
        ObjectNode reused = mapper.createObjectNode().put("maxResults", 1).put("nextToken", functionsToken);
        assertEquals(mismatch, message(() -> service.listRevisions(REGION, "alpha", reused)));
        assertEquals(mismatch, message(() -> service.listEndpoints(REGION, "alpha", reused)));
        ObjectNode otherSize = mapper.createObjectNode().put("maxResults", 2).put("nextToken", functionsToken);
        assertEquals(mismatch, message(() -> service.listFunctions(REGION, otherSize)));

        ObjectNode revisions = mapper.createObjectNode().put("maxResults", 1);
        PaginatedResult<WebFunctionRevision> newest = service.listRevisions(REGION, "alpha", revisions);
        assertEquals(60, newest.items().get(0).serviceConfig.timeoutSeconds);
        revisions.put("nextToken", newest.nextToken());
        assertEquals(30, service.listRevisions(REGION, "alpha", revisions).items().get(0).serviceConfig.timeoutSeconds);
    }

    @Test
    void readsAreSnapshotsOfTheStoredState() {
        LambdaWebService service = service();
        service.createFunction(REGION, createRequest("app"));
        WebFunctionEndpoint endpoint = service.getEndpoint(REGION, "app", "dev");
        endpoint.description = "changed by the caller";
        endpoint.revisionWeights.clear();
        WebFunctionEndpoint again = service.getEndpoint(REGION, "app", "dev");
        assertNull(again.description);
        assertEquals(1, again.revisionWeights.size());
        WebFunction fn = service.getFunction(REGION, "app");
        fn.tags.put("leak", "x");
        assertFalse(service.getFunction(REGION, "app").tags.containsKey("leak"));
    }

    @Test
    void stateFilterMatchesOnlyActive() {
        LambdaWebService service = service();
        service.createFunction(REGION, createRequest("app"));
        ObjectNode filtered = mapper.createObjectNode();
        filtered.putArray("filters").addObject().put("name", "state").putArray("values").add("Failed");
        assertTrue(service.listFunctions(REGION, filtered).items().isEmpty());
        ObjectNode unknown = mapper.createObjectNode();
        unknown.putArray("filters").addObject().put("name", "nope").putArray("values").add("x");
        assertEquals("The filter names [nope] are invalid. Supported filter names are: [state].",
                message(() -> service.listFunctions(REGION, unknown)));
        // A repeated state filter adds its values, whatever the order, as on AWS.
        for (List<String> order : List.of(List.of("Failed", "Active"), List.of("Active", "Failed"))) {
            ObjectNode repeated = mapper.createObjectNode();
            ArrayNode filters = repeated.putArray("filters");
            for (String state : order) {
                filters.addObject().put("name", "state").putArray("values").add(state);
            }
            assertEquals(1, service.listFunctions(REGION, repeated).items().size(), order.toString());
        }
    }

    @Test
    void revisionMatchesComparesTheConfigurationAfterDefaults() {
        LambdaWebService service = service();
        WebFunction fn = service.createFunction(REGION, createRequest("app"));
        WebFunctionRevision revision = fn.revisions.values().iterator().next();
        assertTrue(service.revisionMatches(revision, revisionConfig(30)));
        assertFalse(service.revisionMatches(revision, revisionConfig(31)));
        ObjectNode described = revisionConfig(30).put("description", "d");
        assertFalse(service.revisionMatches(revision, described));
        // The default log group is the function's, so writing it out matches and dropping a custom one does not.
        ObjectNode explicit = revisionConfig(30);
        ((ObjectNode) explicit.get("serviceConfig")).putObject("telemetryConfig").putObject("loggingConfig")
                .put("logGroup", "/aws/lambda/web/app");
        assertTrue(service.revisionMatches(revision, explicit));
        ObjectNode custom = revisionConfig(30);
        ((ObjectNode) custom.get("serviceConfig")).putObject("telemetryConfig").putObject("loggingConfig")
                .put("logGroup", "custom-group");
        WebFunctionRevision customized = service.createRevision(REGION, "app", custom);
        assertFalse(service.revisionMatches(customized, revisionConfig(30)));
    }

    @Test
    void identifiersAcceptArnsOfTheHomeRegionAndAccount() {
        LambdaWebService service = service();
        WebFunction fn = service.createFunction(REGION, createRequest("app"));
        WebFunctionEndpoint endpoint = fn.endpoints.get("dev");
        WebFunctionRevision revision = fn.revisions.values().iterator().next();
        assertEquals("dev", service.getEndpoint(REGION, fn.functionArn, endpoint.endpointArn).endpointName);
        assertEquals(revision.revisionId, service.getRevision(REGION, "app", revision.revisionArn).revisionId);
        assertEquals("dev", service.getEndpoint(REGION, "app",
                "arn:aws:lambda:us-east-1:000000000000:web-function/other/endpoint/dev").endpointName);

        assertEquals("Invalid revision id or ARN 'nope'", message(() -> service.getRevision(REGION, "app", "nope")));
        assertEquals("Invalid revision id or ARN '" + endpoint.endpointArn + "'",
                message(() -> service.getRevision(REGION, "app", endpoint.endpointArn)));
        assertEquals("Invalid endpoint name or ARN '" + revision.revisionArn + "'",
                message(() -> service.getEndpoint(REGION, "app", revision.revisionArn)));
        assertEquals("Invalid function name or ARN 'a'", message(() -> service.getFunction(REGION, "a")));
        assertEquals("Invalid function name or ARN '" + endpoint.endpointArn + "'",
                message(() -> service.getFunction(REGION, endpoint.endpointArn)));

        String foreignRegion = "arn:aws:lambda:eu-west-1:000000000000:web-function/app";
        assertEquals("The operation is only allowed in the function's home region eu-west-1.",
                message(() -> service.getFunction(REGION, foreignRegion)));
        assertEquals("The operation is only allowed in the function's home region eu-west-1.",
                message(() -> service.getEndpoint(REGION, "app", foreignRegion + "/endpoint/dev")));

        AwsException denied = error(() -> service.getFunction(REGION,
                "arn:aws:lambda:us-east-1:111111111111:web-function/app"));
        assertEquals("AccessDeniedException", denied.getErrorCode());
        assertEquals(403, denied.getHttpStatus());
        assertEquals("User: arn:aws:iam::000000000000:user/unknown is not authorized to perform: lambda:GetWebFunction"
                + " on resource: arn:aws:lambda:us-east-1:111111111111:web-function/app because no resource-based policy"
                + " allows the lambda:GetWebFunction action", denied.getMessage());
    }

    @Test
    void endpointQuotaAndInUseMessages() {
        LambdaWebService service = service();
        service.createFunction(REGION, createRequest("app"));
        for (int i = 2; i <= 10; i++) {
            service.createEndpoint(REGION, "app", endpointConfig("ep" + i));
        }
        AwsException quota = error(() -> service.createEndpoint(REGION, "app", endpointConfig("ep11")));
        assertEquals("ServiceQuotaExceededException", quota.getErrorCode());
        assertEquals(402, quota.getHttpStatus());
        assertEquals("The function " + ARN + " has reached the maxEndpointsPerFunction quota of 10.", quota.getMessage());

        String revisionId = service.listRevisions(REGION, "app", mapper.createObjectNode()).items().get(0).revisionId;
        assertEquals("The revision " + revisionId + " cannot be deleted because the endpoints [dev, ep10, ep2, ep3, ep4,"
                + " ep5, ep6, ep7, ep8, ep9] are using it. Update the endpoints to use other revisions first.",
                message(() -> service.deleteRevision(REGION, "app", revisionId)));
    }

    @Test
    void resourcePolicyDocumentsAreCheckedBeforeTheLookup() {
        LambdaWebService service = service();
        String missing = "arn:aws:lambda:us-east-1:000000000000:web-function/missing";
        String resource = missing + "/endpoint/*";
        assertEquals("Policy has syntax errors.", message(() -> service.putResourcePolicy(REGION, missing, policy("[]"))));
        assertEquals("Policy has syntax errors.", message(() -> service.putResourcePolicy(REGION, missing, policy("{}"))));
        assertEquals("Policy has syntax errors.", message(() -> service.putResourcePolicy(REGION, missing,
                policy("{\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::000000000000:root\"},"
                        + "\"Action\":\"lambda:InvokeWebFunctionEndpoint\",\"Resource\":\"*\"}]}"))));
        assertEquals("Effect must be explicitly available for a statement", message(() -> service.putResourcePolicy(
                REGION, missing, policy("{\"Statement\":[{\"Principal\":\"*\",\"Action\":\"lambda:InvokeWebFunctionEndpoint\","
                        + "\"Resource\":\"" + resource + "\"}]}"))));
        assertEquals("Syntax error at position (-1,-1)", message(() -> service.putResourcePolicy(REGION, missing,
                policy("{\"Statement\":[{\"Foo\":\"bar\",\"Effect\":\"Allow\",\"Principal\":\"*\","
                        + "\"Action\":\"lambda:InvokeWebFunctionEndpoint\",\"Resource\":\"" + resource + "\"}]}"))));
        assertEquals("The provided principal was invalid. Please check the principal and try again.",
                message(() -> service.putResourcePolicy(REGION, missing, policy("{\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Action\":\"lambda:InvokeWebFunctionEndpoint\",\"Resource\":\"" + resource + "\"}]}"))));
        // Empty arrays: the principal is checked first, then the action and resource grammar.
        String members = "{\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":%s,\"Action\":%s,\"Resource\":%s}]}";
        String root = "{\"AWS\":\"arn:aws:iam::000000000000:root\"}";
        String invoke = "\"lambda:InvokeWebFunctionEndpoint\"";
        assertEquals("Policy has syntax errors.", message(() -> service.putResourcePolicy(REGION, missing,
                policy(members.formatted(root, "[]", "\"" + resource + "\"")))));
        assertEquals("Policy has syntax errors.", message(() -> service.putResourcePolicy(REGION, missing,
                policy(members.formatted(root, invoke, "[]")))));
        assertEquals("The provided principal was invalid. Please check the principal and try again.",
                message(() -> service.putResourcePolicy(REGION, missing,
                        policy(members.formatted("{\"AWS\":[]}", invoke, "\"" + resource + "\"")))));
        assertEquals("The provided principal was invalid. Please check the principal and try again.",
                message(() -> service.putResourcePolicy(REGION, missing,
                        policy(members.formatted("{\"AWS\":[]}", "[]", "[]")))));
        assertEquals("The provided input'*'was invalid. Member must satisfy expression: [\\w+=,.@-]* Please check your"
                + " input and try again.", message(() -> service.putResourcePolicy(REGION, missing,
                policy("{\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"*\"},"
                        + "\"Action\":\"lambda:InvokeWebFunctionEndpoint\",\"Resource\":\"" + resource + "\"}]}"))));
        // An ARN may carry a path, any other AWS principal may not.
        assertEquals("The provided input'arn:aws:iam::000000000000:role/has space'was invalid. Member must satisfy"
                + " expression: [\\w+=/:,.@-]* Please check your input and try again.",
                message(() -> service.putResourcePolicy(REGION, missing,
                        policy(members.formatted("{\"AWS\":\"arn:aws:iam::000000000000:role/has space\"}", invoke,
                                "\"" + resource + "\"")))));
        assertEquals("The provided input'foo/bar'was invalid. Member must satisfy expression: [\\w+=,.@\\:-]*"
                + " Please check your input and try again.", message(() -> service.putResourcePolicy(REGION, missing,
                policy(members.formatted("{\"AWS\":\"foo/bar\"}", invoke, "\"" + resource + "\"")))));
        assertEquals("ResourceNotFoundException", error(() -> service.putResourcePolicy(REGION, missing,
                policy(members.formatted("{\"AWS\":\"arn:aws:iam::000000000000:user/path/alice\"}", invoke,
                        "\"" + resource + "\"")))).getErrorCode());
        assertEquals("ValidationException", error(() -> service.putResourcePolicy(REGION, missing,
                policy(members.formatted("{\"CanonicalUser\":\"arn:aws:iam::000000000000:foo/bar\"}", invoke,
                        "\"" + resource + "\"")))).getErrorCode(), "only an AWS principal may carry a path");
        assertEquals("Lambda blocked the creation of this policy because it would grant public access to your function.",
                message(() -> service.putResourcePolicy(REGION, missing, policy("{\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Principal\":{\"AWS\":[\"arn:aws:iam::000000000000:root\",\"*\"]},"
                        + "\"Action\":\"lambda:InvokeWebFunctionEndpoint\",\"Resource\":\"" + resource + "\"}]}"))));
        // A wildcard principal is public unless a positive operator pins a trusted key without a wildcard.
        String blocked = "Lambda blocked the creation of this policy because it would grant public access"
                + " to your function.";
        for (String condition : List.of(
                "{\"StringNotEquals\":{\"aws:PrincipalAccount\":\"000000000000\"}}",
                "{\"StringLike\":{\"aws:PrincipalAccount\":\"0000*\"}}",
                "{\"StringEqualsIfExists\":{\"aws:PrincipalAccount\":\"000000000000\"}}",
                "{\"ArnLike\":{\"aws:PrincipalArn\":\"arn:aws:iam::*:role/x\"}}",
                "{\"StringLike\":{\"aws:PrincipalOrgID\":\"o-*\"}}",
                "{\"StringEquals\":{\"aws:username\":\"alice\"}}")) {
            assertEquals(blocked, message(() -> service.putResourcePolicy(REGION, missing,
                    policy(conditioned(resource, condition)))), condition);
        }
        for (String pinned : List.of(
                "{\"StringEquals\":{\"aws:SourceAccount\":\"000000000000\"}}",
                "{\"StringLike\":{\"aws:PrincipalAccount\":\"000000000000\"}}",
                "{\"StringEquals\":{\"aws:PrincipalAccount\":[\"000000000000\",\"*\"]}}",
                "{\"ArnLike\":{\"aws:PrincipalArn\":\"arn:aws:iam::000000000000:*\"}}",
                "{\"StringNotEquals\":{\"aws:PrincipalAccount\":\"111122223333\"},"
                        + "\"StringEquals\":{\"aws:PrincipalArn\":\"arn:aws:iam::000000000000:root\"}}",
                "{\"Bool\":{\"aws:PrincipalIsAWSService\":\"true\"}}")) {
            assertEquals("ResourceNotFoundException", error(() -> service.putResourcePolicy(REGION, missing,
                    policy(conditioned(resource, pinned)))).getErrorCode(), pinned);
        }
        for (String accepted : List.of(
                "{\"Statement\":{\"Effect\":\"Deny\",\"Principal\":\"*\",\"Action\":\"lambda:*\",\"Resource\":\"" + missing + "\"}}",
                "{\"Statement\":[{\"Effect\":\"allow\",\"Principal\":{\"Federated\":\"*\"},"
                        + "\"Action\":\"lambda:InvokeWebFunctionEndpoint\",\"Resource\":\"" + resource + "\"}]}")) {
            assertEquals("ResourceNotFoundException",
                    error(() -> service.putResourcePolicy(REGION, missing, policy(accepted))).getErrorCode(), accepted);
        }
    }

    private ObjectNode policy(String document) {
        return mapper.createObjectNode().put("Policy", document);
    }

    private static String conditioned(String resource, String condition) {
        return "{\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":\"lambda:InvokeWebFunctionEndpoint\","
                + "\"Resource\":\"" + resource + "\",\"Condition\":" + condition + "}]}";
    }

    @Test
    void functionsSurviveARestart() {
        LambdaWebService first = service(new FileStorageFactory(storageDir));
        WebFunction created = first.createFunction(REGION, createRequest("durable"));
        first.createRevision(REGION, "durable", revisionConfig(60));
        ObjectNode update = mapper.createObjectNode().put("description", "updated");
        first.updateEndpoint(REGION, "durable", "dev", update);

        String domain = first.getEndpoint(REGION, "durable", "dev").domainName;
        assertEquals("dev", first.locate(domain).orElseThrow().endpoint().endpointName);

        LambdaWebService second = service(new FileStorageFactory(storageDir));
        WebFunction restored = second.getFunction(REGION, "durable");
        assertEquals(created.functionArn, restored.functionArn);
        LambdaWebService.WebFunctionTarget target = second.locate(domain).orElseThrow();
        assertEquals(ACCOUNT_ID, target.accountId());
        assertEquals(REGION, target.region());
        assertEquals("durable", target.function().functionName);
        assertEquals("dev", target.endpoint().endpointName);
        assertTrue(second.locate("nope.001.lambda-web.us-east-1.localhost:4566").isEmpty());
        assertEquals(2, restored.revisions.size());
        WebFunctionEndpoint endpoint = second.getEndpoint(REGION, "durable", "dev");
        assertEquals("updated", endpoint.description);
        assertEquals("Successful", endpoint.updateStatus);
        assertEquals(60, second.listRevisions(REGION, "durable", mapper.createObjectNode())
                .items().get(0).serviceConfig.timeoutSeconds);
        assertEquals(endpoint.revisionWeights.get(0).revisionId,
                second.listRevisions(REGION, "durable", mapper.createObjectNode()).items().get(0).revisionId,
                "the LatestRevision endpoint moved to the newer revision and that move was written back");
        second.deleteEndpoint(REGION, "durable", "dev");
        assertTrue(second.locate(domain).isEmpty(), "a deleted endpoint's domain is gone");
    }

    @Test
    void aCloudFrontOriginNamesAnEndpointDomainWithoutCaseTrailingDotOrPort() {
        LambdaWebService service = service(new MemoryStorageFactory(), "http://Floci.Example.:4566");
        service.createFunction(REGION, createRequest("app"));
        String domain = service.getEndpoint(REGION, "app", "dev").domainName;
        assertTrue(domain.endsWith(".001.lambda-web.us-east-1.Floci.Example.:4566"), domain);
        String host = domain.substring(0, domain.indexOf('.')) + ".001.lambda-web.us-east-1.floci.example";

        assertEquals(domain, service.endpointDomain(host).orElseThrow());
        assertTrue(service.endpointDomain("nope.001.lambda-web.us-east-1.floci.example").isEmpty());
        service.deleteEndpoint(REGION, "app", "dev");
        assertTrue(service.endpointDomain(host).isEmpty(), "a deleted endpoint's domain is gone");
    }

    @Test
    void theGlobalDomainIsServedInTheDefaultRegionWhenTheEndpointHasIt() {
        LambdaWebService service = service();
        service.createFunction("us-west-2", createRequest("app"));
        String revisionId = service.getEndpoint("us-west-2", "app", "dev").revisionWeights.get(0).revisionId;
        String near = service.createEndpoint("us-west-2", "app", multiRegion("near", revisionId, REGION)).domainName;
        String far = service.createEndpoint("us-west-2", "app", multiRegion("far", revisionId, "eu-west-1")).domainName;

        LambdaWebService.WebFunctionTarget target = service.locate(near).orElseThrow();
        assertEquals("us-west-2", target.region());
        assertEquals(REGION, target.servingRegion());
        assertEquals("us-west-2", service.locate(far).orElseThrow().servingRegion());
    }

    private ObjectNode multiRegion(String name, String revisionId, String region) {
        ObjectNode request = mapper.createObjectNode().put("endpointName", name).put("endpointType", "MultiRegion")
                .put("authType", "ApplicationManaged");
        request.putArray("regions").add(region);
        request.putArray("revisionWeights").addObject().put("revisionId", revisionId).put("weight", 100);
        return request;
    }

    @Test
    void eachRevisionKeepsTheCodeTheObjectHadWhenItWasCreated() throws IOException {
        S3Service s3 = mock(S3Service.class);
        LambdaWebService service = service(new MemoryStorageFactory(), "http://localhost:4566", s3);
        LambdaWebCode code = new LambdaWebCode(storageDir.resolve("code"));
        byte[] v1 = LambdaWebZips.indexJs("v1");
        byte[] v2 = LambdaWebZips.indexJs("v2");
        when(s3.openObject("code", "app.zip", null)).thenReturn(object(v1));
        String first = service.createFunction(REGION, createRequest("app")).revisions.keySet().iterator().next();
        when(s3.openObject("code", "app.zip", null)).thenReturn(object(v2));
        String second = service.createRevision(REGION, "app", revisionConfig(30)).revisionId;

        assertArrayEquals(v1, Files.readAllBytes(code.file(ACCOUNT_ID, REGION, "app", first)));
        assertArrayEquals(v2, Files.readAllBytes(code.file(ACCOUNT_ID, REGION, "app", second)));
        service.deleteRevision(REGION, "app", first);
        assertFalse(Files.exists(code.file(ACCOUNT_ID, REGION, "app", first)));
        service.deleteFunction(REGION, "app");
        assertFalse(Files.exists(code.file(ACCOUNT_ID, REGION, "app", second)));
    }

    @Test
    void aStartKeepsOnlyTheCopiesOfStoredRevisions() throws IOException {
        S3Service s3 = mock(S3Service.class);
        byte[] zip = LambdaWebZips.indexJs("code");
        when(s3.openObject("code", "app.zip", null)).thenReturn(object(zip));
        FileStorageFactory storage = new FileStorageFactory(storageDir);
        String revisionId = service(storage, "http://localhost:4566", s3).createFunction(REGION, createRequest("app"))
                .revisions.keySet().iterator().next();
        LambdaWebCode code = new LambdaWebCode(storageDir.resolve("code"));
        code.store(ACCOUNT_ID, REGION, "app", "rev-stray", new ByteArrayInputStream(new byte[] {1}));

        service(new FileStorageFactory(storageDir), "http://localhost:4566", s3);
        assertArrayEquals(zip, Files.readAllBytes(code.file(ACCOUNT_ID, REGION, "app", revisionId)));
        assertFalse(Files.exists(code.file(ACCOUNT_ID, REGION, "app", "rev-stray")));

        service(new MemoryStorageFactory(), "http://localhost:4566", s3);
        assertFalse(Files.exists(code.file(ACCOUNT_ID, REGION, "app", revisionId)));
    }

    @Test
    void regionsThatLookAlikeKeepTheirOwnCopies() throws IOException {
        LambdaWebCode code = new LambdaWebCode(storageDir.resolve("code"));
        code.store(ACCOUNT_ID, ".", "app", "rev-aaaaa", new ByteArrayInputStream(new byte[] {1}));
        code.store(ACCOUNT_ID, "_", "app", "rev-aaaaa", new ByteArrayInputStream(new byte[] {2}));
        code.deleteFunction(ACCOUNT_ID, ".", "app");

        assertEquals(2, Files.readAllBytes(code.file(ACCOUNT_ID, "_", "app", "rev-aaaaa"))[0]);
    }

    @Test
    void aCopyThatCannotBeReadIsRemoved() throws IOException {
        LambdaWebCode code = new LambdaWebCode(storageDir.resolve("code"));
        Path copy = code.file(ACCOUNT_ID, REGION, "app", "rev-aaaaa");
        Files.createDirectories(copy);

        assertThrows(UncheckedIOException.class, () -> code.unzippedSize(ACCOUNT_ID, REGION, "app", "rev-aaaaa"));
        assertFalse(Files.exists(copy));
    }

    @Test
    void aVersionThatCannotBeReadIsNamedInTheError() {
        S3Service s3 = mock(S3Service.class);
        LambdaWebService service = service(new MemoryStorageFactory(), "http://localhost:4566", s3);
        ObjectNode pinned = createRequest("app");
        ((ObjectNode) pinned.path("revisionConfig").path("buildConfig").path("codeConfig").path("s3Object"))
                .put("versionId", "v1");
        when(s3.openObject("code", "app.zip", "v1"))
                .thenThrow(new AwsException("NoSuchVersion", "The specified version does not exist.", 404));
        assertEquals("The specified S3 object does not exist: s3://code/app.zip (versionId=v1). Verify the object exists"
                + " and the versionId is correct.", message(() -> service.createFunction(REGION, pinned)));

        doThrow(new AwsException("InvalidArgument", "Invalid version id specified", 400))
                .when(s3).openObject("code", "app.zip", "v1");
        assertEquals("The S3 object s3://code/app.zip (versionId=v1) is inaccessible. Verify the request parameters and"
                + " permissions are correct.", message(() -> service.createFunction(REGION, pinned)));

        when(s3.openObject("code", "app.zip", null))
                .thenThrow(new AwsException("NoSuchKey", "The specified key does not exist.", 404));
        assertEquals("The specified S3 object does not exist: s3://code/app.zip. Verify the object exists in the bucket.",
                message(() -> service.createFunction(REGION, createRequest("app"))));
    }

    @Test
    void aRevisionWhoseCodeIsNotAZipIsFailedAndCannotBeDeployed() {
        S3Service s3 = mock(S3Service.class);
        LambdaWebService service = service(new MemoryStorageFactory(), "http://localhost:4566", s3);
        when(s3.openObject("code", "app.zip", null)).thenReturn(object(LambdaWebZips.indexJs("v1")));
        String good = service.createFunction(REGION, createRequest("app")).revisions.keySet().iterator().next();
        when(s3.openObject("code", "app.zip", null)).thenReturn(object(NOT_A_ZIP));
        WebFunctionRevision bad = service.createRevision(REGION, "app", revisionConfig(30));

        assertEquals("Failed to read zip file: zip: not a valid zip file", bad.failureReason);
        assertFalse(Files.exists(new LambdaWebCode(storageDir.resolve("code")).file(ACCOUNT_ID, REGION, "app",
                bad.revisionId)));
        assertEquals(good, service.getEndpoint(REGION, "app", "dev").revisionWeights.get(0).revisionId,
                "a LatestRevision endpoint stays on its revision");
        assertEquals(List.of(bad.revisionId), service.listRevisions(REGION, "app", stateFilter("Failed")).items()
                .stream().map(r -> r.revisionId).toList());

        String refusal = "The revision " + bad.revisionId + " is in Failed state and cannot be deployed."
                + " Only revisions in Active state can be deployed.";
        ObjectNode toBad = mapper.createObjectNode().put("autoDeploymentMode", "Disabled");
        toBad.putArray("revisionWeights").addObject().put("revisionId", bad.revisionId).put("weight", 100);
        AwsException update = error(() -> service.updateEndpoint(REGION, "app", "dev", toBad));
        assertEquals("ConflictException", update.getErrorCode());
        assertEquals(409, update.getHttpStatus());
        assertEquals(refusal, update.getMessage());
        assertEquals(good, service.getEndpoint(REGION, "app", "dev").revisionWeights.get(0).revisionId);
        ObjectNode weighted = endpointConfig("web").put("autoDeploymentMode", "Disabled");
        weighted.set("revisionWeights", toBad.get("revisionWeights"));
        assertEquals(refusal, message(() -> service.createEndpoint(REGION, "app", weighted)));

        String latestRefusal = "The latest revision " + bad.revisionId + " is in Failed state and cannot be deployed."
                + " Only revisions in Active state can be deployed. Create a new revision.";
        ObjectNode disabled = mapper.createObjectNode().put("autoDeploymentMode", "Disabled");
        disabled.putArray("revisionWeights").addObject().put("revisionId", good).put("weight", 100);
        service.updateEndpoint(REGION, "app", "dev", disabled);
        assertEquals(latestRefusal, message(() -> service.updateEndpoint(REGION, "app", "dev",
                mapper.createObjectNode().put("autoDeploymentMode", "LatestRevision"))));
        assertEquals(latestRefusal, message(() -> service.createEndpoint(REGION, "app", endpointConfig("auto"))));

        service.deleteRevision(REGION, "app", bad.revisionId);
        assertEquals(List.of(good), List.copyOf(service.getFunction(REGION, "app").revisions.keySet()));
    }

    @Test
    void aFunctionCreatedOnCodeThatIsNotAZipHasAFailedEndpoint() {
        S3Service s3 = mock(S3Service.class);
        LambdaWebService service = service(new MemoryStorageFactory(), "http://localhost:4566", s3);
        when(s3.openObject("code", "app.zip", null)).thenReturn(object(NOT_A_ZIP));
        service.createFunction(REGION, createRequest("app"));

        WebFunctionEndpoint dev = service.getEndpoint(REGION, "app", "dev");
        assertTrue(dev.failed);
        String bad = dev.revisionWeights.get(0).revisionId;
        assertNotNull(service.getRevision(REGION, "app", bad).failureReason);
        AwsException update = error(() -> service.updateEndpoint(REGION, "app", "dev",
                mapper.createObjectNode().put("description", "retry")));
        assertEquals("ConflictException", update.getErrorCode());
        assertEquals("The endpoint dev is in Failed state. The operation is allowed only when the endpoint is in"
                + " Active state.", update.getMessage());

        // A good revision still moves the endpoint's weights, and the endpoint stays Failed, as on AWS.
        when(s3.openObject("code", "app.zip", null)).thenReturn(object(LambdaWebZips.indexJs("v2")));
        String good = service.createRevision(REGION, "app", revisionConfig(30)).revisionId;
        dev = service.getEndpoint(REGION, "app", "dev");
        assertEquals(good, dev.revisionWeights.get(0).revisionId);
        assertTrue(dev.failed);
        assertEquals(1, service.listEndpoints(REGION, "app", stateFilter("Failed")).items().size());
        assertTrue(service.listEndpoints(REGION, "app", stateFilter("Active")).items().isEmpty());

        service.deleteRevision(REGION, "app", bad);
        service.deleteEndpoint(REGION, "app", "dev");
    }

    @Test
    void anEmptyObjectIsAnInternalErrorThatCreatesNoRevision() throws IOException {
        S3Service s3 = mock(S3Service.class);
        LambdaWebService service = service(new MemoryStorageFactory(), "http://localhost:4566", s3);
        when(s3.openObject("code", "app.zip", null)).thenReturn(object(new byte[0]));

        AwsException create = error(() -> service.createFunction(REGION, createRequest("app")));
        assertInstanceOf(LambdaWebInternalServerException.class, create);
        assertEquals("InternalServerException", create.getErrorCode());
        assertEquals(500, create.getHttpStatus());
        assertEquals("The function arn:aws:lambda:us-east-1:000000000000:web-function/app does not exist.",
                message(() -> service.getFunction(REGION, "app")));

        when(s3.openObject("code", "app.zip", null)).thenReturn(object(LambdaWebZips.indexJs("v1")));
        service.createFunction(REGION, createRequest("app"));
        when(s3.openObject("code", "app.zip", null)).thenReturn(object(new byte[0]));
        assertInstanceOf(LambdaWebInternalServerException.class,
                error(() -> service.createRevision(REGION, "app", revisionConfig(30))));
        assertEquals(1, service.getFunction(REGION, "app").revisions.size());
        try (Stream<Path> copies = Files.walk(storageDir.resolve("code"))) {
            assertEquals(1, copies.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void aRevisionWhoseFilesAddUpPastTheLimitIsFailed() {
        S3Service s3 = mock(S3Service.class);
        LambdaWebService service = service(new MemoryStorageFactory(), "http://localhost:4566", s3);
        when(s3.openObject("code", "app.zip", null))
                .thenReturn(object(LambdaWebZips.declaring(LambdaWebCode.MAX_BYTES)));
        WebFunctionRevision full = service.createFunction(REGION, createRequest("app")).revisions.values()
                .iterator().next();
        when(s3.openObject("code", "app.zip", null))
                .thenReturn(object(LambdaWebZips.declaring(LambdaWebCode.MAX_BYTES + 1)));
        WebFunctionRevision over = service.createRevision(REGION, "app", revisionConfig(30));

        assertNull(full.failureReason);
        assertEquals("Uncompressed code size 536870913 bytes exceeds the maximum allowed size of 536870912 bytes",
                over.failureReason);
        LambdaWebCode code = new LambdaWebCode(storageDir.resolve("code"));
        assertTrue(Files.exists(code.file(ACCOUNT_ID, REGION, "app", full.revisionId)));
        assertFalse(Files.exists(code.file(ACCOUNT_ID, REGION, "app", over.revisionId)));
    }

    @Test
    void declaredSizesAddUpWithoutWrappingAround() {
        S3Service s3 = mock(S3Service.class);
        LambdaWebService service = service(new MemoryStorageFactory(), "http://localhost:4566", s3);
        when(s3.openObject("code", "app.zip", null))
                .thenReturn(object(LambdaWebZips.declaring(Long.MAX_VALUE, 1)));
        WebFunctionRevision signed = service.createFunction(REGION, createRequest("app")).revisions.values()
                .iterator().next();
        when(s3.openObject("code", "app.zip", null))
                .thenReturn(object(LambdaWebZips.declaring(Long.MAX_VALUE, Long.MAX_VALUE, 2)));
        WebFunctionRevision unsigned = service.createRevision(REGION, "app", revisionConfig(30));

        assertEquals("Uncompressed code size 9223372036854775808 bytes exceeds the maximum allowed size of "
                + "536870912 bytes", signed.failureReason);
        assertEquals("Uncompressed code size 18446744073709551615 bytes exceeds the maximum allowed size of "
                + "536870912 bytes", unsigned.failureReason);
    }

    @Test
    void anObjectPastTheZippedLimitIsRefusedAndCreatesNothing() throws IOException {
        S3Service s3 = mock(S3Service.class);
        LambdaWebService service = service(new MemoryStorageFactory(), "http://localhost:4566", s3);
        byte[] zip = LambdaWebZips.indexJs("");
        String refusal = "Zipped size must be smaller than 536870912 bytes";
        when(s3.openObject("code", "app.zip", null)).thenReturn(object(zip, LambdaWebCode.MAX_BYTES + 1));

        AwsException create = error(() -> service.createFunction(REGION, createRequest("app")));
        assertEquals("ValidationException", create.getErrorCode());
        assertEquals(400, create.getHttpStatus());
        assertEquals(refusal, create.getMessage());
        assertEquals("The function arn:aws:lambda:us-east-1:000000000000:web-function/app does not exist.",
                message(() -> service.getFunction(REGION, "app")));

        when(s3.openObject("code", "app.zip", null)).thenReturn(object(zip, LambdaWebCode.MAX_BYTES));
        service.createFunction(REGION, createRequest("app"));
        when(s3.openObject("code", "app.zip", null)).thenReturn(object(zip, LambdaWebCode.MAX_BYTES + 1));
        assertEquals(refusal, message(() -> service.createRevision(REGION, "app", revisionConfig(30))));
        assertEquals(1, service.getFunction(REGION, "app").revisions.size());
        try (Stream<Path> copies = Files.walk(storageDir.resolve("code"))) {
            assertEquals(1, copies.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void theNullVersionAsTheLatestIsRefusedBeforeTheSize() {
        S3Service s3 = mock(S3Service.class);
        LambdaWebService service = service(new MemoryStorageFactory(), "http://localhost:4566", s3);
        byte[] zip = LambdaWebZips.indexJs("");
        when(s3.openObject("code", "app.zip", null)).thenReturn(nullVersion(object(zip, LambdaWebCode.MAX_BYTES + 1)));

        AwsException create = error(() -> service.createFunction(REGION, createRequest("app")));
        assertEquals("ValidationException", create.getErrorCode());
        assertEquals(400, create.getHttpStatus());
        assertEquals("You must enable S3 versioning for your S3 bucket when S3ObjectStorageMode is set to REFERENCE.",
                create.getMessage());
        assertEquals("The function arn:aws:lambda:us-east-1:000000000000:web-function/app does not exist.",
                message(() -> service.getFunction(REGION, "app")));

        ObjectNode pinned = createRequest("app");
        ((ObjectNode) pinned.path("revisionConfig").path("buildConfig").path("codeConfig").path("s3Object"))
                .put("versionId", "null");
        when(s3.openObject("code", "app.zip", "null")).thenReturn(nullVersion(object(zip)));
        assertNull(service.createFunction(REGION, pinned).revisions.values().iterator().next().failureReason);
    }

    @Test
    void lambdaMustBeAllowedToReadTheObjectVersionAfterTheSizeCheck() throws IOException {
        S3Service s3 = mock(S3Service.class);
        LambdaWebService service = service(new MemoryStorageFactory(), "http://localhost:4566", s3);
        when(serviceCalls.authorize(eq("lambda"), eq("s3:GetObjectVersion"), eq("arn:aws:s3:::code/app.zip"),
                startsWith(ARN + "/revision/rev-"), eq(ACCOUNT_ID), eq(Map.of("s3:versionid", List.of("v1")))))
                .thenReturn(Decision.DENY);
        when(s3.openObject("code", "app.zip", null)).thenReturn(object(new byte[0]));

        AwsException create = error(() -> service.createFunction(REGION, createRequest("app")));
        assertEquals("ValidationException", create.getErrorCode());
        assertEquals(400, create.getHttpStatus());
        assertEquals("Lambda service principal lambda.amazonaws.com does not have s3:GetObject and s3:GetObjectVersion"
                + " permission on s3://code/app.zip. Update the bucket policy to grant the Lambda service principal"
                + " access and retry.", create.getMessage());
        assertEquals("The function arn:aws:lambda:us-east-1:000000000000:web-function/app does not exist.",
                message(() -> service.getFunction(REGION, "app")));
        Files.createDirectories(storageDir.resolve("code"));
        try (Stream<Path> copies = Files.walk(storageDir.resolve("code"))) {
            assertEquals(0, copies.filter(Files::isRegularFile).count());
        }

        when(s3.openObject("code", "app.zip", null))
                .thenReturn(object(LambdaWebZips.indexJs(""), LambdaWebCode.MAX_BYTES + 1));
        assertEquals("Zipped size must be smaller than 536870912 bytes",
                message(() -> service.createFunction(REGION, createRequest("app"))));
    }

    @Test
    void anActiveRevisionPastFiftyDeletesTheOldestUnusedOnes() {
        S3Service s3 = mock(S3Service.class);
        LambdaWebService service = service(new MemoryStorageFactory(), "http://localhost:4566", s3);
        byte[] zip = LambdaWebZips.indexJs("");
        when(s3.openObject("code", "app.zip", null)).thenAnswer(invocation -> object(zip));
        String pinned = service.createFunction(REGION, createRequest("app")).revisions.keySet().iterator().next();
        ObjectNode pin = endpointConfig("pin").put("autoDeploymentMode", "Disabled");
        pin.putArray("revisionWeights").addObject().put("revisionId", pinned).put("weight", 100);
        service.createEndpoint(REGION, "app", pin);
        when(s3.openObject("code", "app.zip", null)).thenAnswer(invocation -> object(NOT_A_ZIP));
        String failed = service.createRevision(REGION, "app", revisionConfig(30)).revisionId;
        when(s3.openObject("code", "app.zip", null)).thenAnswer(invocation -> object(zip));
        List<String> active = new ArrayList<>();
        for (int i = 0; i < 48; i++) {
            active.add(service.createRevision(REGION, "app", revisionConfig(30)).revisionId);
        }
        assertEquals(50, service.getFunction(REGION, "app").revisions.size());

        service.createRevision(REGION, "app", revisionConfig(30));
        Set<String> kept = service.getFunction(REGION, "app").revisions.keySet();
        assertEquals(50, kept.size());
        assertFalse(kept.contains(failed));
        assertTrue(kept.contains(pinned));

        when(s3.openObject("code", "app.zip", null)).thenAnswer(invocation -> object(NOT_A_ZIP));
        service.createRevision(REGION, "app", revisionConfig(30));
        assertEquals(51, service.getFunction(REGION, "app").revisions.size(), "a Failed revision cleans up nothing");

        when(s3.openObject("code", "app.zip", null)).thenAnswer(invocation -> object(zip));
        service.createRevision(REGION, "app", revisionConfig(30));
        kept = service.getFunction(REGION, "app").revisions.keySet();
        assertEquals(50, kept.size());
        assertFalse(kept.contains(active.get(0)));
        assertFalse(kept.contains(active.get(1)));
        assertTrue(kept.contains(active.get(2)));
        LambdaWebCode code = new LambdaWebCode(storageDir.resolve("code"));
        assertFalse(Files.exists(code.file(ACCOUNT_ID, REGION, "app", active.get(0))));
        assertTrue(Files.exists(code.file(ACCOUNT_ID, REGION, "app", active.get(2))));
    }

    private ObjectNode stateFilter(String state) {
        ObjectNode request = mapper.createObjectNode();
        request.putArray("filters").addObject().put("name", "state").putArray("values").add(state);
        return request;
    }

    private static S3Service.ObjectRead object(byte[] bytes) {
        return versioned(new S3Object("code", "app.zip", bytes, "application/zip"), bytes);
    }

    /** An object S3 reports as {@code size} bytes, whatever its body. */
    private static S3Service.ObjectRead object(byte[] bytes, long size) {
        return versioned(new S3Object("code", "app.zip", size, "application/zip", "etag"), bytes);
    }

    private static S3Service.ObjectRead versioned(S3Object object, byte[] bytes) {
        object.setVersionId("v1");
        return new S3Service.ObjectRead(object, new ByteArrayInputStream(bytes));
    }

    private static S3Service.ObjectRead nullVersion(S3Service.ObjectRead read) {
        read.object().setVersionId(null);
        return read;
    }

    private static final class MemoryStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private MemoryStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(fileName,
                    ignored -> AccountAwareStorageBackend.inMemory(ACCOUNT_ID));
        }
    }

    /** Real files rather than memory, so only what reached the backend comes back. */
    private static final class FileStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();
        private final Path directory;

        private FileStorageFactory(Path directory) {
            super(null, null);
            this.directory = directory;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(fileName, ignored -> {
                PersistentStorage<String, V> inner = new PersistentStorage<>(directory.resolve(fileName), typeReference);
                inner.load();
                return new AccountAwareStorageBackend<V>(inner, null, ACCOUNT_ID);
            });
        }
    }

    @Test
    void jsonTreeOfARevisionRoundTrips() {
        LambdaWebService service = service();
        WebFunction fn = service.createFunction(REGION, createRequest("app"));
        JsonNode tree = mapper.valueToTree(fn);
        WebFunction back = mapper.convertValue(tree, WebFunction.class);
        assertEquals(fn.revisions.keySet(), back.revisions.keySet());
        assertEquals(fn.endpoints.get("dev").domainName, back.endpoints.get("dev").domainName);
    }
}
