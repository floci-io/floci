package io.github.hectorvent.floci.services.opensearch;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * A domain in a VPC and the OpenSearch Service-managed VPC endpoints that lead into one. The
 * shapes, the {@code ENDPOINT_NOT_FOUND} error entry, the malformed-id message and the empty list
 * for an unknown domain were captured from AWS.
 */
@QuarkusTest
class OpenSearchVpcEndpointIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/es/aws4_request";
    private static final String WEST_AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/eu-west-1/es/aws4_request";
    private static final String VPC_DOMAIN = "vpce-domain";
    private static final String PUBLIC_DOMAIN = "vpce-public";
    // The seeded default subnets, so EC2 can name their VPC and zones.
    private static final String SUBNET_A = Ec2Service.defaultSubnetId("us-east-1", "a");
    private static final String SUBNET_B = Ec2Service.defaultSubnetId("us-east-1", "b");

    @Inject
    Ec2Service ec2Service;

    @AfterEach
    void cleanup() {
        for (String name : new String[]{VPC_DOMAIN, PUBLIC_DOMAIN}) {
            given().header("Authorization", AUTH_HEADER).when().delete("/2021-01-01/opensearch/domain/" + name);
        }
        List<String> ids = given().header("Authorization", AUTH_HEADER)
                .when().get("/2021-01-01/opensearch/vpcEndpoints")
                .then().extract().path("VpcEndpointSummaryList.VpcEndpointId");
        if (ids != null) {
            ids.forEach(id -> given().header("Authorization", AUTH_HEADER)
                    .when().delete("/2021-01-01/opensearch/vpcEndpoints/" + id));
        }
    }

    @Test
    void aDomainInAVpcIsReportedUnderEndpointsVpcAndAPublicOneUnderEndpoint() {
        createVpcDomain()
                .body("DomainStatus.Endpoint", nullValue())
                .body("DomainStatus.Endpoints.vpc", startsWith("vpc-" + VPC_DOMAIN + "-"))
                .body("DomainStatus.Endpoints.vpc", endsWith(".us-east-1.es.amazonaws.com"));
        given().header("Authorization", AUTH_HEADER)
                .when().get("/2021-01-01/opensearch/domain/" + VPC_DOMAIN)
                .then().statusCode(200)
                .body("DomainStatus.Endpoint", nullValue())
                .body("DomainStatus.Endpoints.vpc", startsWith("vpc-" + VPC_DOMAIN + "-"));
        // The legacy Elasticsearch API reports the same domain the same way.
        given().header("Authorization", AUTH_HEADER)
                .when().get("/2015-01-01/es/domain/" + VPC_DOMAIN)
                .then().statusCode(200)
                .body("DomainStatus.Endpoint", nullValue())
                .body("DomainStatus.Endpoints.vpc", startsWith("vpc-" + VPC_DOMAIN + "-"));

        given().contentType("application/json").header("Authorization", AUTH_HEADER)
                .body("{\"DomainName\":\"" + PUBLIC_DOMAIN + "\",\"EngineVersion\":\"OpenSearch_2.19\"}")
                .when().post("/2021-01-01/opensearch/domain")
                .then().statusCode(200)
                .body("DomainStatus.Endpoint", startsWith("search-" + PUBLIC_DOMAIN + "-"))
                .body("DomainStatus.Endpoints", nullValue());
    }

    @Test
    void anEndpointIsCreatedDescribedUpdatedListedAndDeleted() {
        String domainArn = createVpcDomain().extract().path("DomainStatus.ARN");
        String domainAddress = given().header("Authorization", AUTH_HEADER)
                .when().get("/2021-01-01/opensearch/domain/" + VPC_DOMAIN)
                .then().extract().path("DomainStatus.Endpoints.vpc");

        String id = createEndpoint(domainArn, null)
                .body("VpcEndpoint.VpcEndpointId", matchesPattern("^aos-[0-9a-f]{12}$"))
                .body("VpcEndpoint.VpcEndpointOwner", equalTo("000000000000"))
                .body("VpcEndpoint.DomainArn", equalTo(domainArn))
                .body("VpcEndpoint.Status", equalTo("CREATING"))
                .body("VpcEndpoint.VpcOptions.SubnetIds", contains(SUBNET_A, SUBNET_B))
                .body("VpcEndpoint.VpcOptions.SecurityGroupIds", contains("sg-one"))
                .body("VpcEndpoint.VpcOptions.VPCId", startsWith("vpc-"))
                .body("VpcEndpoint.VpcOptions.AvailabilityZones", contains("us-east-1a", "us-east-1b"))
                .body("VpcEndpoint.Endpoint", equalTo(domainAddress))
                .extract().path("VpcEndpoint.VpcEndpointId");

        describe("[\"" + id + "\",\"aos-000000000000\"]")
                .body("VpcEndpoints", hasSize(1))
                .body("VpcEndpoints[0].VpcEndpointId", equalTo(id))
                .body("VpcEndpoints[0].Status", equalTo("ACTIVE"))
                .body("VpcEndpointErrors", hasSize(1))
                .body("VpcEndpointErrors[0].VpcEndpointId", equalTo("aos-000000000000"))
                .body("VpcEndpointErrors[0].ErrorCode", equalTo("ENDPOINT_NOT_FOUND"))
                .body("VpcEndpointErrors[0].ErrorMessage", equalTo("Endpoint does not exist"));

        given().contentType("application/json").header("Authorization", AUTH_HEADER)
                .body("{\"VpcEndpointId\":\"" + id + "\",\"VpcOptions\":{\"SubnetIds\":[\"" + SUBNET_A
                        + "\"],\"SecurityGroupIds\":[\"sg-two\"]}}")
                .when().post("/2021-01-01/opensearch/vpcEndpoints/update")
                .then().statusCode(200)
                .body("VpcEndpoint.Status", equalTo("UPDATING"))
                .body("VpcEndpoint.VpcOptions.SubnetIds", contains(SUBNET_A))
                .body("VpcEndpoint.VpcOptions.SecurityGroupIds", contains("sg-two"))
                .body("VpcEndpoint.VpcOptions.AvailabilityZones", contains("us-east-1a"));
        describe("[\"" + id + "\"]")
                .body("VpcEndpoints[0].Status", equalTo("ACTIVE"))
                .body("VpcEndpoints[0].VpcOptions.SecurityGroupIds", contains("sg-two"));

        given().header("Authorization", AUTH_HEADER)
                .when().get("/2021-01-01/opensearch/vpcEndpoints")
                .then().statusCode(200)
                .body("VpcEndpointSummaryList.VpcEndpointId", hasItem(id))
                .body("VpcEndpointSummaryList[0].Status", equalTo("ACTIVE"))
                .body("NextToken", nullValue());
        given().header("Authorization", AUTH_HEADER)
                .when().get("/2021-01-01/opensearch/domain/" + VPC_DOMAIN + "/vpcEndpoints")
                .then().statusCode(200)
                .body("VpcEndpointSummaryList", hasSize(1))
                .body("VpcEndpointSummaryList[0].VpcEndpointId", equalTo(id))
                .body("VpcEndpointSummaryList[0].DomainArn", equalTo(domainArn));
        // Endpoints belong to their region.
        given().header("Authorization", WEST_AUTH_HEADER)
                .when().get("/2021-01-01/opensearch/vpcEndpoints")
                .then().statusCode(200)
                .body("VpcEndpointSummaryList.VpcEndpointId", not(hasItem(id)));
        // A domain of another Region is unknown to a create signed for this one.
        given().contentType("application/json").header("Authorization", WEST_AUTH_HEADER)
                .body("{\"DomainArn\":\"" + domainArn + "\",\"VpcOptions\":{\"SubnetIds\":[\"" + SUBNET_A + "\"]}}")
                .when().post("/2021-01-01/opensearch/vpcEndpoints")
                .then().statusCode(400)
                .body("__type", containsString("ValidationException"))
                .body("message", equalTo("Domain not found for ARN: " + domainArn));

        given().header("Authorization", AUTH_HEADER)
                .when().delete("/2021-01-01/opensearch/vpcEndpoints/" + id)
                .then().statusCode(200)
                .body("VpcEndpointSummary.VpcEndpointId", equalTo(id))
                .body("VpcEndpointSummary.Status", equalTo("DELETING"));
        describe("[\"" + id + "\"]")
                .body("VpcEndpoints", empty())
                .body("VpcEndpointErrors[0].ErrorCode", equalTo("ENDPOINT_NOT_FOUND"));
        given().header("Authorization", AUTH_HEADER)
                .when().delete("/2021-01-01/opensearch/vpcEndpoints/" + id)
                .then().statusCode(409)
                .body("__type", containsString("ResourceNotFoundException"));
    }

    @Test
    void anEndpointWithoutSecurityGroupsTakesTheVpcsDefaultOneOnCreateAndUpdate() {
        String domainArn = createVpcDomain().extract().path("DomainStatus.ARN");
        ValidatableResponse created = given().contentType("application/json").header("Authorization", AUTH_HEADER)
                .body("{\"DomainArn\":\"" + domainArn + "\",\"VpcOptions\":{\"SubnetIds\":[\"" + SUBNET_A + "\"]}}")
                .when().post("/2021-01-01/opensearch/vpcEndpoints")
                .then().statusCode(200);
        String vpcId = created.extract().path("VpcEndpoint.VpcOptions.VPCId");
        String id = created.extract().path("VpcEndpoint.VpcEndpointId");
        String defaultGroup = ec2Service.defaultSecurityGroupOf("us-east-1", vpcId).orElseThrow().getGroupId();
        created.body("VpcEndpoint.VpcOptions.SecurityGroupIds", contains(defaultGroup));

        given().contentType("application/json").header("Authorization", AUTH_HEADER)
                .body("{\"VpcEndpointId\":\"" + id + "\",\"VpcOptions\":{\"SubnetIds\":[\"" + SUBNET_B + "\"]}}")
                .when().post("/2021-01-01/opensearch/vpcEndpoints/update")
                .then().statusCode(200)
                .body("VpcEndpoint.VpcOptions.SecurityGroupIds", contains(defaultGroup));
    }

    @Test
    void aRetriedCreateWithTheSameClientTokenAnswersTheSameEndpoint() {
        String domainArn = createVpcDomain().extract().path("DomainStatus.ARN");

        String first = createEndpoint(domainArn, "token-1").extract().path("VpcEndpoint.VpcEndpointId");
        String retried = createEndpoint(domainArn, "token-1").extract().path("VpcEndpoint.VpcEndpointId");
        String other = createEndpoint(domainArn, "token-2").extract().path("VpcEndpoint.VpcEndpointId");

        assertEquals(first, retried);
        assertNotEquals(first, other);
    }

    @Test
    void requestsAreValidatedAsAwsAnswers() {
        describe("[]").body("VpcEndpoints", empty()).body("VpcEndpointErrors", empty());
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
                .body("{\"VpcEndpointIds\":[\"not-an-id\"]}")
                .when().post("/2021-01-01/opensearch/vpcEndpoints/describe")
                .then().statusCode(400)
                .body("__type", containsString("ValidationException"))
                .body("message", equalTo("1 validation error detected: Value '[not-an-id]' at 'vpcEndpointIds' "
                        + "failed to satisfy constraint: Member must satisfy constraint: [Member must have length "
                        + "less than or equal to 256, Member must have length greater than or equal to 5, Member "
                        + "must satisfy regular expression pattern: ^aos-[a-zA-Z0-9]*$]"));
        given().header("Authorization", AUTH_HEADER)
                .when().get("/2021-01-01/opensearch/domain/no-such-domain/vpcEndpoints")
                .then().statusCode(200)
                .body("VpcEndpointSummaryList", empty());

        given().contentType("application/json").header("Authorization", AUTH_HEADER)
                .body("{\"VpcOptions\":{\"SubnetIds\":[\"" + SUBNET_A + "\"]}}")
                .when().post("/2021-01-01/opensearch/vpcEndpoints")
                .then().statusCode(400)
                .body("__type", containsString("ValidationException"))
                .body("message", containsString("'domainArn' failed to satisfy constraint: Member must not be null"));
        createEndpointResponse("arn:aws:es:us-east-1:000000000000:domain/no-such-domain", null)
                .statusCode(400)
                .body("__type", containsString("ValidationException"));
        given().contentType("application/json").header("Authorization", AUTH_HEADER)
                .body("{\"VpcEndpointId\":\"aos-000000000000\",\"VpcOptions\":{\"SubnetIds\":[]}}")
                .when().post("/2021-01-01/opensearch/vpcEndpoints/update")
                .then().statusCode(409)
                .body("__type", containsString("ResourceNotFoundException"));
    }

    private ValidatableResponse createVpcDomain() {
        return given().contentType("application/json").header("Authorization", AUTH_HEADER)
                .body("{\"DomainName\":\"" + VPC_DOMAIN + "\",\"EngineVersion\":\"OpenSearch_2.19\","
                        + "\"VPCOptions\":{\"SubnetIds\":[\"" + SUBNET_A + "\",\"" + SUBNET_B + "\"]}}")
                .when().post("/2021-01-01/opensearch/domain")
                .then().statusCode(200)
                .body("DomainStatus.ARN", notNullValue());
    }

    private ValidatableResponse createEndpoint(String domainArn, String clientToken) {
        return createEndpointResponse(domainArn, clientToken).statusCode(200);
    }

    private ValidatableResponse createEndpointResponse(String domainArn, String clientToken) {
        String token = clientToken == null ? "" : ",\"ClientToken\":\"" + clientToken + "\"";
        return given().contentType("application/json").header("Authorization", AUTH_HEADER)
                .body("{\"DomainArn\":\"" + domainArn + "\",\"VpcOptions\":{\"SubnetIds\":[\"" + SUBNET_A + "\",\""
                        + SUBNET_B + "\"],\"SecurityGroupIds\":[\"sg-one\"]}" + token + "}")
                .when().post("/2021-01-01/opensearch/vpcEndpoints")
                .then();
    }

    private ValidatableResponse describe(String idsJson) {
        return given().contentType("application/json").header("Authorization", AUTH_HEADER)
                .body("{\"VpcEndpointIds\":" + idsJson + "}")
                .when().post("/2021-01-01/opensearch/vpcEndpoints/describe")
                .then().statusCode(200);
    }
}
