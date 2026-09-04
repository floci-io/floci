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
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers CreateUserPoolDomain/DescribeUserPoolDomain/UpdateUserPoolDomain/DeleteUserPoolDomain (lex00/floci#63)
 * for both an Amazon Cognito prefix domain and a custom domain fronted by an ACM certificate.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CognitoUserPoolDomainIntegrationTest {

    private static String poolId;
    private static String prefixDomain;
    private static String customDomain;
    private static final String CERTIFICATE_ARN =
            "arn:aws:acm:us-east-1:000000000000:certificate/" + UUID.randomUUID();
    private static final String RENEWED_CERTIFICATE_ARN =
            "arn:aws:acm:us-east-1:000000000000:certificate/" + UUID.randomUUID();

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
        prefixDomain = "floci-test-" + UUID.randomUUID().toString().substring(0, 8);
        customDomain = "auth-" + UUID.randomUUID().toString().substring(0, 8) + ".example.com";
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
    }

    @Test
    @Order(4)
    void deletePrefixDomainSucceeds() {
        cognitoAction("DeleteUserPoolDomain", """
                {
                  "Domain": "%s",
                  "UserPoolId": "%s"
                }
                """.formatted(prefixDomain, poolId))
                .then()
                .statusCode(200);
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
                .statusCode(404)
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
    }

    @Test
    @Order(8)
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
    @Order(9)
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
    @Order(10)
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
    @Order(11)
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
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(12)
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
    @Order(13)
    void deleteUserPoolSucceedsOnceDomainIsGone() {
        cognitoAction("DeleteUserPool", """
                {
                  "UserPoolId": "%s"
                }
                """.formatted(poolId))
                .then()
                .statusCode(200);
    }
}
