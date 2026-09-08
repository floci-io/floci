package io.github.hectorvent.floci.services.elasticache;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Cache parameter groups over the Query protocol.
 *
 * <p>Behaviour follows a live account: the create response and its ARN, the error each rejection
 * carries, and the {@code default.*} groups AWS publishes. What floci does not carry is AWS's
 * per-family catalogue of parameter names, so it stores what a caller sets and reports it as
 * {@code user} — rejecting names absent from a partial catalogue would refuse configurations AWS
 * accepts.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElastiCacheParameterGroupIntegrationTest {

    private static final String GROUP = "int-test-pg";
    // The router picks the service out of the credential scope, so every request carries it.
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260412/us-east-1/elasticache/aws4_request";

    private static io.restassured.specification.RequestSpecification query(String action) {
        return given()
                .header("Authorization", AUTH_HEADER)
                .formParam("Action", action)
                .formParam("Version", "2015-02-02");
    }

    @Test
    @Order(1)
    void createReturnsTheGroupAndItsArn() {
        query("CreateCacheParameterGroup")
                .formParam("CacheParameterGroupName", GROUP)
                .formParam("CacheParameterGroupFamily", "redis7")
                .formParam("Description", "integration test")
                .formParam("Tags.Tag.1.Key", "env")
                .formParam("Tags.Tag.1.Value", "prod")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<CacheParameterGroupName>" + GROUP + "</CacheParameterGroupName>"))
            .body(containsString("<CacheParameterGroupFamily>redis7</CacheParameterGroupFamily>"))
            .body(containsString("<IsGlobal>false</IsGlobal>"))
            .body(containsString(":parametergroup:" + GROUP + "</ARN>"));
    }

    @Test
    @Order(2)
    void duplicateIsRejected() {
        query("CreateCacheParameterGroup")
                .formParam("CacheParameterGroupName", GROUP)
                .formParam("CacheParameterGroupFamily", "redis7")
                .formParam("Description", "again")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("CacheParameterGroupAlreadyExists"))
            // The Query protocol answers in XML; a JSON error here would be unparseable to an SDK.
            .body(containsString("<Error>"));
    }

    @Test
    @Order(2)
    void anUnknownFamilyIsRejected() {
        query("CreateCacheParameterGroup")
                .formParam("CacheParameterGroupName", "bad-family-pg")
                .formParam("CacheParameterGroupFamily", "redis99")
                .formParam("Description", "x")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("not a valid parameter group family"));
    }

    @Test
    @Order(2)
    void anInvalidNameIsRejected() {
        for (String invalid : new String[]{"1leading-digit", "trailing-", "double--hyphen", "has.dot"}) {
            query("CreateCacheParameterGroup")
                    .formParam("CacheParameterGroupName", invalid)
                    .formParam("CacheParameterGroupFamily", "redis7")
                    .formParam("Description", "x")
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body(containsString("not a valid identifier"));
        }
    }

    @Test
    @Order(3)
    void modifyStoresTheParametersAndDescribeReportsThem() {
        query("ModifyCacheParameterGroup")
                .formParam("CacheParameterGroupName", GROUP)
                .formParam("ParameterNameValues.ParameterNameValue.1.ParameterName", "maxmemory-policy")
                .formParam("ParameterNameValues.ParameterNameValue.1.ParameterValue", "allkeys-lru")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<CacheParameterGroupName>" + GROUP + "</CacheParameterGroupName>"));

        query("DescribeCacheParameters")
                .formParam("CacheParameterGroupName", GROUP)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<ParameterName>maxmemory-policy</ParameterName>"))
            .body(containsString("<ParameterValue>allkeys-lru</ParameterValue>"))
            .body(containsString("<Source>user</Source>"));
    }

    @Test
    @Order(3)
    void describeParametersHonoursTheSourceFilter() {
        // floci holds no system defaults, so a request for them reports none rather than
        // returning the user's parameters under the wrong source.
        query("DescribeCacheParameters")
                .formParam("CacheParameterGroupName", GROUP)
                .formParam("Source", "system")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<ParameterName>")));
    }

    @Test
    @Order(3)
    void tagsSurviveTheCreateAndAreListed() {
        query("ListTagsForResource")
                .formParam("ResourceName", "arn:aws:elasticache:us-east-1:000000000000:parametergroup:" + GROUP)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<Key>env</Key>"))
            .body(containsString("<Value>prod</Value>"));
    }

    @Test
    @Order(3)
    void tagsAreAnsweredForTheArnThatWasAskedAbout() {
        // Reading the trailing name alone would answer with this caller's group for an ARN naming
        // another account or region. Each rejection is the one AWS gives.
        query("ListTagsForResource")
                .formParam("ResourceName", "arn:aws:elasticache:eu-west-1:000000000000:parametergroup:" + GROUP)
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("Please check the region"));

        query("ListTagsForResource")
                .formParam("ResourceName", "arn:aws:elasticache:us-east-1:111122223333:parametergroup:" + GROUP)
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("does not belong to the caller"));

        query("ListTagsForResource")
                .formParam("ResourceName", "not-an-arn")
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("does not have 7 components"));

        // An ARN in another service's namespace names another resource, however familiar the
        // trailing name looks.
        query("ListTagsForResource")
                .formParam("ResourceName", "arn:aws:rds:us-east-1:000000000000:parametergroup:" + GROUP)
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("service field is wrong"));

        query("ListTagsForResource")
                .formParam("ResourceName", "arn:aws-cn:elasticache:us-east-1:000000000000:parametergroup:" + GROUP)
        .when().post("/")
        .then()
            .statusCode(400)
            .body(containsString("partition field is wrong"));

        query("ListTagsForResource")
                .formParam("ResourceName", "arn:aws:elasticache:us-east-1:000000000000:parametergroup:no-such-pg")
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("no-such-pg is not present"));
    }

    @Test
    @Order(4)
    void theDefaultGroupsArePublished() {
        query("DescribeCacheParameterGroups")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<CacheParameterGroupName>default.redis7</CacheParameterGroupName>"))
            .body(containsString("<CacheParameterGroupName>default.redis7.cluster.on</CacheParameterGroupName>"))
            .body(containsString("<CacheParameterGroupName>default.valkey8</CacheParameterGroupName>"))
            .body(containsString("<CacheParameterGroupName>" + GROUP + "</CacheParameterGroupName>"));
    }

    @Test
    @Order(4)
    void aDefaultGroupCannotBeModifiedOrDeleted() {
        query("ModifyCacheParameterGroup")
                .formParam("CacheParameterGroupName", "default.redis7")
                .formParam("ParameterNameValues.ParameterNameValue.1.ParameterName", "maxmemory-policy")
                .formParam("ParameterNameValues.ParameterNameValue.1.ParameterValue", "noeviction")
        .when()
            .post("/")
        .then()
            .statusCode(400);

        // AWS rejects the delete on the identifier rule, since a name cannot contain dots.
        query("DeleteCacheParameterGroup")
                .formParam("CacheParameterGroupName", "default.redis7")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("not a valid identifier"));
    }

    @Test
    @Order(4)
    void eachActionWordsItsNotFoundTheWayAwsWordsIt() {
        // Three actions, three different sentences on a live account — including a delete that
        // omits the space after the type name. They are pinned here so the odd one cannot be
        // tidied into the others by someone reading it as a typo.
        query("DescribeCacheParameterGroups")
                .formParam("CacheParameterGroupName", "absent-pg")
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("CacheParameterGroup absent-pg not found."));

        query("ModifyCacheParameterGroup")
                .formParam("CacheParameterGroupName", "absent-pg")
                .formParam("ParameterNameValues.ParameterNameValue.1.ParameterName", "maxmemory-policy")
                .formParam("ParameterNameValues.ParameterNameValue.1.ParameterValue", "noeviction")
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("CacheParameterGroup not found: absent-pg"));

        query("DeleteCacheParameterGroup")
                .formParam("CacheParameterGroupName", "absent-pg")
        .when().post("/")
        .then()
            .statusCode(404)
            .body(containsString("CacheParameterGroupnot found: absent-pg"));
    }

    @Test
    @Order(4)
    void aReplicationGroupCannotReferenceAnUnknownParameterGroup() {
        // The lookup runs with the other validations, before any container is started, so the
        // refusal carries the parameter group's own not-found rather than a provisioning failure.
        query("CreateReplicationGroup")
                .formParam("ReplicationGroupId", "pg-absent-rg")
                .formParam("ReplicationGroupDescription", "references a missing group")
                .formParam("CacheParameterGroupName", "absent-pg")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body(containsString("<Code>CacheParameterGroupNotFound</Code>"))
            .body(containsString("CacheParameterGroup absent-pg not found."));

        query("DescribeReplicationGroups")
                .formParam("ReplicationGroupId", "pg-absent-rg")
        .when()
            .post("/")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(4)
    void aParameterGroupInUseByAReplicationGroupCannotBeDeleted() {
        // The replication group is a real one behind a container, so this case needs Docker
        // where the rest of the class does not.
        Assumptions.assumeTrue(ElastiCacheIntegrationTest.isDockerAvailable(),
                "Docker daemon must be available to create a replication group");
        String replicationGroupId = "pg-in-use-rg";
        query("CreateReplicationGroup")
                .formParam("ReplicationGroupId", replicationGroupId)
                .formParam("ReplicationGroupDescription", "holds the parameter group in use")
                .formParam("CacheParameterGroupName", GROUP)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        try {
            query("DeleteCacheParameterGroup")
                    .formParam("CacheParameterGroupName", GROUP)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body(containsString("<Code>InvalidCacheParameterGroupState</Code>"))
                .body(containsString("One or more cache clusters are still members of this parameter group "
                        + GROUP + ", so the group cannot be deleted."));

            query("DescribeCacheParameterGroups")
                    .formParam("CacheParameterGroupName", GROUP)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<CacheParameterGroupName>" + GROUP + "</CacheParameterGroupName>"));
        } finally {
            query("DeleteReplicationGroup")
                    .formParam("ReplicationGroupId", replicationGroupId)
            .when()
                .post("/")
            .then()
                .statusCode(200);
        }
    }

    @Test
    @Order(5)
    void deleteRemovesItAndTheSecondDeleteReportsItMissing() {
        query("DeleteCacheParameterGroup")
                .formParam("CacheParameterGroupName", GROUP)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        query("DescribeCacheParameterGroups")
                .formParam("CacheParameterGroupName", GROUP)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body(containsString("CacheParameterGroupNotFound"));

        query("DeleteCacheParameterGroup")
                .formParam("CacheParameterGroupName", GROUP)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body(containsString("CacheParameterGroupNotFound"));
    }
}
