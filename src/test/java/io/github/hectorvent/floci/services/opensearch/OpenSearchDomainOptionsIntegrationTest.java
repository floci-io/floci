package io.github.hectorvent.floci.services.opensearch;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * Verifies that VPC, advanced security, encryption, and endpoint option
 * blocks round-trip from {@code CreateDomain} / {@code UpdateDomainConfig}
 * through {@code DescribeDomain} / {@code DescribeDomainConfig}.
 *
 * <p>Real SDK clients (Terraform / CDK / Pulumi) read these fields back to
 * detect drift; if they don't survive the round-trip, every plan re-applies
 * the same change forever.
 */
@QuarkusTest
class OpenSearchDomainOptionsIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/es/aws4_request";

    @AfterEach
    void cleanup() {
        // Delete every domain we touch so test order is irrelevant.
        for (String name : new String[]{"opts-vpc", "opts-adv", "opts-enc", "opts-endpoint", "opts-update"}) {
            given().header("Authorization", AUTH_HEADER)
                .when().delete("/2021-01-01/opensearch/domain/" + name);
        }
    }

    @Test
    void vpcOptionsRoundTrip() {
        String body = "{"
                + "\"DomainName\":\"opts-vpc\","
                + "\"EngineVersion\":\"OpenSearch_2.19\","
                + "\"VPCOptions\":{"
                + "  \"SubnetIds\":[\"subnet-aaa\",\"subnet-bbb\"],"
                + "  \"SecurityGroupIds\":[\"sg-111\"],"
                + "  \"VPCId\":\"vpc-zzz\""
                + "}}";

        given().contentType("application/json").header("Authorization", AUTH_HEADER).body(body)
            .when().post("/2021-01-01/opensearch/domain")
            .then().statusCode(200)
            .body("DomainStatus.VPCOptions.SubnetIds", contains("subnet-aaa", "subnet-bbb"))
            .body("DomainStatus.VPCOptions.SecurityGroupIds", contains("sg-111"))
            .body("DomainStatus.VPCOptions.VPCId", equalTo("vpc-zzz"));

        given().header("Authorization", AUTH_HEADER)
            .when().get("/2021-01-01/opensearch/domain/opts-vpc")
            .then().statusCode(200)
            .body("DomainStatus.VPCOptions.SubnetIds", hasItem("subnet-aaa"))
            .body("DomainStatus.VPCOptions.VPCId", equalTo("vpc-zzz"));

        given().header("Authorization", AUTH_HEADER)
            .when().get("/2021-01-01/opensearch/domain/opts-vpc/config")
            .then().statusCode(200)
            .body("DomainConfig.VPCOptions.Options.VPCId", equalTo("vpc-zzz"))
            .body("DomainConfig.VPCOptions.Status.State", equalTo("Active"));
    }

    @Test
    void advancedSecurityOptionsRoundTripsButNeverEchoesPassword() {
        String body = "{"
                + "\"DomainName\":\"opts-adv\","
                + "\"EngineVersion\":\"OpenSearch_2.19\","
                + "\"AdvancedSecurityOptions\":{"
                + "  \"Enabled\":true,"
                + "  \"InternalUserDatabaseEnabled\":true,"
                + "  \"MasterUserOptions\":{"
                + "    \"MasterUserName\":\"admin\","
                + "    \"MasterUserPassword\":\"super-secret\""
                + "  }}"
                + "}";

        given().contentType("application/json").header("Authorization", AUTH_HEADER).body(body)
            .when().post("/2021-01-01/opensearch/domain")
            .then().statusCode(200)
            .body("DomainStatus.AdvancedSecurityOptions.Enabled", is(true))
            .body("DomainStatus.AdvancedSecurityOptions.InternalUserDatabaseEnabled", is(true))
            .body("DomainStatus.AdvancedSecurityOptions.MasterUserOptions.MasterUserName", equalTo("admin"))
            // Master password must not leak — AWS doesn't echo it back either.
            .body("DomainStatus.AdvancedSecurityOptions.MasterUserOptions.MasterUserPassword", nullValue());
    }

    @Test
    void advancedSecurityRequiresMasterUserWhenInternalDbEnabled() {
        // Reject the same combination AWS rejects so Terraform plans surface
        // the validation error here instead of waiting for prod.
        String body = "{"
                + "\"DomainName\":\"opts-adv\","
                + "\"EngineVersion\":\"OpenSearch_2.19\","
                + "\"AdvancedSecurityOptions\":{"
                + "  \"Enabled\":true,"
                + "  \"InternalUserDatabaseEnabled\":true"
                + "}}";

        given().contentType("application/json").header("Authorization", AUTH_HEADER).body(body)
            .when().post("/2021-01-01/opensearch/domain")
            .then().statusCode(400);
    }

    @Test
    void encryptionAndNodeToNodeOptionsRoundTrip() {
        String body = "{"
                + "\"DomainName\":\"opts-enc\","
                + "\"EngineVersion\":\"OpenSearch_2.19\","
                + "\"EncryptionAtRestOptions\":{"
                + "  \"Enabled\":true,"
                + "  \"KmsKeyId\":\"arn:aws:kms:us-east-1:000000000000:key/my-key\""
                + "},"
                + "\"NodeToNodeEncryptionOptions\":{\"Enabled\":true}"
                + "}";

        given().contentType("application/json").header("Authorization", AUTH_HEADER).body(body)
            .when().post("/2021-01-01/opensearch/domain")
            .then().statusCode(200)
            .body("DomainStatus.EncryptionAtRestOptions.Enabled", is(true))
            .body("DomainStatus.EncryptionAtRestOptions.KmsKeyId",
                    equalTo("arn:aws:kms:us-east-1:000000000000:key/my-key"))
            .body("DomainStatus.NodeToNodeEncryptionOptions.Enabled", is(true));
    }

    @Test
    void domainEndpointOptionsRoundTripAndRejectMissingCustomEndpoint() {
        String okBody = "{"
                + "\"DomainName\":\"opts-endpoint\","
                + "\"EngineVersion\":\"OpenSearch_2.19\","
                + "\"DomainEndpointOptions\":{"
                + "  \"EnforceHTTPS\":true,"
                + "  \"TLSSecurityPolicy\":\"Policy-Min-TLS-1-2-2019-07\""
                + "}}";

        given().contentType("application/json").header("Authorization", AUTH_HEADER).body(okBody)
            .when().post("/2021-01-01/opensearch/domain")
            .then().statusCode(200)
            .body("DomainStatus.DomainEndpointOptions.EnforceHTTPS", is(true))
            .body("DomainStatus.DomainEndpointOptions.TLSSecurityPolicy",
                    equalTo("Policy-Min-TLS-1-2-2019-07"));

        // Custom endpoint without a value set is malformed — match AWS behavior.
        String badBody = "{"
                + "\"DomainName\":\"opts-endpoint-bad\","
                + "\"EngineVersion\":\"OpenSearch_2.19\","
                + "\"DomainEndpointOptions\":{"
                + "  \"CustomEndpointEnabled\":true"
                + "}}";

        given().contentType("application/json").header("Authorization", AUTH_HEADER).body(badBody)
            .when().post("/2021-01-01/opensearch/domain")
            .then().statusCode(400);
    }

    @Test
    void updateDomainConfigPersistsNewVpcOptions() {
        // Create with one set of subnets; update to a different set.
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"DomainName\":\"opts-update\",\"EngineVersion\":\"OpenSearch_2.19\","
                    + "\"VPCOptions\":{\"SubnetIds\":[\"subnet-old\"]}}")
            .when().post("/2021-01-01/opensearch/domain")
            .then().statusCode(200);

        given().contentType("application/json").header("Authorization", AUTH_HEADER)
            .body("{\"VPCOptions\":{\"SubnetIds\":[\"subnet-new\"],\"SecurityGroupIds\":[\"sg-new\"]}}")
            .when().post("/2021-01-01/opensearch/domain/opts-update/config")
            .then().statusCode(200)
            .body("DomainConfig.VPCOptions.Options.SubnetIds", contains("subnet-new"));

        given().header("Authorization", AUTH_HEADER)
            .when().get("/2021-01-01/opensearch/domain/opts-update")
            .then().statusCode(200)
            .body("DomainStatus.VPCOptions.SubnetIds", contains("subnet-new"))
            .body("DomainStatus.VPCOptions.SubnetIds", not(hasItem("subnet-old")));
    }
}
