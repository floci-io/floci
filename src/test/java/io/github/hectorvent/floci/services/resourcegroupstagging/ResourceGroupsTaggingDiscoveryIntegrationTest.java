package io.github.hectorvent.floci.services.resourcegroupstagging;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * GetResources, GetTagKeys and GetTagValues see the tags a resource's own service holds, so a
 * queue, function, log group or API Gateway resource tagged through its own service is
 * discoverable without ever being tagged through the Resource Groups Tagging API.
 */
@QuarkusTest
class ResourceGroupsTaggingDiscoveryIntegrationTest {

    private static final String TAGGING_TARGET = "ResourceGroupsTaggingAPI_20170126.";
    private static final String JSON_1_0 = "application/x-amz-json-1.0";
    private static final String JSON_1_1 = "application/x-amz-json-1.1";
    private static final String ARN_PREFIX = "arn:aws:%s:us-east-1:000000000000:";
    private static final String APIGATEWAY_ARN_PREFIX = "arn:aws:apigateway:us-east-1::";
    private static final String IAM_AUTH = "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void sqsQueueIsDiscoveredByTypeAndItsOwnTags() {
        String name = "discovery-" + unique();
        String marker = unique();
        String arn = ARN_PREFIX.formatted("sqs") + name;
        String queueUrl = sqs("CreateQueue", """
                {"QueueName": "%s", "tags": {"fd": "%s", "pn": "p1", "pt": "t1"}}
                """.formatted(name, marker))
            .then()
            .statusCode(200)
            .extract().path("QueueUrl");

        getResources("""
                {"ResourceTypeFilters": ["sqs:queue"], "TagFilters": [%s, %s, %s]}
                """.formatted(tagFilter("fd", marker), tagFilter("pn", "p1"), tagFilter("pt", "t1")))
            .body("ResourceTagMappingList.ResourceARN", contains(arn))
            .body("ResourceTagMappingList[0].Tags.size()", equalTo(3))
            .body("ResourceTagMappingList[0].Tags.find { it.Key == 'fd' }.Value", equalTo(marker))
            .body("ResourceTagMappingList[0].Tags.find { it.Key == 'pn' }.Value", equalTo("p1"))
            .body("ResourceTagMappingList[0].Tags.find { it.Key == 'pt' }.Value", equalTo("t1"));

        sqs("TagQueue", """
                {"QueueUrl": "%s", "Tags": {"added": "yes"}}
                """.formatted(queueUrl)).then().statusCode(200);
        getResources(markerAndAddedFilter("sqs:queue", marker))
            .body("ResourceTagMappingList.ResourceARN", contains(arn));

        sqs("UntagQueue", """
                {"QueueUrl": "%s", "TagKeys": ["added"]}
                """.formatted(queueUrl)).then().statusCode(200);
        getResources(markerAndAddedFilter("sqs:queue", marker))
            .body("ResourceTagMappingList", empty());

        sqs("DeleteQueue", """
                {"QueueUrl": "%s"}
                """.formatted(queueUrl)).then().statusCode(200);
        getResources(markerFilter("sqs:queue", marker))
            .body("ResourceTagMappingList", empty());
    }

    @Test
    void lambdaFunctionIsDiscoveredByTypeAndItsOwnTags() {
        String name = "discovery-" + unique();
        String marker = unique();
        String arn = given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "Runtime": "nodejs20.x",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "index.handler",
                    "Tags": {"fd": "%s"}
                }
                """.formatted(name, marker))
        .when()
            .post("/2015-03-31/functions")
        .then()
            .statusCode(201)
            .extract().path("FunctionArn");
        assertThat(arn, equalTo(ARN_PREFIX.formatted("lambda") + "function:" + name));

        getResources(markerFilter("lambda:function", marker))
            .body("ResourceTagMappingList.ResourceARN", contains(arn))
            .body("ResourceTagMappingList[0].Tags.find { it.Key == 'fd' }.Value", equalTo(marker));

        given()
            .contentType("application/json")
            .body("""
                {"Tags": {"added": "yes"}}
                """)
        .when()
            .post("/2017-03-31/tags/" + arn)
        .then()
            .statusCode(204);
        getResources(markerAndAddedFilter("lambda:function", marker))
            .body("ResourceTagMappingList.ResourceARN", contains(arn));

        given()
            .queryParam("tagKeys", "added")
        .when()
            .delete("/2017-03-31/tags/" + arn)
        .then()
            .statusCode(204);
        getResources(markerAndAddedFilter("lambda:function", marker))
            .body("ResourceTagMappingList", empty());

        given()
        .when()
            .delete("/2015-03-31/functions/" + name)
        .then()
            .statusCode(204);
        getResources(markerFilter("lambda:function", marker))
            .body("ResourceTagMappingList", empty());
    }

    @Test
    void logGroupIsDiscoveredWithoutWildcardSuffix() {
        String name = "/probe/" + unique();
        String marker = unique();
        logs("CreateLogGroup", """
                {"logGroupName": "%s", "tags": {"fd": "%s"}}
                """.formatted(name, marker)).then().statusCode(200);

        getResources(markerFilter("logs:log-group", marker))
            .body("ResourceTagMappingList.ResourceARN", contains(ARN_PREFIX.formatted("logs") + "log-group:" + name));

        logs("TagLogGroup", """
                {"logGroupName": "%s", "tags": {"added": "yes"}}
                """.formatted(name)).then().statusCode(200);
        getResources(markerAndAddedFilter("logs:log-group", marker))
            .body("ResourceTagMappingList.ResourceARN", contains(ARN_PREFIX.formatted("logs") + "log-group:" + name));

        logs("UntagLogGroup", """
                {"logGroupName": "%s", "tags": ["added"]}
                """.formatted(name)).then().statusCode(200);
        getResources(markerAndAddedFilter("logs:log-group", marker))
            .body("ResourceTagMappingList", empty());

        logs("DeleteLogGroup", """
                {"logGroupName": "%s"}
                """.formatted(name)).then().statusCode(200);
        getResources(markerFilter("logs:log-group", marker))
            .body("ResourceTagMappingList", empty());
    }

    @Test
    void tagKeysAndValuesIncludeKeySetOnlyThroughSqs() {
        String key = "sqs-only-" + unique();
        String value = unique();
        String queueUrl = sqs("CreateQueue", """
                {"QueueName": "discovery-%s", "tags": {"%s": "%s"}}
                """.formatted(unique(), key, value))
            .then()
            .statusCode(200)
            .extract().path("QueueUrl");

        assertThat(allTagKeys(), hasItem(key));
        tagging("GetTagValues", """
                {"Key": "%s"}
                """.formatted(key))
            .then()
            .statusCode(200)
            .body("TagValues", contains(value));

        sqs("DeleteQueue", """
                {"QueueUrl": "%s"}
                """.formatted(queueUrl)).then().statusCode(200);
    }

    @Test
    void apiKeyIsDiscoveredByTypeAndItsOwnTags() {
        String marker = unique();
        String id = createApiKey(marker);
        String arn = APIGATEWAY_ARN_PREFIX + "/apikeys/" + id;

        getResources(rsidFilter("apigateway:apikeys", marker))
            .body("ResourceTagMappingList.ResourceARN", contains(arn))
            .body("ResourceTagMappingList[0].Tags.size()", equalTo(3))
            .body("ResourceTagMappingList[0].Tags.find { it.Key == 'cid' }.Value", equalTo("c1"));
        getResources(rsidFilter("apigateway:/apikeys", marker))
            .body("ResourceTagMappingList", empty());

        deleteApiKey(id);
        getResources(rsidFilter("apigateway:apikeys", marker))
            .body("ResourceTagMappingList", empty());
    }

    @Test
    void taggingApiWritesReachTheApiKey() {
        String marker = unique();
        String cid = "c2-" + marker;
        String id = createApiKey(marker);
        String arn = APIGATEWAY_ARN_PREFIX + "/apikeys/" + id;

        tagging("TagResources", """
                {"ResourceARNList": ["%s"], "Tags": {"cid": "%s"}}
                """.formatted(arn, cid))
            .then()
            .statusCode(200)
            .body("FailedResourcesMap", anEmptyMap());
        tagging("UntagResources", """
                {"ResourceARNList": ["%s"], "TagKeys": ["baah"]}
                """.formatted(arn))
            .then()
            .statusCode(200)
            .body("FailedResourcesMap", anEmptyMap());

        given()
        .when()
            .get("/apikeys/" + id)
        .then()
            .statusCode(200)
            .body("tags.rsid", equalTo(marker))
            .body("tags.cid", equalTo(cid))
            .body("tags", not(hasKey("baah")));
        getResources("""
                {"ResourceTypeFilters": ["apigateway:apikeys"], "TagFilters": [%s]}
                """.formatted(tagFilter("cid", cid)))
            .body("ResourceTagMappingList.ResourceARN", contains(arn))
            .body("ResourceTagMappingList[0].Tags.size()", equalTo(2));

        deleteApiKey(id);
        getResources("""
                {"TagFilters": [%s]}
                """.formatted(tagFilter("cid", cid)))
            .body("ResourceTagMappingList", empty());
    }

    @Test
    void apiKeyRejectionOfReservedTagIsReportedInFailedResourcesMap() {
        String marker = unique();
        String id = createApiKey(marker);
        String arn = APIGATEWAY_ARN_PREFIX + "/apikeys/" + id;

        tagging("TagResources", """
                {"ResourceARNList": ["%s"], "Tags": {"floci:override-id": "x%s"}}
                """.formatted(arn, marker))
            .then()
            .statusCode(200)
            .body("FailedResourcesMap", hasKey(arn))
            .body("FailedResourcesMap['%s'].StatusCode".formatted(arn), equalTo(400))
            .body("FailedResourcesMap['%s'].ErrorCode".formatted(arn), equalTo("BadRequestException"))
            .body("FailedResourcesMap['%s'].ErrorMessage".formatted(arn), containsString("floci:override-id"));

        getResources(rsidFilter("apigateway:apikeys", marker))
            .body("ResourceTagMappingList.ResourceARN", contains(arn))
            .body("ResourceTagMappingList[0].Tags.Key", not(hasItem("floci:override-id")));
        given()
        .when()
            .get("/apikeys/" + id)
        .then()
            .statusCode(200)
            .body("tags", not(hasKey("floci:override-id")));

        deleteApiKey(id);
    }

    @Test
    void deploymentArnDoesNotRetagTheRestApi() {
        String marker = unique();
        String apiId = createRestApi(marker);
        String restApiArn = APIGATEWAY_ARN_PREFIX + "/restapis/" + apiId;
        String deploymentArn = restApiArn + "/deployments/d1";

        tagging("TagResources", """
                {"ResourceARNList": ["%s"], "Tags": {"leak": "yes"}}
                """.formatted(deploymentArn))
            .then()
            .statusCode(200)
            .body("FailedResourcesMap", anEmptyMap());

        given()
            .pathParam("arn", restApiArn)
        .when()
            .get("/tags/{arn}")
        .then()
            .statusCode(200)
            .body("tags.fd", equalTo(marker))
            .body("tags", not(hasKey("leak")));

        tagging("UntagResources", """
                {"ResourceARNList": ["%s"], "TagKeys": ["leak"]}
                """.formatted(deploymentArn)).then().statusCode(200);
        given().when().delete("/restapis/" + apiId).then().statusCode(202);
    }

    @Test
    void restTagsPathReadsAndWritesApiKeyTags() {
        String marker = unique();
        String id = createApiKey(marker);
        String arn = APIGATEWAY_ARN_PREFIX + "/apikeys/" + id;

        given()
            .pathParam("arn", arn)
            .contentType("application/json")
            .body("""
                {"tags": {"added": "yes"}}
                """)
        .when()
            .put("/tags/{arn}")
        .then()
            .statusCode(204);
        given()
            .pathParam("arn", arn)
        .when()
            .get("/tags/{arn}")
        .then()
            .statusCode(200)
            .body("tags.rsid", equalTo(marker))
            .body("tags.added", equalTo("yes"));
        given()
            .pathParam("arn", arn)
            .queryParam("tagKeys", "added")
        .when()
            .delete("/tags/{arn}")
        .then()
            .statusCode(204);
        given()
        .when()
            .get("/apikeys/" + id)
        .then()
            .statusCode(200)
            .body("tags", not(hasKey("added")));

        deleteApiKey(id);
    }

    @Test
    void apiKeySegmentNestedUnderRestApiArnIsRejected() {
        String marker = unique();
        String apiId = createRestApi(marker);
        String keyId = createApiKey(marker);
        String arn = APIGATEWAY_ARN_PREFIX + "/restapis/" + apiId + "/apikeys/" + keyId;

        putNestedTagIsRejected(arn);
        given()
        .when()
            .get("/apikeys/" + keyId)
        .then()
            .statusCode(200)
            .body("tags.rsid", equalTo(marker))
            .body("tags", not(hasKey("nested")));
        restApiTagsAreUnchanged(apiId, marker);

        deleteApiKey(keyId);
        given().when().delete("/restapis/" + apiId).then().statusCode(202);
    }

    @Test
    void domainSegmentNestedUnderRestApiArnIsRejected() {
        String marker = unique();
        String apiId = createRestApi(marker);
        String domainName = "discovery-" + marker + ".example.com";
        given()
            .contentType("application/json")
            .body("""
                {"domainName": "%s", "regionalCertificateArn": "%s",
                 "endpointConfiguration": {"types": ["REGIONAL"]}, "tags": {"fd": "%s"}}
                """.formatted(domainName, ARN_PREFIX.formatted("acm") + "certificate/" + UUID.randomUUID(), marker))
        .when()
            .post("/domainnames")
        .then()
            .statusCode(201);

        putNestedTagIsRejected(APIGATEWAY_ARN_PREFIX + "/restapis/" + apiId + "/domainnames/" + domainName);
        given()
        .when()
            .get("/domainnames/" + domainName)
        .then()
            .statusCode(200)
            .body("tags.fd", equalTo(marker))
            .body("tags", not(hasKey("nested")));
        restApiTagsAreUnchanged(apiId, marker);

        given().when().delete("/domainnames/" + domainName).then().statusCode(202);
        given().when().delete("/restapis/" + apiId).then().statusCode(202);
    }

    @Test
    void deploymentArnIsRejectedByTheTagsPath() {
        String marker = unique();
        String apiId = createRestApi(marker);

        putNestedTagIsRejected(APIGATEWAY_ARN_PREFIX + "/restapis/" + apiId + "/deployments/d1");
        restApiTagsAreUnchanged(apiId, marker);

        given().when().delete("/restapis/" + apiId).then().statusCode(202);
    }

    @Test
    void restApiIsDiscoveredByType() {
        String marker = unique();
        String apiId = createRestApi(marker);

        getResources(markerFilter("apigateway:restapis", marker))
            .body("ResourceTagMappingList.ResourceARN", contains(APIGATEWAY_ARN_PREFIX + "/restapis/" + apiId));

        given().when().delete("/restapis/" + apiId).then().statusCode(202);
        getResources(markerFilter("apigateway:restapis", marker))
            .body("ResourceTagMappingList", empty());
    }

    @Test
    void stageIsDiscoveredByType() {
        String marker = unique();
        String apiId = createRestApi(marker);
        String deploymentId = given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/restapis/" + apiId + "/deployments")
        .then()
            .statusCode(201)
            .extract().path("id");
        given()
            .contentType("application/json")
            .body("""
                {"stageName": "dev", "deploymentId": "%s", "tags": {"fd": "%s"}}
                """.formatted(deploymentId, marker))
        .when()
            .post("/restapis/" + apiId + "/stages")
        .then()
            .statusCode(201);

        String restApiArn = APIGATEWAY_ARN_PREFIX + "/restapis/" + apiId;
        getResources(markerFilter("apigateway:restapis/stages", marker))
            .body("ResourceTagMappingList.ResourceARN", contains(restApiArn + "/stages/dev"));
        getResources(markerFilter("apigateway:restapis", marker))
            .body("ResourceTagMappingList.ResourceARN", contains(restApiArn, restApiArn + "/stages/dev"));

        given().when().delete("/restapis/" + apiId).then().statusCode(202);
    }

    @Test
    void taggedIamRoleIsNotListed() {
        String role = "discovery-" + unique();
        String marker = unique();
        given()
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "CreateRole")
            .formParam("RoleName", role)
            .formParam("AssumeRolePolicyDocument", """
                {"Version": "2012-10-17", "Statement": [{"Effect": "Allow",
                 "Principal": {"Service": "lambda.amazonaws.com"}, "Action": "sts:AssumeRole"}]}""")
            .formParam("Tags.member.1.Key", "fd")
            .formParam("Tags.member.1.Value", marker)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        getResources("""
                {"TagFilters": [%s]}
                """.formatted(tagFilter("fd", marker)))
            .body("ResourceTagMappingList", empty());

        given()
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "DeleteRole")
            .formParam("RoleName", role)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    void iamRoleTaggedThroughTheTaggingApiIsNotListed() {
        String role = "discovery-" + unique();
        String marker = unique();
        String roleArn = given()
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "CreateRole")
            .formParam("RoleName", role)
            .formParam("AssumeRolePolicyDocument", """
                {"Version": "2012-10-17", "Statement": [{"Effect": "Allow",
                 "Principal": {"Service": "lambda.amazonaws.com"}, "Action": "sts:AssumeRole"}]}""")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateRoleResponse.CreateRoleResult.Role.Arn");

        tagging("TagResources", """
                {"ResourceARNList": ["%s"], "Tags": {"fd": "%s"}}
                """.formatted(roleArn, marker))
            .then()
            .statusCode(200)
            .body("FailedResourcesMap", anEmptyMap());

        getResources("""
                {"TagFilters": [%s]}
                """.formatted(tagFilter("fd", marker)))
            .body("ResourceTagMappingList", empty());

        given()
            .header("Authorization", IAM_AUTH)
            .formParam("Action", "DeleteRole")
            .formParam("RoleName", role)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        getResources("""
                {"TagFilters": [%s]}
                """.formatted(tagFilter("fd", marker)))
            .body("ResourceTagMappingList", empty());
    }

    @Test
    void tagResourcesRejectsArnFromAnotherRegion() {
        String marker = unique();
        String westArn = "arn:aws:sqs:eu-west-1:000000000000:discovery-" + marker;

        tagging("TagResources", """
                {"ResourceARNList": ["%s"], "Tags": {"fd": "%s"}}
                """.formatted(westArn, marker))
            .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"))
            .body("message", equalTo("Region in the ARN " + westArn
                    + " does not match with the region in which TagResources API is invoked"));
    }

    @Test
    void taggingApiWritesReachTheMskCluster() {
        String marker = unique();
        String clusterArn = given()
            .contentType("application/json")
            .body("""
                {"clusterName": "discovery-%s", "kafkaVersion": "3.6.0", "tags": {"fd": "%s"}}
                """.formatted(marker, marker))
        .when()
            .post("/v1/clusters")
        .then()
            .statusCode(200)
            .extract().path("clusterArn");

        tagging("TagResources", """
                {"ResourceARNList": ["%s"], "Tags": {"added": "yes"}}
                """.formatted(clusterArn))
            .then()
            .statusCode(200)
            .body("FailedResourcesMap", anEmptyMap());
        mskTags(clusterArn)
            .body("tags.fd", equalTo(marker))
            .body("tags.added", equalTo("yes"));

        tagging("UntagResources", """
                {"ResourceARNList": ["%s"], "TagKeys": ["added"]}
                """.formatted(clusterArn))
            .then()
            .statusCode(200)
            .body("FailedResourcesMap", anEmptyMap());
        mskTags(clusterArn)
            .body("tags.fd", equalTo(marker))
            .body("tags", not(hasKey("added")));

        given().when().delete("/v1/clusters/{arn}", clusterArn).then().statusCode(200);
    }

    @Test
    void taggingApiWritesReachTheSqsQueue() {
        String name = "discovery-" + unique();
        String marker = unique();
        String value = unique();
        String arn = ARN_PREFIX.formatted("sqs") + name;
        String queueUrl = sqs("CreateQueue", """
                {"QueueName": "%s", "tags": {"fd": "%s"}}
                """.formatted(name, marker))
            .then()
            .statusCode(200)
            .extract().path("QueueUrl");

        tagAddedThroughTaggingApi(arn, value);
        sqsQueueTags(queueUrl)
            .body("Tags.fd", equalTo(marker))
            .body("Tags.added", equalTo(value));
        addedTagIsListedOnce(arn, value);

        untagAddedThroughTaggingApi(arn);
        sqsQueueTags(queueUrl)
            .body("Tags.fd", equalTo(marker))
            .body("Tags", not(hasKey("added")));
        addedTagIsNotListed(arn);

        sqs("DeleteQueue", """
                {"QueueUrl": "%s"}
                """.formatted(queueUrl)).then().statusCode(200);
    }

    @Test
    void taggingApiWritesReachTheLambdaFunction() {
        String name = "discovery-" + unique();
        String marker = unique();
        String value = unique();
        String arn = given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "Runtime": "nodejs20.x",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "index.handler",
                    "Tags": {"fd": "%s"}
                }
                """.formatted(name, marker))
        .when()
            .post("/2015-03-31/functions")
        .then()
            .statusCode(201)
            .extract().path("FunctionArn");

        tagAddedThroughTaggingApi(arn, value);
        lambdaTags(arn)
            .body("Tags.fd", equalTo(marker))
            .body("Tags.added", equalTo(value));
        addedTagIsListedOnce(arn, value);

        untagAddedThroughTaggingApi(arn);
        lambdaTags(arn)
            .body("Tags.fd", equalTo(marker))
            .body("Tags", not(hasKey("added")));
        addedTagIsNotListed(arn);

        given().when().delete("/2015-03-31/functions/" + name).then().statusCode(204);
    }

    @Test
    void taggingApiWritesReachTheLogGroup() {
        String name = "/probe/" + unique();
        String marker = unique();
        String value = unique();
        String arn = ARN_PREFIX.formatted("logs") + "log-group:" + name;
        logs("CreateLogGroup", """
                {"logGroupName": "%s", "tags": {"fd": "%s"}}
                """.formatted(name, marker)).then().statusCode(200);

        tagAddedThroughTaggingApi(arn, value);
        logs("ListTagsLogGroup", """
                {"logGroupName": "%s"}
                """.formatted(name))
            .then()
            .statusCode(200)
            .body("tags.fd", equalTo(marker))
            .body("tags.added", equalTo(value));
        logGroupTagsByArn(arn)
            .body("tags.fd", equalTo(marker))
            .body("tags.added", equalTo(value));
        addedTagIsListedOnce(arn, value);

        untagAddedThroughTaggingApi(arn);
        logGroupTagsByArn(arn)
            .body("tags.fd", equalTo(marker))
            .body("tags", not(hasKey("added")));
        addedTagIsNotListed(arn);

        logs("DeleteLogGroup", """
                {"logGroupName": "%s"}
                """.formatted(name)).then().statusCode(200);
    }

    @Test
    void taggingApiRejectsWildcardLogGroupArn() {
        String name = "/probe/" + unique();
        String marker = unique();
        String wildcardArn = ARN_PREFIX.formatted("logs") + "log-group:" + name + ":*";
        logs("CreateLogGroup", """
                {"logGroupName": "%s", "tags": {"fd": "%s"}}
                """.formatted(name, marker)).then().statusCode(200);

        wildcardArnIsRejected(tagging("TagResources", """
                {"ResourceARNList": ["%s"], "Tags": {"added": "yes"}}
                """.formatted(wildcardArn)), wildcardArn);
        logGroupTagsAreOnlyTheMarker(name, marker);

        wildcardArnIsRejected(tagging("UntagResources", """
                {"ResourceARNList": ["%s"], "TagKeys": ["fd"]}
                """.formatted(wildcardArn)), wildcardArn);
        logGroupTagsAreOnlyTheMarker(name, marker);

        String missingArn = ARN_PREFIX.formatted("logs") + "log-group:/probe/missing-" + marker + ":*";
        wildcardArnIsRejected(tagging("TagResources", """
                {"ResourceARNList": ["%s"], "Tags": {"missing": "%s"}}
                """.formatted(missingArn, marker)), missingArn);
        getResources("""
                {"TagFilters": [%s]}
                """.formatted(tagFilter("missing", marker)))
            .body("ResourceTagMappingList", empty());

        logs("DeleteLogGroup", """
                {"logGroupName": "%s"}
                """.formatted(name)).then().statusCode(200);
    }

    @Test
    void sqsQueueIsNotServedOnTheSharedTagsPaths() {
        String name = "discovery-" + unique();
        String arn = ARN_PREFIX.formatted("sqs") + name;
        String unhandledArn = ARN_PREFIX.formatted("sns") + name;
        String queueUrl = sqs("CreateQueue", """
                {"QueueName": "%s", "tags": {"fd": "%s"}}
                """.formatted(name, unique()))
            .then()
            .statusCode(200)
            .extract().path("QueueUrl");

        for (String path : List.of("/tags/{arn}", "/v1/tags/{arn}")) {
            tagsPathRejectsArn(path, unhandledArn);
            tagsPathRejectsArn(path, arn);
        }

        sqs("DeleteQueue", """
                {"QueueUrl": "%s"}
                """.formatted(queueUrl)).then().statusCode(200);
    }

    @Test
    void usagePlanIsDiscoveredByType() {
        String marker = unique();
        String planId = given()
            .contentType("application/json")
            .body("""
                {"name": "discovery-%s", "tags": {"fd": "%s"}}
                """.formatted(marker, marker))
        .when()
            .post("/usageplans")
        .then()
            .statusCode(201)
            .extract().path("id");

        getResources(markerFilter("apigateway:usageplans", marker))
            .body("ResourceTagMappingList.ResourceARN", contains(APIGATEWAY_ARN_PREFIX + "/usageplans/" + planId));

        given().when().delete("/usageplans/" + planId).then().statusCode(202);
        getResources(markerFilter("apigateway:usageplans", marker))
            .body("ResourceTagMappingList", empty());
    }

    @Test
    void taggingApiWritesReachTheUsagePlan() {
        String marker = unique();
        String planId = given()
            .contentType("application/json")
            .body("""
                {"name": "discovery-%s", "tags": {"fd": "%s"}}
                """.formatted(marker, marker))
        .when()
            .post("/usageplans")
        .then()
            .statusCode(201)
            .extract().path("id");
        String arn = APIGATEWAY_ARN_PREFIX + "/usageplans/" + planId;

        tagging("TagResources", """
                {"ResourceARNList": ["%s"], "Tags": {"added": "yes"}}
                """.formatted(arn))
            .then()
            .statusCode(200)
            .body("FailedResourcesMap", anEmptyMap());

        given()
        .when()
            .get("/usageplans/" + planId)
        .then()
            .statusCode(200)
            .body("tags.fd", equalTo(marker))
            .body("tags.added", equalTo("yes"));
        given()
            .pathParam("arn", arn)
        .when()
            .get("/tags/{arn}")
        .then()
            .statusCode(200)
            .body("tags.fd", equalTo(marker))
            .body("tags.added", equalTo("yes"));

        tagging("UntagResources", """
                {"ResourceARNList": ["%s"], "TagKeys": ["added"]}
                """.formatted(arn))
            .then()
            .statusCode(200)
            .body("FailedResourcesMap", anEmptyMap());
        given()
        .when()
            .get("/usageplans/" + planId)
        .then()
            .statusCode(200)
            .body("tags.fd", equalTo(marker))
            .body("tags", not(hasKey("added")));

        given().when().delete("/usageplans/" + planId).then().statusCode(202);
    }

    @Test
    void domainNameIsDiscoveredByType() {
        String marker = unique();
        String domainName = "discovery-" + marker + ".example.com";
        given()
            .contentType("application/json")
            .body("""
                {"domainName": "%s", "regionalCertificateArn": "%s",
                 "endpointConfiguration": {"types": ["REGIONAL"]}, "tags": {"fd": "%s"}}
                """.formatted(domainName, ARN_PREFIX.formatted("acm") + "certificate/" + UUID.randomUUID(), marker))
        .when()
            .post("/domainnames")
        .then()
            .statusCode(201);

        getResources(markerFilter("apigateway:domainnames", marker))
            .body("ResourceTagMappingList.ResourceARN", contains(APIGATEWAY_ARN_PREFIX + "/domainnames/" + domainName));

        given().when().delete("/domainnames/" + domainName).then().statusCode(202);
        getResources(markerFilter("apigateway:domainnames", marker))
            .body("ResourceTagMappingList", empty());
    }

    private static String createApiKey(String marker) {
        return given()
            .contentType("application/json")
            .body("""
                {"name": "discovery-%s", "tags": {"rsid": "%s", "cid": "c1", "baah": "x"}}
                """.formatted(marker, marker))
        .when()
            .post("/apikeys")
        .then()
            .statusCode(201)
            .extract().path("id");
    }

    private static void putNestedTagIsRejected(String arn) {
        given()
            .pathParam("arn", arn)
            .contentType("application/json")
            .body("""
                {"tags": {"nested": "yes"}}
                """)
        .when()
            .put("/tags/{arn}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Invalid ARN specified in the request"));
    }

    private static void restApiTagsAreUnchanged(String apiId, String marker) {
        given()
            .pathParam("arn", APIGATEWAY_ARN_PREFIX + "/restapis/" + apiId)
        .when()
            .get("/tags/{arn}")
        .then()
            .statusCode(200)
            .body("tags.fd", equalTo(marker))
            .body("tags", not(hasKey("nested")));
    }

    private static ValidatableResponse mskTags(String arn) {
        return given()
            .pathParam("arn", arn)
        .when()
            .get("/v1/tags/{arn}")
        .then()
            .statusCode(200);
    }

    private static void deleteApiKey(String id) {
        given().when().delete("/apikeys/" + id).then().statusCode(202);
    }

    private static void tagAddedThroughTaggingApi(String arn, String value) {
        tagging("TagResources", """
                {"ResourceARNList": ["%s"], "Tags": {"added": "%s"}}
                """.formatted(arn, value))
            .then()
            .statusCode(200)
            .body("FailedResourcesMap", anEmptyMap());
    }

    private static void untagAddedThroughTaggingApi(String arn) {
        tagging("UntagResources", """
                {"ResourceARNList": ["%s"], "TagKeys": ["added"]}
                """.formatted(arn))
            .then()
            .statusCode(200)
            .body("FailedResourcesMap", anEmptyMap());
    }

    private static void addedTagIsListedOnce(String arn, String value) {
        getResources(arnFilter(arn))
            .body("ResourceTagMappingList.ResourceARN", contains(arn))
            .body("ResourceTagMappingList[0].Tags.findAll { it.Key == 'added' }.Value", contains(value));
    }

    private static void addedTagIsNotListed(String arn) {
        getResources(arnFilter(arn))
            .body("ResourceTagMappingList.ResourceARN", contains(arn))
            .body("ResourceTagMappingList[0].Tags.Key", not(hasItem("added")));
    }

    private static String arnFilter(String arn) {
        return """
                {"ResourceARNList": ["%s"]}
                """.formatted(arn);
    }

    private static ValidatableResponse sqsQueueTags(String queueUrl) {
        return sqs("ListQueueTags", """
                {"QueueUrl": "%s"}
                """.formatted(queueUrl))
            .then()
            .statusCode(200);
    }

    private static ValidatableResponse lambdaTags(String arn) {
        return given()
        .when()
            .get("/2017-03-31/tags/" + arn)
        .then()
            .statusCode(200);
    }

    private static ValidatableResponse logGroupTagsByArn(String arn) {
        return logs("ListTagsForResource", """
                {"resourceArn": "%s"}
                """.formatted(arn))
            .then()
            .statusCode(200);
    }

    private static void wildcardArnIsRejected(Response response, String wildcardArn) {
        response.then()
            .statusCode(200)
            .body("FailedResourcesMap.size()", equalTo(1))
            .body("FailedResourcesMap['%s'].StatusCode".formatted(wildcardArn), equalTo(400))
            .body("FailedResourcesMap['%s'].ErrorCode".formatted(wildcardArn), equalTo("ValidationException"))
            .body("FailedResourcesMap['%s'].ErrorMessage".formatted(wildcardArn), equalTo("Invalid resourceArn"));
    }

    private static void logGroupTagsAreOnlyTheMarker(String name, String marker) {
        logs("ListTagsLogGroup", """
                {"logGroupName": "%s"}
                """.formatted(name))
            .then()
            .statusCode(200)
            .body("tags.size()", equalTo(1))
            .body("tags.fd", equalTo(marker));
    }

    private static void tagsPathRejectsArn(String path, String arn) {
        given()
            .pathParam("arn", arn)
        .when()
            .get(path)
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo("Invalid resource ARN: " + arn));
    }

    private static String createRestApi(String marker) {
        return given()
            .contentType("application/json")
            .body("""
                {"name": "discovery-%s", "tags": {"fd": "%s"}}
                """.formatted(marker, marker))
        .when()
            .post("/restapis")
        .then()
            .statusCode(201)
            .extract().path("id");
    }

    private static String rsidFilter(String resourceType, String marker) {
        return """
                {"ResourceTypeFilters": ["%s"], "TagFilters": [%s]}
                """.formatted(resourceType, tagFilter("rsid", marker));
    }

    private static List<String> allTagKeys() {
        List<String> keys = new ArrayList<>();
        String token = "";
        do {
            Response response = tagging("GetTagKeys", """
                    {"PaginationToken": "%s"}
                    """.formatted(token));
            response.then().statusCode(200);
            keys.addAll(response.jsonPath().getList("TagKeys", String.class));
            token = response.jsonPath().getString("PaginationToken");
        } while (token != null && !token.isEmpty());
        return keys;
    }

    private static String markerFilter(String resourceType, String marker) {
        return """
                {"ResourceTypeFilters": ["%s"], "TagFilters": [%s]}
                """.formatted(resourceType, tagFilter("fd", marker));
    }

    private static String markerAndAddedFilter(String resourceType, String marker) {
        return """
                {"ResourceTypeFilters": ["%s"], "TagFilters": [%s, {"Key": "added"}]}
                """.formatted(resourceType, tagFilter("fd", marker));
    }

    private static String tagFilter(String key, String value) {
        return """
                {"Key": "%s", "Values": ["%s"]}""".formatted(key, value);
    }

    private static ValidatableResponse getResources(String body) {
        return tagging("GetResources", body).then().statusCode(200);
    }

    private static Response tagging(String action, String body) {
        return given()
            .header("X-Amz-Target", TAGGING_TARGET + action)
            .contentType(JSON_1_1)
            .body(body)
        .when()
            .post("/");
    }

    private static Response sqs(String action, String body) {
        return given()
            .header("X-Amz-Target", "AmazonSQS." + action)
            .contentType(JSON_1_0)
            .body(body)
        .when()
            .post("/");
    }

    private static Response logs(String action, String body) {
        return given()
            .header("X-Amz-Target", "Logs_20140328." + action)
            .contentType(JSON_1_1)
            .body(body)
        .when()
            .post("/");
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
