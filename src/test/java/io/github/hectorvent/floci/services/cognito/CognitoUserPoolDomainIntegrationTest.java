package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.UUID;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers CreateUserPoolDomain/DescribeUserPoolDomain/UpdateUserPoolDomain/DeleteUserPoolDomain (lex00/floci#63)
 * for both an Amazon Cognito prefix domain and a custom domain fronted by an ACM certificate, including
 * the certificate's InUseBy bookkeeping in ACM.
 *
 * <p>The last test posts {@code /_floci/state/reset}, which clears every service. The class runs on
 * the shared default application all the same, as other classes that post the reset do: test
 * classes run one at a time, so the reset only clears what this class and the ones before it
 * created, and each service restores its bootstrap state as it clears.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CognitoUserPoolDomainIntegrationTest {

    private static String poolId;
    private static String prefixDomain;
    private static String customDomain;
    private static String CERTIFICATE_ARN;
    private static String RENEWED_CERTIFICATE_ARN;
    private static final String AWS_CERTIFICATE_MESSAGE = "The specified SSL certificate doesn't exist, "
            + "isn't in us-east-1 region, isn't valid, or doesn't include a valid certificate chain.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createPool() throws Exception {
        JsonNode poolResponse = cognitoJson("CreateUserPool", """
                {
                  "PoolName": "DomainTestPool"
                }
                """);
        poolId = poolResponse.path("UserPool").path("Id").asText();
        assertNull(poolResponse.path("UserPool").get("Domain"));
        assertNull(poolResponse.path("UserPool").get("CustomDomain"));
        prefixDomain = "floci-test-" + UUID.randomUUID().toString().substring(0, 8);
        customDomain = "auth-" + UUID.randomUUID().toString().substring(0, 8) + ".example.com";
        CERTIFICATE_ARN = requestCertificate(customDomain);
        RENEWED_CERTIFICATE_ARN = requestCertificate(customDomain);
    }

    private static String requestCertificate(String domainName) throws Exception {
        return RestAssuredJsonUtils.awsActionJson("CertificateManager", "RequestCertificate", """
                {
                  "DomainName": "%s",
                  "ValidationMethod": "DNS"
                }
                """.formatted(domainName)).path("CertificateArn").asText();
    }

    private static io.restassured.response.Response acm(String action, String certificateArn) {
        return RestAssuredJsonUtils.awsAction("CertificateManager", action, """
                {
                  "CertificateArn": "%s"
                }
                """.formatted(certificateArn));
    }

    @Test
    @Order(2)
    void createPrefixDomainSucceeds() throws Exception {
        JsonNode response = cognitoJson("CreateUserPoolDomain", """
                {
                  "Domain": "%s",
                  "UserPoolId": "%s"
                }
                """.formatted(prefixDomain, poolId));

        // AWS returns CloudFrontDomain=null for a prefix domain; floci should not
        // fabricate one, so the field is simply absent from the response.
        assertNull(response.get("CloudFrontDomain"));
    }

    @Test
    @Order(3)
    void describePrefixDomainReturnsMatchingFields() throws Exception {
        JsonNode response = cognitoJson("DescribeUserPoolDomain", """
                {
                  "Domain": "%s"
                }
                """.formatted(prefixDomain));

        JsonNode description = response.path("DomainDescription");
        assertEquals(prefixDomain, description.path("Domain").asText());
        assertEquals(poolId, description.path("UserPoolId").asText());
        assertEquals("ACTIVE", description.path("Status").asText());
        assertNull(description.get("CustomDomainConfig"));

        JsonNode userPool = cognitoJson("DescribeUserPool", """
                {
                  "UserPoolId": "%s"
                }
                """.formatted(poolId)).path("UserPool");
        assertEquals(prefixDomain, userPool.path("Domain").asText());
        assertNull(userPool.get("CustomDomain"));
    }

    @Test
    @Order(4)
    void deletePrefixDomainSucceeds() throws Exception {
        cognitoAction("DeleteUserPoolDomain", """
                {
                  "Domain": "%s",
                  "UserPoolId": "%s"
                }
                """.formatted(prefixDomain, poolId))
                .then()
                .statusCode(200);

        JsonNode userPool = cognitoJson("DescribeUserPool", """
                {
                  "UserPoolId": "%s"
                }
                """.formatted(poolId)).path("UserPool");
        assertNull(userPool.get("Domain"));
    }

    @Test
    @Order(5)
    void describeDeletedPrefixDomainIsNotFound() {
        cognitoAction("DescribeUserPoolDomain", """
                {
                  "Domain": "%s"
                }
                """.formatted(prefixDomain))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(6)
    void createCustomDomainReturnsCloudFrontDomain() throws Exception {
        JsonNode response = cognitoJson("CreateUserPoolDomain", """
                {
                  "Domain": "%s",
                  "UserPoolId": "%s",
                  "CustomDomainConfig": {
                    "CertificateArn": "%s"
                  }
                }
                """.formatted(customDomain, poolId, CERTIFICATE_ARN));

        org.junit.jupiter.api.Assertions.assertTrue(response.path("CloudFrontDomain").isTextual());
        org.junit.jupiter.api.Assertions.assertFalse(response.path("CloudFrontDomain").asText().isBlank());
    }

    @Test
    @Order(7)
    void describeCustomDomainReturnsCertificateArn() throws Exception {
        JsonNode response = cognitoJson("DescribeUserPoolDomain", """
                {
                  "Domain": "%s"
                }
                """.formatted(customDomain));

        JsonNode description = response.path("DomainDescription");
        assertEquals(customDomain, description.path("Domain").asText());
        assertEquals(poolId, description.path("UserPoolId").asText());
        assertEquals(CERTIFICATE_ARN, description.path("CustomDomainConfig").path("CertificateArn").asText());
        assertEquals("ACTIVE", description.path("Status").asText());
        org.junit.jupiter.api.Assertions.assertNotNull(description.path("CloudFrontDistribution").asText(null));

        String secondPrefixDomain = prefixDomain + "-again";
        cognitoJson("CreateUserPoolDomain", """
                {
                  "Domain": "%s",
                  "UserPoolId": "%s"
                }
                """.formatted(secondPrefixDomain, poolId));
        try {
            JsonNode userPool = cognitoJson("DescribeUserPool", """
                    {
                      "UserPoolId": "%s"
                    }
                    """.formatted(poolId)).path("UserPool");
            assertEquals(secondPrefixDomain, userPool.path("Domain").asText());
            assertEquals(customDomain, userPool.path("CustomDomain").asText());

        } finally {
            cognitoAction("DeleteUserPoolDomain", """
                    {
                      "Domain": "%s",
                      "UserPoolId": "%s"
                    }
                    """.formatted(secondPrefixDomain, poolId))
                    .then()
                    .statusCode(200);
        }
    }

    @Test
    @Order(8)
    void certificateInUseByTheDomainCannotBeDeleted() {
        acm("DescribeCertificate", CERTIFICATE_ARN)
                .then()
                .statusCode(200)
                .body("Certificate.InUseBy.size()", equalTo(1))
                .body("Certificate.InUseBy[0]", startsWith("arn:aws:cloudfront::"));

        acm("DeleteCertificate", CERTIFICATE_ARN)
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceInUseException"));
    }

    @Test
    @Order(9)
    void updateCustomDomainReplacesTheCertificateAndKeepsTheCloudFrontDistribution() throws Exception {
        String cloudFront = cognitoJson("DescribeUserPoolDomain", """
                {
                  "Domain": "%s"
                }
                """.formatted(customDomain)).path("DomainDescription").path("CloudFrontDistribution").asText();

        JsonNode response = cognitoJson("UpdateUserPoolDomain", """
                {
                  "Domain": "%s",
                  "UserPoolId": "%s",
                  "CustomDomainConfig": {
                    "CertificateArn": "%s"
                  },
                  "ManagedLoginVersion": 2
                }
                """.formatted(customDomain, poolId, RENEWED_CERTIFICATE_ARN));

        // As on AWS, the distribution survives a certificate change, so a DNS alias stays valid.
        assertEquals(cloudFront, response.path("CloudFrontDomain").asText());
        assertEquals(2, response.path("ManagedLoginVersion").asInt());

        JsonNode description = cognitoJson("DescribeUserPoolDomain", """
                {
                  "Domain": "%s"
                }
                """.formatted(customDomain)).path("DomainDescription");
        assertEquals(RENEWED_CERTIFICATE_ARN, description.path("CustomDomainConfig").path("CertificateArn").asText());
        assertEquals(cloudFront, description.path("CloudFrontDistribution").asText());
        assertEquals(2, description.path("ManagedLoginVersion").asInt());
    }

    @Test
    @Order(10)
    void updateMovesTheRegistrationToTheRenewedCertificate() {
        acm("DescribeCertificate", CERTIFICATE_ARN)
                .then()
                .statusCode(200)
                .body("Certificate.InUseBy.size()", equalTo(0));
        acm("DescribeCertificate", RENEWED_CERTIFICATE_ARN)
                .then()
                .statusCode(200)
                .body("Certificate.InUseBy.size()", equalTo(1));
    }

    @Test
    @Order(11)
    void updateDomainOfAnotherPoolIsNotFound() {
        cognitoAction("UpdateUserPoolDomain", """
                {
                  "Domain": "%s",
                  "UserPoolId": "us-east-1_missing",
                  "CustomDomainConfig": {
                    "CertificateArn": "%s"
                  }
                }
                """.formatted(customDomain, CERTIFICATE_ARN))
                .then()
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(12)
    void createCustomDomainWithoutCertificateArnFails() {
        cognitoAction("CreateUserPoolDomain", """
                {
                  "Domain": "%s",
                  "UserPoolId": "%s",
                  "CustomDomainConfig": {}
                }
                """.formatted("bad-" + UUID.randomUUID().toString().substring(0, 8) + ".example.com", poolId))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    @Order(13)
    void createCustomDomainWithUnknownCertificateFails() {
        cognitoAction("CreateUserPoolDomain", """
                {
                  "Domain": "%s",
                  "UserPoolId": "%s",
                  "CustomDomainConfig": {
                    "CertificateArn": "arn:aws:acm:us-east-1:000000000000:certificate/%s"
                  }
                }
                """.formatted("unknown-" + UUID.randomUUID().toString().substring(0, 8) + ".example.com", poolId, UUID.randomUUID()))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo(AWS_CERTIFICATE_MESSAGE));
    }

    @Test
    @Order(14)
    void deleteCustomDomainThenDescribeIsNotFound() {
        cognitoAction("DeleteUserPoolDomain", """
                {
                  "Domain": "%s",
                  "UserPoolId": "%s"
                }
                """.formatted(customDomain, poolId))
                .then()
                .statusCode(200);

        cognitoAction("DescribeUserPoolDomain", """
                {
                  "Domain": "%s"
                }
                """.formatted(customDomain))
                .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(15)
    void deletingTheDomainReleasesTheCertificate() {
        acm("DescribeCertificate", RENEWED_CERTIFICATE_ARN)
                .then()
                .statusCode(200)
                .body("Certificate.InUseBy.size()", equalTo(0));

        acm("DeleteCertificate", RENEWED_CERTIFICATE_ARN)
                .then()
                .statusCode(200);
    }

    @Test
    @Order(16)
    void deleteUserPoolWithDomainIsRejected() throws Exception {
        // The DeleteUserPool API reference's own example documents this refusal verbatim.
        String blockingDomain = "floci-block-" + UUID.randomUUID().toString().substring(0, 8);
        cognitoJson("CreateUserPoolDomain", """
                {
                  "Domain": "%s",
                  "UserPoolId": "%s"
                }
                """.formatted(blockingDomain, poolId));

        cognitoAction("DeleteUserPool", """
                {
                  "UserPoolId": "%s"
                }
                """.formatted(poolId))
                .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"))
                .body("message", equalTo(
                        "User pool cannot be deleted. It has a domain configured that should be deleted first."));

        cognitoAction("DeleteUserPoolDomain", """
                {
                  "Domain": "%s",
                  "UserPoolId": "%s"
                }
                """.formatted(blockingDomain, poolId))
                .then()
                .statusCode(200);
    }

    @Test
    @Order(17)
    void deleteUserPoolSucceedsOnceDomainIsGone() {
        cognitoAction("DeleteUserPool", """
                {
                  "UserPoolId": "%s"
                }
                """.formatted(poolId))
                .then()
                .statusCode(200);
    }

    @Test
    @Order(18)
    void samePoolIdInDifferentAccountsReturnsOnlyThatAccountsDomain() throws Exception {
        String accountB = "111122223333";
        String sharedPoolId = "us-east-1_shared" + UUID.randomUUID().toString().substring(0, 8);
        String defaultDomain = "floci-default-" + UUID.randomUUID().toString().substring(0, 8);
        String accountBDomain = "floci-accountb-" + UUID.randomUUID().toString().substring(0, 8);
        cognitoJson("CreateUserPool", """
                {
                  "PoolName": "DefaultAccountSharedPool",
                  "UserPoolTags": { "floci:override-id": "%s" }
                }
                """.formatted(sharedPoolId));
        CognitoCustomDomainFixtures.awsJsonAs(accountB, CognitoCustomDomainFixtures.COGNITO, "CreateUserPool", """
                {
                  "PoolName": "AccountBSharedPool",
                  "UserPoolTags": { "floci:override-id": "%s" }
                }
                """.formatted(sharedPoolId));

        try {
            cognitoJson("CreateUserPoolDomain", """
                    { "Domain": "%s", "UserPoolId": "%s" }
                    """.formatted(defaultDomain, sharedPoolId));
            CognitoCustomDomainFixtures.awsJsonAs(accountB, CognitoCustomDomainFixtures.COGNITO,
                    "CreateUserPoolDomain", """
                            { "Domain": "%s", "UserPoolId": "%s" }
                            """.formatted(accountBDomain, sharedPoolId));

            JsonNode defaultPool = cognitoJson("DescribeUserPool", """
                    { "UserPoolId": "%s" }
                    """.formatted(sharedPoolId)).path("UserPool");
            JsonNode accountBPool = CognitoCustomDomainFixtures.awsJsonAs(accountB,
                    CognitoCustomDomainFixtures.COGNITO, "DescribeUserPool", """
                            { "UserPoolId": "%s" }
                            """.formatted(sharedPoolId)).path("UserPool");
            assertEquals(defaultDomain, defaultPool.path("Domain").asText());
            assertEquals(accountBDomain, accountBPool.path("Domain").asText());
        } finally {
            CognitoCustomDomainFixtures.awsActionAs(accountB, CognitoCustomDomainFixtures.COGNITO,
                    "DeleteUserPoolDomain", """
                            { "Domain": "%s", "UserPoolId": "%s" }
                            """.formatted(accountBDomain, sharedPoolId)).then().statusCode(200);
            cognitoAction("DeleteUserPoolDomain", """
                    { "Domain": "%s", "UserPoolId": "%s" }
                    """.formatted(defaultDomain, sharedPoolId)).then().statusCode(200);
            CognitoCustomDomainFixtures.awsActionAs(accountB, CognitoCustomDomainFixtures.COGNITO,
                    "DeleteUserPool", """
                            { "UserPoolId": "%s" }
                            """.formatted(sharedPoolId)).then().statusCode(200);
            cognitoAction("DeleteUserPool", """
                    { "UserPoolId": "%s" }
                    """.formatted(sharedPoolId)).then().statusCode(200);
        }
    }

    @Test
    @Order(19)
    void recreatedPoolDoesNotInheritDomainAfterStateReset() throws Exception {
        String pinnedPoolId = "us-east-1_reset" + UUID.randomUUID().toString().substring(0, 8);
        String resetDomain = "floci-reset-" + UUID.randomUUID().toString().substring(0, 8);

        try {
            cognitoJson("CreateUserPool", """
                    {
                      "PoolName": "DomainResetPool",
                      "UserPoolTags": { "floci:override-id": "%s" }
                    }
                    """.formatted(pinnedPoolId));
            cognitoJson("CreateUserPoolDomain", """
                    {
                      "Domain": "%s",
                      "UserPoolId": "%s"
                    }
                    """.formatted(resetDomain, pinnedPoolId));

            assertEquals(resetDomain, cognitoJson("DescribeUserPool", """
                    { "UserPoolId": "%s" }
                    """.formatted(pinnedPoolId)).path("UserPool").path("Domain").asText());

            given().when().post("/_floci/state/reset").then().statusCode(200);

            JsonNode recreatedPool = cognitoJson("CreateUserPool", """
                    {
                      "PoolName": "DomainResetPoolAgain",
                      "UserPoolTags": { "floci:override-id": "%s" }
                    }
                    """.formatted(pinnedPoolId)).path("UserPool");

            assertNull(recreatedPool.get("Domain"), "a reset pool must not inherit its old domain");
            assertNull(recreatedPool.get("CustomDomain"));
        } finally {
            given().when().post("/_floci/state/reset").then().statusCode(200);
        }
    }
}
