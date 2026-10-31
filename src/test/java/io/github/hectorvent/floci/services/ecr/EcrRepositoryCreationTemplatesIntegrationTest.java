package io.github.hectorvent.floci.services.ecr;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Repository creation templates over the JSON protocol. Each test uses its own Region so they do
 * not share state.
 */
@QuarkusTest
class EcrRepositoryCreationTemplatesIntegrationTest {

    private static final String CT = "application/x-amz-json-1.1";
    private static final String PREFIX = "AmazonEC2ContainerRegistry_V20150921.";
    private static final String POLICY = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"Pull\","
            + "\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":[\"ecr:BatchGetImage\"]}]}";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aTemplateIsCreatedDescribedUpdatedAndDeleted() {
        String region = "eu-north-1";
        call("CreateRepositoryCreationTemplate", "{\"prefix\":\"prod\",\"description\":\"production\","
                + "\"appliedFor\":[\"PULL_THROUGH_CACHE\",\"REPLICATION\"],"
                + "\"resourceTags\":[{\"Key\":\"team\",\"Value\":\"platform\"}],"
                + "\"imageTagMutability\":\"IMMUTABLE_WITH_EXCLUSION\","
                + "\"imageTagMutabilityExclusionFilters\":[{\"filterType\":\"WILDCARD\",\"filter\":\"latest*\"}],"
                + "\"repositoryPolicy\":" + quote(POLICY) + "}", region).statusCode(200)
                .body("registryId", equalTo("000000000000"))
                .body("repositoryCreationTemplate.prefix", equalTo("prod"))
                .body("repositoryCreationTemplate.encryptionConfiguration.encryptionType", equalTo("AES256"))
                .body("repositoryCreationTemplate.resourceTags[0].Key", equalTo("team"))
                .body("repositoryCreationTemplate.imageTagMutabilityExclusionFilters[0].filter", equalTo("latest*"))
                .body("repositoryCreationTemplate.repositoryPolicy", equalTo(POLICY))
                .body("repositoryCreationTemplate.createdAt", notNullValue());
        call("CreateRepositoryCreationTemplate", "{\"prefix\":\"prod\",\"appliedFor\":[\"REPLICATION\"]}", region)
                .statusCode(400).body("__type", equalTo("TemplateAlreadyExistsException"));

        call("DescribeRepositoryCreationTemplates", "{\"prefixes\":[\"prod\",\"absent\"]}", region).statusCode(200)
                .body("registryId", equalTo("000000000000"))
                .body("repositoryCreationTemplates", hasSize(1))
                .body("repositoryCreationTemplates[0].description", equalTo("production"))
                .body("repositoryCreationTemplates[0].appliedFor", contains("PULL_THROUGH_CACHE", "REPLICATION"));

        call("UpdateRepositoryCreationTemplate", "{\"prefix\":\"prod\",\"description\":\"\","
                + "\"encryptionConfiguration\":{\"encryptionType\":\"KMS\"},\"appliedFor\":[\"CREATE_ON_PUSH\"]}",
                region).statusCode(200)
                .body("repositoryCreationTemplate.description", nullValue())
                .body("repositoryCreationTemplate.encryptionConfiguration.encryptionType", equalTo("KMS"))
                .body("repositoryCreationTemplate.appliedFor", contains("CREATE_ON_PUSH"))
                .body("repositoryCreationTemplate.repositoryPolicy", equalTo(POLICY));

        call("DeleteRepositoryCreationTemplate", "{\"prefix\":\"prod\"}", region).statusCode(200)
                .body("repositoryCreationTemplate.prefix", equalTo("prod"));
        call("DeleteRepositoryCreationTemplate", "{\"prefix\":\"prod\"}", region).statusCode(400)
                .body("__type", equalTo("TemplateNotFoundException"));
        call("UpdateRepositoryCreationTemplate", "{\"prefix\":\"prod\",\"description\":\"x\"}", region)
                .statusCode(400).body("__type", equalTo("TemplateNotFoundException"));
    }

    @Test
    void templatesPageInPrefixOrder() {
        String region = "eu-south-1";
        for (String prefix : new String[] {"ROOT", "apps/web", "apps"}) {
            call("CreateRepositoryCreationTemplate", "{\"prefix\":\"" + prefix
                    + "\",\"appliedFor\":[\"REPLICATION\"]}", region).statusCode(200);
        }
        String token = call("DescribeRepositoryCreationTemplates", "{\"maxResults\":2}", region).statusCode(200)
                .body("repositoryCreationTemplates.prefix", contains("ROOT", "apps"))
                .extract().path("nextToken");
        call("DescribeRepositoryCreationTemplates", "{\"maxResults\":2,\"nextToken\":\"" + token + "\"}", region)
                .statusCode(200)
                .body("repositoryCreationTemplates.prefix", contains("apps/web"))
                .body("nextToken", nullValue());
    }

    @Test
    void badInputIsAnInvalidParameter() {
        String region = "ap-south-2";
        for (String body : new String[] {
                "{\"prefix\":\"prod\"}",
                "{\"prefix\":\"Prod\",\"appliedFor\":[\"REPLICATION\"]}",
                "{\"prefix\":\"prod\",\"appliedFor\":[\"SOMETIMES\"]}",
                "{\"prefix\":\"prod\",\"appliedFor\":[\"REPLICATION\"],\"imageTagMutability\":\"FROZEN\"}",
                "{\"prefix\":\"prod\",\"appliedFor\":[\"REPLICATION\"],\"lifecyclePolicy\":\"not json\"}",
                "{\"prefix\":\"prod\",\"appliedFor\":[\"REPLICATION\"],"
                        + "\"encryptionConfiguration\":{\"encryptionType\":\"KMS\",\"kmsKey\":\"alias/x\"}}"}) {
            call("CreateRepositoryCreationTemplate", body, region).statusCode(400)
                    .body("__type", equalTo("InvalidParameterException"));
        }
        // The handler checks the request against the vendored model before the service sees it.
        call("CreateRepositoryCreationTemplate", "{\"prefix\":\"prod\"}", region).statusCode(400)
                .body("message", equalTo("Invalid parameter at 'appliedFor' failed to satisfy constraint: "
                        + "'Member must not be null'"));
        call("DescribeRepositoryCreationTemplates", "{\"maxResults\":0}", region).statusCode(400)
                .body("__type", equalTo("InvalidParameterException"));
        call("DescribeRepositoryCreationTemplates", "{}", region).statusCode(200)
                .body("repositoryCreationTemplates", hasSize(0));
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static ValidatableResponse call(String action, String body, String region) {
        return given()
                .header("X-Amz-Target", PREFIX + action)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=000000000000/20261016/" + region
                        + "/ecr/aws4_request, SignedHeaders=host, Signature=abc")
                .contentType(CT)
                .body(body)
            .when()
                .post("/")
            .then();
    }
}
