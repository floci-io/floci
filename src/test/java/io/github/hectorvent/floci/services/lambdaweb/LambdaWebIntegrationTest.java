package io.github.hectorvent.floci.services.lambdaweb;

import io.github.hectorvent.floci.services.s3.S3Service;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;

/**
 * The Lambda Web control plane as the AWS CLI and SDKs drive it. Shapes, state words and error
 * wording follow responses recorded against the live service.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LambdaWebIntegrationTest {

    private static final String BASE = "/2025-03-07";
    private static final String FN = "web-it-app";
    private static final String ARN = "arn:aws:lambda:us-east-1:000000000000:web-function/" + FN;
    private static final String BUCKET = "web-it-code";
    private static final String KEY = "app.zip";
    private static final String ROLE = "arn:aws:iam::000000000000:role/web-it-role";
    private static final String BUILD = """
            {"codeConfig": {"s3Object": {"bucket": "%s", "key": "%s"}}, "runtimeConfig": {"runtime": "nodejs24.x"}}
            """.formatted(BUCKET, KEY);

    @Inject
    S3Service s3Service;

    private static String revision1;
    private static String revision2;
    private static String domainName;

    @BeforeAll
    void seedCode() {
        s3Service.createBucket(BUCKET, "us-east-1");
        s3Service.putObject(BUCKET, KEY, "zip".getBytes(StandardCharsets.UTF_8), "application/zip", Map.of());
    }

    @Test
    @Order(1)
    void createWebFunctionReportsPendingChildren() {
        revision1 = given()
                .contentType("application/json")
                .body("""
                        {"functionName": "%s",
                         "revisionConfig": {"buildConfig": %s, "serviceConfig": {"executionRoleArn": "%s"}},
                         "endpointConfig": {"endpointName": "dev", "endpointType": "HomeRegion",
                                            "authType": "ApplicationManaged", "autoDeploymentMode": "LatestRevision"},
                         "tags": {"env": "it"}}
                        """.formatted(FN, BUILD, ROLE))
                .when().put(BASE + "/web-functions")
                .then()
                .statusCode(202)
                .body("functionName", equalTo(FN))
                .body("functionArn", equalTo(ARN))
                .body("state", equalTo("Active"))
                .body("stateReason", equalTo("The function is active."))
                .body("tags.env", equalTo("it"))
                .body("revision.state", equalTo("Pending"))
                .body("revision.stateReason", equalTo("The revision is being activated."))
                .body("revision.revisionId", matchesPattern("rev-[a-z0-9]{5}"))
                .body("revision.revisionArn", matchesPattern(Pattern.quote(ARN + "/revision/") + "rev-[a-z0-9]{5}"))
                .body("endpoint.state", equalTo("Pending"))
                .body("endpoint.stateReason",
                        equalTo("The endpoint is being activated and is not yet invokable in any region."))
                .body("endpoint.endpointArn", equalTo(ARN + "/endpoint/dev"))
                .body("endpoint.autoDeploymentMode", equalTo("LatestRevision"))
                .body("endpoint.regions", equalTo(List.of("us-east-1")))
                .body("endpoint.revisionWeights[0].weight", equalTo(100))
                .body("endpoint.domainName", matchesPattern("[a-z0-9]{16}\\.001\\.lambda-web\\.us-east-1\\..+"))
                .body("createdAt", matchesPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z"))
                .extract().path("revision.revisionId");
    }

    @Test
    @Order(2)
    void readsSettleToActive() {
        given().when().get(BASE + "/web-functions/" + FN)
                .then().statusCode(200)
                .body("state", equalTo("Active"));

        domainName = given().when().get(BASE + "/web-functions/" + FN + "/endpoints/dev")
                .then().statusCode(200)
                .body("state", equalTo("Active"))
                .body("stateReason", equalTo("The endpoint is active and invokable."))
                .body("updateStatus", nullValue())
                .body("regionalEndpoints.us-east-1.state", equalTo("Active"))
                .body("regionalEndpoints.us-east-1.stateReason", equalTo("The endpoint is active in the region."))
                .body("regionalEndpoints.us-east-1.revisionWeights[0].revisionId", equalTo(revision1))
                .body("functionArn", equalTo(ARN))
                .extract().path("domainName");

        given().when().get(BASE + "/web-functions/" + FN + "/revisions/" + revision1)
                .then().statusCode(200)
                .body("state", equalTo("Active"))
                .body("stateReason", equalTo("The revision is active."))
                .body("buildConfig.codeConfig.s3Object.bucket", equalTo(BUCKET))
                .body("buildConfig.codeConfig.s3Object.versionId", nullValue())
                .body("buildConfig.runtimeConfig.runtime", equalTo("nodejs24.x"))
                .body("serviceConfig.timeoutSeconds", equalTo(30))
                .body("serviceConfig.maxConcurrencyPerEnvironment", equalTo(64))
                .body("serviceConfig.environmentVariables", nullValue())
                .body("serviceConfig.telemetryConfig.loggingConfig.logGroup", equalTo("/aws/lambda/web/" + FN))
                .body("serviceConfig.telemetryConfig.loggingConfig.applicationLogLevel", equalTo("INFO"))
                .body("serviceConfig.telemetryConfig.loggingConfig.systemLogLevel", equalTo("INFO"));

        // The function is addressable by its ARN too, and the account settings count it.
        given().urlEncodingEnabled(false)
                .when().get(BASE + "/web-functions/" + URLEncoder.encode(ARN, StandardCharsets.UTF_8))
                .then().statusCode(200).body("functionName", equalTo(FN));
        given().when().get(BASE + "/web-account-settings")
                .then().statusCode(200)
                .body("accountQuotas.maxEndpointsPerFunction", equalTo(10))
                .body("accountQuotas.maxRevisionsPerFunction", equalTo(50))
                .body("accountQuotas.maxTotalArmVCpus", equalTo(2000))
                .body("accountQuotas.maxTotalRateLimit", equalTo(100000))
                .body("accountUsage.functionCount", equalTo(1));
    }

    @Test
    @Order(3)
    void newRevisionMovesALatestRevisionEndpoint() {
        revision2 = given()
                .contentType("application/json")
                .body("""
                        {"buildConfig": %s, "description": "second",
                         "serviceConfig": {"executionRoleArn": "%s", "timeoutSeconds": 60,
                                           "maxConcurrencyPerEnvironment": 8,
                                           "environmentVariables": {"APP_VERSION": "2"},
                                           "telemetryConfig": {"loggingConfig": {"systemLogLevel": "DEBUG"}}}}
                        """.formatted(BUILD, ROLE))
                .when().post(BASE + "/web-functions/" + FN + "/revisions")
                .then()
                .statusCode(202)
                .body("state", equalTo("Pending"))
                .body("description", equalTo("second"))
                .body("serviceConfig.timeoutSeconds", equalTo(60))
                .body("serviceConfig.environmentVariables.APP_VERSION", equalTo("2"))
                .body("serviceConfig.telemetryConfig.loggingConfig.systemLogLevel", equalTo("DEBUG"))
                .extract().path("revisionId");

        given().when().get(BASE + "/web-functions/" + FN + "/endpoints/dev")
                .then().statusCode(200)
                .body("revisionWeights", hasSize(1))
                .body("revisionWeights[0].revisionId", equalTo(revision2))
                .body("updateStatus", nullValue());

        // Newest first.
        given().contentType("application/json").body("{}")
                .when().post(BASE + "/web-functions/" + FN + "/list-revisions")
                .then().statusCode(200)
                .body("revisions", hasSize(2))
                .body("revisions[0].revisionId", equalTo(revision2))
                .body("revisions[1].revisionId", equalTo(revision1))
                .body("nextToken", nullValue());
    }

    @Test
    @Order(4)
    void weightedRoutingUpdate() {
        given().contentType("application/json")
                .body("""
                        {"revisionWeights": [{"revisionId": "%s", "weight": 100}]}
                        """.formatted(revision1))
                .when().patch(BASE + "/web-functions/" + FN + "/endpoints/dev")
                .then().statusCode(400)
                .header("X-Amzn-Errortype", "ValidationException")
                .body("message",
                        equalTo("The revisionWeights cannot be specified when the autoDeploymentMode is LatestRevision."));

        given().contentType("application/json")
                .body("""
                        {"autoDeploymentMode": "Disabled",
                         "revisionWeights": [{"revisionId": "%s", "weight": 90}, {"revisionId": "%s", "weight": 10}]}
                        """.formatted(revision1, revision2))
                .when().patch(BASE + "/web-functions/" + FN + "/endpoints/dev")
                .then().statusCode(202)
                .body("autoDeploymentMode", equalTo("Disabled"))
                .body("revisionWeights[0].revisionId", equalTo(revision1))
                .body("revisionWeights[0].weight", equalTo(90))
                .body("state", equalTo("Active"))
                .body("updateStatus", equalTo("InProgress"))
                .body("updateStatusReason", equalTo("The endpoint is being updated."))
                // The per-region block still shows the weights from before the update.
                .body("regionalEndpoints.us-east-1.revisionWeights", hasSize(1))
                .body("regionalEndpoints.us-east-1.revisionWeights[0].revisionId", equalTo(revision2))
                .body("regionalEndpoints.us-east-1.updateStatus", equalTo("InProgress"))
                .body("regionalEndpoints.us-east-1.updateStatusReason",
                        equalTo("The endpoint is being updated in the region."));

        given().when().get(BASE + "/web-functions/" + FN + "/endpoints/dev")
                .then().statusCode(200)
                .body("updateStatus", equalTo("Successful"))
                .body("updateStatusReason", equalTo("The endpoint is updated."))
                .body("regionalEndpoints.us-east-1.revisionWeights", hasSize(2))
                .body("regionalEndpoints.us-east-1.updateStatus", equalTo("Successful"))
                .body("regionalEndpoints.us-east-1.updateStatusReason", equalTo("The endpoint is updated in the region."));

        given().when().delete(BASE + "/web-functions/" + FN + "/revisions/" + revision2)
                .then().statusCode(409)
                .header("X-Amzn-Errortype", "ConflictException")
                .body("message", equalTo("The revision " + revision2 + " cannot be deleted because the endpoints [dev]"
                        + " are using it. Update the endpoints to use other revisions first."));

        given().contentType("application/json")
                .body("""
                        {"revisionWeights": [{"revisionId": "%s", "weight": 50}, {"revisionId": "%s", "weight": 40}]}
                        """.formatted(revision1, revision2))
                .when().patch(BASE + "/web-functions/" + FN + "/endpoints/dev")
                .then().statusCode(400)
                .body("message",
                        equalTo("The revisionWeights must contain unique revisionIds, and the weights must total exactly 100."));

        given().contentType("application/json")
                .body("""
                        {"revisionWeights": [{"revisionId": "rev-zzzzz", "weight": 100}]}
                        """)
                .when().patch(BASE + "/web-functions/" + FN + "/endpoints/dev")
                .then().statusCode(404)
                .body("message", equalTo("The revision rev-zzzzz does not exist."));

        // Back to LatestRevision: the newest revision takes all traffic without weights in the request.
        given().contentType("application/json").body("{\"autoDeploymentMode\": \"LatestRevision\"}")
                .when().patch(BASE + "/web-functions/" + FN + "/endpoints/dev")
                .then().statusCode(202)
                .body("revisionWeights", hasSize(1))
                .body("revisionWeights[0].revisionId", equalTo(revision2));
    }

    @Test
    @Order(5)
    void scalingAndThrottleConfigs() {
        given().contentType("application/json")
                .body("{\"scalingConfig\": {\"maxEnvironments\": 2}, \"throttleConfig\": {\"rateLimit\": 100}}")
                .when().patch(BASE + "/web-functions/" + FN + "/endpoints/dev")
                .then().statusCode(202)
                .body("scalingConfig.maxEnvironments", equalTo(2))
                .body("throttleConfig.rateLimit", equalTo(100));

        given().contentType("application/json").body("{\"scalingConfig\": {}}")
                .when().patch(BASE + "/web-functions/" + FN + "/endpoints/dev")
                .then().statusCode(202)
                .body("scalingConfig", equalTo(Map.of()))
                .body("throttleConfig.rateLimit", equalTo(100))
                .body("regionalEndpoints.us-east-1.scalingConfig.maxEnvironments", equalTo(2));

        given().contentType("application/json").body("{\"throttleConfig\": {\"rateLimit\": 150}}")
                .when().patch(BASE + "/web-functions/" + FN + "/endpoints/dev")
                .then().statusCode(400)
                .body("message", equalTo("The rateLimit 150 is invalid. Supported rate limits are: [0, 100, 200, 300, 400,"
                        + " 500, 600, 700, 800, 900, 1000, 2000, 3000, 4000, 5000, 6000, 7000, 8000, 9000, 10000]."));

        given().contentType("application/json").body("{\"scalingConfig\": {\"maxEnvironments\": 1}}")
                .when().patch(BASE + "/web-functions/" + FN + "/endpoints/dev")
                .then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value '1' at 'scalingConfig.maxEnvironments'"
                        + " failed to satisfy constraint: Member must have value greater than or equal to 2"));
    }

    @Test
    @Order(6)
    void endpointRules() {
        String endpoints = BASE + "/web-functions/" + FN + "/endpoints";
        given().contentType("application/json")
                .body("{\"endpointName\": \"dev\", \"endpointType\": \"HomeRegion\", \"authType\": \"ApplicationManaged\","
                        + " \"autoDeploymentMode\": \"LatestRevision\"}")
                .when().put(endpoints)
                .then().statusCode(409)
                .body("message", equalTo("The endpoint dev already exists."));

        given().contentType("application/json")
                .body("{\"endpointName\": \"prod\", \"endpointType\": \"HomeRegion\", \"authType\": \"ApplicationManaged\"}")
                .when().put(endpoints)
                .then().statusCode(400)
                .body("message", equalTo("The revisionWeights must be specified when the autoDeploymentMode is Disabled."));

        given().contentType("application/json")
                .body("{\"endpointName\": \"prod\", \"endpointType\": \"MultiRegion\", \"regions\": [\"us-west-2\"],"
                        + " \"authType\": \"ApplicationManaged\", \"autoDeploymentMode\": \"LatestRevision\"}")
                .when().put(endpoints)
                .then().statusCode(400)
                .body("message", equalTo("The autoDeploymentMode can only be Disabled when the endpointType is MultiRegion."));

        given().contentType("application/json")
                .body("{\"endpointName\": \"prod\", \"endpointType\": \"HomeRegion\", \"regions\": [\"us-west-2\"],"
                        + " \"authType\": \"ApplicationManaged\", \"autoDeploymentMode\": \"LatestRevision\"}")
                .when().put(endpoints)
                .then().statusCode(400)
                .body("message", equalTo("The endpointType HomeRegion supports only the home region us-east-1."
                        + " The request results in the regions [us-east-1, us-west-2]."));

        given().contentType("application/json")
                .body("{\"endpointName\": \"prod\", \"endpointType\": \"MultiRegion\", \"regions\": [\"us-east-1\"],"
                        + " \"authType\": \"ApplicationManaged\","
                        + " \"revisionWeights\": [{\"revisionId\": \"" + revision1 + "\", \"weight\": 100}]}")
                .when().put(endpoints)
                .then().statusCode(400)
                .body("message", equalTo("The endpointType MultiRegion requires at least 2 distinct regions. The home region"
                        + " us-east-1 is added automatically, so specify at least 1 additional region."
                        + " The request results in the regions [us-east-1]."));

        given().contentType("application/json")
                .body("{\"endpointName\": \"prod\", \"endpointType\": \"HomeRegion\", \"authType\": \"NONE\"}")
                .when().put(endpoints)
                .then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value 'NONE' at 'authType' failed to satisfy"
                        + " constraint: Member must satisfy enum value set: [ApplicationManaged, IamAuth]"));

        given().contentType("application/json")
                .body("{\"endpointName\": \"-x\", \"endpointType\": \"HomeRegion\", \"authType\": \"ApplicationManaged\","
                        + " \"autoDeploymentMode\": \"LatestRevision\"}")
                .when().put(endpoints)
                .then().statusCode(400)
                .body("message", equalTo("The endpoint ARN -x with the function name " + FN
                        + ", the region us-east-1 and the account id 000000000000 is invalid."));

        // A MultiRegion endpoint gets a global domain and a per-region block for every region.
        given().contentType("application/json")
                .body("{\"endpointName\": \"global\", \"endpointType\": \"MultiRegion\", \"regions\": [\"us-west-2\"],"
                        + " \"authType\": \"IamAuth\", \"description\": \"d\","
                        + " \"revisionWeights\": [{\"revisionId\": \"" + revision1 + "\", \"weight\": 100}]}")
                .when().put(endpoints)
                .then().statusCode(202)
                .body("domainName", matchesPattern("[a-z0-9]{16}\\.lambda-web\\.global\\..+"))
                .body("regions", equalTo(List.of("us-east-1", "us-west-2")))
                .body("regionalEndpoints.us-west-2.state", equalTo("Pending"))
                .body("regionalEndpoints.us-west-2.authType", equalTo("IamAuth"))
                .body("description", equalTo("d"));

        given().contentType("application/json").body("{}")
                .when().post(BASE + "/web-functions/" + FN + "/list-endpoints")
                .then().statusCode(200)
                .body("endpoints", hasSize(2))
                .body("endpoints[0].endpointName", equalTo("dev"))
                .body("endpoints[1].endpointName", equalTo("global"))
                .body("endpoints[0].functionArn", nullValue());

        given().when().delete(endpoints + "/global").then().statusCode(204);
        given().when().get(endpoints + "/global")
                .then().statusCode(404)
                .header("X-Amzn-Errortype", "ResourceNotFoundException")
                .body("message", equalTo("The endpoint global does not exist."));
    }

    @Test
    @Order(7)
    void revisionRules() {
        String revisions = BASE + "/web-functions/" + FN + "/revisions";
        given().contentType("application/json")
                .body("""
                        {"buildConfig": {"codeConfig": {"s3Object": {"bucket": "%s", "key": "%s"}},
                                         "runtimeConfig": {"runtime": "nodejs22.x"}},
                         "serviceConfig": {"executionRoleArn": "%s"}}
                        """.formatted(BUCKET, KEY, ROLE))
                .when().post(revisions)
                .then().statusCode(400)
                .body("message", equalTo("The runtime nodejs22.x is invalid."));

        given().contentType("application/json")
                .body("""
                        {"buildConfig": %s, "serviceConfig": {"executionRoleArn": "%s", "timeoutSeconds": 2}}
                        """.formatted(BUILD, ROLE))
                .when().post(revisions)
                .then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value '2' at 'serviceConfig.timeoutSeconds' failed"
                        + " to satisfy constraint: Member must have value greater than or equal to 3"));

        given().contentType("application/json")
                .body("""
                        {"buildConfig": %s, "serviceConfig": {"executionRoleArn": "%s",
                         "environmentVariables": {"AWS_REGION": "x"}}}
                        """.formatted(BUILD, ROLE))
                .when().post(revisions)
                .then().statusCode(400)
                .body("message", equalTo("The environmentVariables cannot contain reserved keys."
                        + " The request specified the reserved keys [AWS_REGION]."));

        given().contentType("application/json")
                .body("""
                        {"buildConfig": %s, "serviceConfig": {}}
                        """.formatted(BUILD))
                .when().post(revisions)
                .then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value null at 'serviceConfig.executionRoleArn'"
                        + " failed to satisfy constraint: Member must not be null"));

        given().contentType("application/json")
                .body("""
                        {"buildConfig": {"codeConfig": {"s3Object": {"bucket": "%s", "key": "missing.zip"}},
                                         "runtimeConfig": {"runtime": "nodejs24.x"}},
                         "serviceConfig": {"executionRoleArn": "%s"}}
                        """.formatted(BUCKET, ROLE))
                .when().post(revisions)
                .then().statusCode(400)
                .body("message", equalTo("The specified S3 object does not exist: s3://" + BUCKET
                        + "/missing.zip. Verify the object exists in the bucket."));

        given().contentType("application/json")
                .body("""
                        {"buildConfig": %s, "serviceConfig": {"executionRoleArn": "%s",
                         "telemetryConfig": {"loggingConfig": {"logGroup": "aws/lambda/x"}}}}
                        """.formatted(BUILD, ROLE))
                .when().post(revisions)
                .then().statusCode(400)
                .body("message", equalTo("The logGroup aws/lambda/x is invalid. Log group names starting with aws/"
                        + " (case-insensitive) are reserved for AWS."));

        given().contentType("application/json")
                .body("""
                        {"buildConfig": %s, "serviceConfig": {"executionRoleArn": "%s", "timeoutSeconds": "60"}}
                        """.formatted(BUILD, ROLE))
                .when().post(revisions)
                .then().statusCode(400)
                .header("X-Amzn-Errortype", "SerializationException")
                .body("message", equalTo("STRING_VALUE can not be converted to an Integer"));

        given().contentType("application/json").body("not json")
                .when().post(revisions)
                .then().statusCode(400)
                .header("X-Amzn-Errortype", "SerializationException");

        given().when().get(revisions + "/rev-nope")
                .then().statusCode(400)
                .body("message", equalTo("Invalid revision id or ARN 'rev-nope'"));
        given().when().get(revisions + "/rev-zzzzz")
                .then().statusCode(404)
                .body("message", equalTo("The revision rev-zzzzz does not exist."));
    }

    @Test
    @Order(8)
    void tagsAndResourcePolicy() {
        given().contentType("application/json").body("{\"Tags\": {\"owner\": \"floci\"}}")
                .when().post(BASE + "/tags/" + ARN)
                .then().statusCode(204);
        given().when().get(BASE + "/tags/" + ARN)
                .then().statusCode(200)
                .body("Tags.env", equalTo("it"))
                .body("Tags.owner", equalTo("floci"));
        given().queryParam("tagKeys", "env").when().delete(BASE + "/tags/" + ARN)
                .then().statusCode(204);
        given().when().get(BASE + "/tags/" + ARN)
                .then().statusCode(200)
                .body("Tags.env", nullValue())
                .body("Tags.owner", equalTo("floci"));
        given().when().get(BASE + "/tags/" + ARN + "/endpoint/dev")
                .then().statusCode(400)
                .body("message", containsString("at 'resource' failed to satisfy constraint:"
                        + " Member must satisfy regular expression pattern: arn:[^:]+:lambda:[^:]+:\\d{12}:web-function/[^/]+"));

        String policyPath = BASE + "/resource-policy/" + ARN;
        given().when().get(policyPath)
                .then().statusCode(404)
                .body("message", equalTo("The function does not have a policy."));

        String publicPolicy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\","
                + "\"Action\":\"lambda:InvokeWebFunctionEndpoint\",\"Resource\":\"" + ARN + "/endpoint/*\"}]}";
        given().contentType("application/json").body(Map.of("Policy", publicPolicy))
                .when().put(policyPath)
                .then().statusCode(400)
                .body("message", equalTo("Lambda blocked the creation of this policy because it would grant public"
                        + " access to your function."));

        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":"
                + "{\"AWS\":\"arn:aws:iam::000000000000:root\"},\"Action\":\"lambda:InvokeWebFunctionEndpoint\","
                + "\"Resource\":\"" + ARN + "/endpoint/*\"}]}";
        String revisionId = given().contentType("application/json").body(Map.of("Policy", policy))
                .when().put(policyPath)
                .then().statusCode(200)
                .body("Policy", equalTo(policy))
                .body("RevisionId", matchesPattern("[0-9a-f-]{36}"))
                .extract().path("RevisionId");

        given().contentType("application/json")
                .body(Map.of("Policy", policy, "RevisionId", "00000000-0000-0000-0000-000000000000"))
                .when().put(policyPath)
                .then().statusCode(409)
                .body("message", equalTo("The policy was updated or deleted by another request."
                        + " Try again with the latest policy revisionId."));
        given().queryParam("RevisionId", "00000000-0000-0000-0000-000000000000").when().delete(policyPath)
                .then().statusCode(409)
                .body("message", equalTo("The policy was updated by another request. Try again."));
        given().queryParam("RevisionId", revisionId).when().delete(policyPath)
                .then().statusCode(204);
        given().when().delete(policyPath)
                .then().statusCode(404)
                .body("message", equalTo("The function does not have a policy."));
    }

    @Test
    @Order(9)
    void listingAndNames() {
        given().contentType("application/json").body("{\"filters\": [{\"name\": \"nope\", \"values\": [\"x\"]}]}")
                .when().post(BASE + "/web-functions")
                .then().statusCode(400)
                .body("message", equalTo("The filter names [nope] are invalid. Supported filter names are: [state]."));
        given().contentType("application/json").body("{\"filters\": [{\"name\": \"state\", \"values\": [\"Bogus\"]}]}")
                .when().post(BASE + "/web-functions")
                .then().statusCode(200)
                .body("functions", hasSize(0));
        given().contentType("application/json").body("{\"nextToken\": \"not-a-token\"}")
                .when().post(BASE + "/web-functions")
                .then().statusCode(400)
                .body("message", equalTo("The nextToken not-a-token is invalid or has expired."));

        given().contentType("application/json").body("{\"functionName\": \"-bad\"}")
                .when().put(BASE + "/web-functions")
                .then().statusCode(400)
                .body("message", equalTo("Invalid function name or ARN '-bad'"));
        given().contentType("application/json").body("{\"functionName\": \"lonely\"}")
                .when().put(BASE + "/web-functions")
                .then().statusCode(400)
                .body("message", equalTo("The revisionConfig and endpointConfig must be specified."));
        given().contentType("application/json").body("{}")
                .when().put(BASE + "/web-functions")
                .then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value null at 'functionName' failed to satisfy"
                        + " constraint: Member must not be null"));
        given().contentType("application/json")
                .body("""
                        {"functionName": "%s",
                         "revisionConfig": {"buildConfig": %s, "serviceConfig": {"executionRoleArn": "%s"}},
                         "endpointConfig": {"endpointName": "dev", "endpointType": "HomeRegion",
                                            "authType": "ApplicationManaged"}}
                        """.formatted(FN, BUILD, ROLE))
                .when().put(BASE + "/web-functions")
                .then().statusCode(409)
                .body("message", equalTo("The function " + ARN + " already exists."));
        given().when().get(BASE + "/web-functions/does-not-exist")
                .then().statusCode(404)
                .body("message", equalTo("The function arn:aws:lambda:us-east-1:000000000000:web-function/does-not-exist"
                        + " does not exist."));
        given().urlEncodingEnabled(false)
                .when().get(BASE + "/web-functions/" + URLEncoder.encode(ARN.replace(":us-east-1:", ":eu-west-1:"),
                        StandardCharsets.UTF_8))
                .then().statusCode(400)
                .body("message", equalTo("The operation is only allowed in the function's home region eu-west-1."));
        given().urlEncodingEnabled(false)
                .when().get(BASE + "/web-functions/" + URLEncoder.encode(ARN.replace("000000000000", "111111111111"),
                        StandardCharsets.UTF_8))
                .then().statusCode(403)
                .header("X-Amzn-Errortype", "AccessDeniedException")
                .body("message", equalTo("User: arn:aws:iam::000000000000:user/unknown is not authorized to perform:"
                        + " lambda:GetWebFunction on resource: arn:aws:lambda:us-east-1:111111111111:web-function/" + FN
                        + " because no resource-based policy allows the lambda:GetWebFunction action"));
        given().contentType("application/json").body("{\"nextToken\": \"00000000-0000-0000-0000-000000000000\"}")
                .when().post(BASE + "/web-functions")
                .then().statusCode(400)
                .body("message", equalTo("The nextToken 00000000-0000-0000-0000-000000000000 is invalid or has expired."));
    }

    @Test
    @Order(10)
    void deleteRemovesEverything() {
        given().when().delete(BASE + "/web-functions/" + FN).then().statusCode(204);
        given().when().get(BASE + "/web-functions/" + FN)
                .then().statusCode(404)
                .body("message", equalTo("The function " + ARN + " does not exist."));
        given().when().get(BASE + "/web-functions/" + FN + "/endpoints/dev")
                .then().statusCode(404)
                .body("message", equalTo("The function " + ARN + " does not exist."));
        given().when().delete(BASE + "/web-functions/" + FN)
                .then().statusCode(404);
        given().when().get(BASE + "/web-account-settings")
                .then().statusCode(200)
                .body("accountUsage.functionCount", equalTo(0));
    }
}
