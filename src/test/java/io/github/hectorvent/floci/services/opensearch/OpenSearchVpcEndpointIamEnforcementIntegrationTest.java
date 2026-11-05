package io.github.hectorvent.floci.services.opensearch;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;

/** The VPC endpoint operations are checked as their own {@code es:} actions. */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class OpenSearchVpcEndpointIamEnforcementIntegrationTest {

    private static final String ACCOUNT_ID = "000000000000";

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void aCallerAllowedOnlyToListEndpointsCannotCreateDescribeUpdateOrDeleteThem() {
        String accessKeyId = userAllowed("es:ListVpcEndpoints");
        String authorization = auth(accessKeyId, "es");

        given().header("Authorization", authorization)
        .when().get("/2021-01-01/opensearch/vpcEndpoints")
        .then().statusCode(200);

        given().header("Authorization", authorization).contentType("application/json")
                .body("""
                        {"DomainArn": "arn:aws:es:us-east-1:000000000000:domain/orders",
                         "VpcOptions": {"SubnetIds": ["subnet-a"]}}""")
        .when().post("/2021-01-01/opensearch/vpcEndpoints")
        .then().statusCode(403);

        given().header("Authorization", authorization).contentType("application/json")
                .body("{\"VpcEndpointIds\": [\"aos-0123456789ab\"]}")
        .when().post("/2021-01-01/opensearch/vpcEndpoints/describe")
        .then().statusCode(403);

        given().header("Authorization", authorization).contentType("application/json")
                .body("{\"VpcEndpointId\": \"aos-0123456789ab\", \"VpcOptions\": {\"SubnetIds\": [\"subnet-a\"]}}")
        .when().post("/2021-01-01/opensearch/vpcEndpoints/update")
        .then().statusCode(403);

        given().header("Authorization", authorization)
        .when().delete("/2021-01-01/opensearch/vpcEndpoints/aos-0123456789ab")
        .then().statusCode(403);

        given().header("Authorization", authorization)
        .when().get("/2021-01-01/opensearch/domain/orders/vpcEndpoints")
        .then().statusCode(403);
    }

    private String userAllowed(String action) {
        String userName = "os-endpoints-" + UUID.randomUUID().toString().substring(0, 8);
        String rootAuthorization = auth(ACCOUNT_ID, "iam");
        String accessKeyId = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), rootAuthorization, userName)
                .accessKeyId();
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), rootAuthorization, userName, "only", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"%s","Resource":"*"}
                ]}""".formatted(action));
        return accessKeyId;
    }
}
