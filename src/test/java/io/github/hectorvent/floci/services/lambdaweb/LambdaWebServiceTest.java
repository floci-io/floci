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
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunction;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunctionEndpoint;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunctionRevision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LambdaWebServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String ARN = "arn:aws:lambda:us-east-1:000000000000:web-function/app";
    private static final String ROLE = "arn:aws:iam::000000000000:role/web";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @TempDir
    Path storageDir;

    private LambdaWebService service() {
        return service(new MemoryStorageFactory());
    }

    private LambdaWebService service(StorageFactory storage) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        LambdaWebService service = new LambdaWebService(new RegionResolver(REGION, ACCOUNT_ID), storage, config, null,
                mapper, null);
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
