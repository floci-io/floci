package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.xml.HasXPath.hasXPath;

/**
 * ModifyVpcEndpoint. A gateway endpoint is normally declared with its route tables and
 * policy attached after the endpoint exists, so without this action an endpoint can be
 * created but never wired to anything.
 *
 * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_ModifyVpcEndpoint.html">ModifyVpcEndpoint</a>
 */
@QuarkusTest
class Ec2ModifyVpcEndpointTest {

    @Inject
    Ec2Service service;

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private static final String POLICY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"Principal\":\"*\",\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}";

    private String ec2(String action, String... formParams) {
        var req = given().formParam("Action", action).header("Authorization", AUTH_HEADER);
        for (int i = 0; i < formParams.length; i += 2) {
            req = req.formParam(formParams[i], formParams[i + 1]);
        }
        return req.when().post("/").then().statusCode(200).extract().asString();
    }

    private String xmlValue(String xml, String element) {
        String open = "<" + element + ">";
        String close = "</" + element + ">";
        int start = xml.indexOf(open);
        return start < 0 ? null : xml.substring(start + open.length(), xml.indexOf(close, start));
    }

    private record Fixture(String vpcId, String routeTableId, String endpointId) {}

    private Fixture fixture(String cidr) {
        String vpcId = xmlValue(ec2("CreateVpc", "CidrBlock", cidr), "vpcId");
        String routeTableId = xmlValue(ec2("CreateRouteTable", "VpcId", vpcId), "routeTableId");
        String endpointId = xmlValue(ec2("CreateVpcEndpoint",
                "VpcId", vpcId, "ServiceName", "com.amazonaws.us-east-1.s3"), "vpcEndpointId");
        return new Fixture(vpcId, routeTableId, endpointId);
    }

    private String describe(String endpointId) {
        return ec2("DescribeVpcEndpoints", "VpcEndpointId.1", endpointId);
    }

    @Test
    void addRouteTableIdAssociatesTheRouteTable() {
        Fixture f = fixture("10.90.0.0/16");

        given()
            .formParam("Action", "ModifyVpcEndpoint")
            .formParam("VpcEndpointId", f.endpointId())
            .formParam("AddRouteTableId.1", f.routeTableId())
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(hasXPath("//*[local-name()='ModifyVpcEndpointResponse']"
                    + "/*[local-name()='return']", equalTo("true")));

        given()
            .formParam("Action", "DescribeVpcEndpoints")
            .formParam("VpcEndpointId.1", f.endpointId())
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(hasXPath("//*[local-name()='routeTableIdSet']/*[local-name()='item']",
                    equalTo(f.routeTableId())));
    }

    @Test
    void removeRouteTableIdDisassociatesIt() {
        Fixture f = fixture("10.91.0.0/16");
        ec2("ModifyVpcEndpoint", "VpcEndpointId", f.endpointId(), "AddRouteTableId.1", f.routeTableId());
        ec2("ModifyVpcEndpoint", "VpcEndpointId", f.endpointId(), "RemoveRouteTableId.1", f.routeTableId());

        given()
            .formParam("Action", "DescribeVpcEndpoints")
            .formParam("VpcEndpointId.1", f.endpointId())
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(hasXPath("count(//*[local-name()='routeTableIdSet']/*[local-name()='item'])",
                    equalTo("0")));
    }

    @Test
    void addingAnAlreadyAssociatedRouteTableDoesNotDuplicateIt() {
        // AWS is idempotent here, and a duplicate would show as drift on the next plan.
        Fixture f = fixture("10.92.0.0/16");
        ec2("ModifyVpcEndpoint", "VpcEndpointId", f.endpointId(), "AddRouteTableId.1", f.routeTableId());
        ec2("ModifyVpcEndpoint", "VpcEndpointId", f.endpointId(), "AddRouteTableId.1", f.routeTableId());

        given()
            .formParam("Action", "DescribeVpcEndpoints")
            .formParam("VpcEndpointId.1", f.endpointId())
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(hasXPath("count(//*[local-name()='routeTableIdSet']/*[local-name()='item'])",
                    equalTo("1")));
    }

    @Test
    void policyDocumentIsStoredAndReportedBack() {
        Fixture f = fixture("10.93.0.0/16");
        ec2("ModifyVpcEndpoint", "VpcEndpointId", f.endpointId(), "PolicyDocument", POLICY);

        String xml = describe(f.endpointId());
        org.junit.jupiter.api.Assertions.assertTrue(xml.contains("s3:GetObject"),
                "expected the policy document to be reported back, got: " + xml);
    }

    @Test
    void resetPolicyClearsTheDocument() {
        Fixture f = fixture("10.94.0.0/16");
        ec2("ModifyVpcEndpoint", "VpcEndpointId", f.endpointId(), "PolicyDocument", POLICY);
        ec2("ModifyVpcEndpoint", "VpcEndpointId", f.endpointId(), "ResetPolicy", "true");

        String xml = describe(f.endpointId());
        org.junit.jupiter.api.Assertions.assertFalse(xml.contains("s3:GetObject"),
                "expected ResetPolicy to clear the document, got: " + xml);
    }

    @Test
    void createVpcEndpointHonoursAPolicyDocument() {
        // Without this, an endpoint declared with a policy reads back without one and
        // shows as drift on every plan even when ModifyVpcEndpoint is never called.
        String vpcId = xmlValue(ec2("CreateVpc", "CidrBlock", "10.95.0.0/16"), "vpcId");
        String endpointId = xmlValue(ec2("CreateVpcEndpoint",
                "VpcId", vpcId,
                "ServiceName", "com.amazonaws.us-east-1.s3",
                "PolicyDocument", POLICY), "vpcEndpointId");

        String xml = describe(endpointId);
        org.junit.jupiter.api.Assertions.assertTrue(xml.contains("s3:GetObject"),
                "expected the create-time policy to be reported back, got: " + xml);
    }

    @Test
    void concurrentAssociationsAllSurvive() throws Exception {
        // Terraform declares each aws_vpc_endpoint_route_table_association separately and
        // applies them in parallel, so several ModifyVpcEndpoint calls arrive at one
        // endpoint at once. A read-modify-write without a lock loses all but the last,
        // and the losers' waiters poll for an association that was silently dropped:
        //   waiting for VPC Endpoint Route Table Association (vpce-.../rtb-...) to become
        //   available: couldn't find resource (21 retries)
        //
        // Driven through the service rather than over HTTP: requests through the test
        // server are serialized enough that the HTTP path does not reliably reproduce the
        // lost update, so an HTTP-level test would pass with the lock removed.
        String vpcId = service.createVpc("us-east-1", "10.97.0.0/16", false).getVpcId();
        String endpointId = service.createVpcEndpoint("us-east-1", vpcId,
                "com.amazonaws.us-east-1.s3", "Gateway",
                List.of(), List.of(), List.of(), null, null, List.of()).getVpcEndpointId();

        int n = 16;
        List<String> routeTableIds = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            routeTableIds.add(service.createRouteTable("us-east-1", vpcId).getRouteTableId());
        }

        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (String routeTableId : routeTableIds) {
            futures.add(pool.submit(() -> {
                start.await();
                service.modifyVpcEndpoint("us-east-1", endpointId,
                        List.of(routeTableId), List.of(), List.of(), List.of(),
                        List.of(), List.of(), null, null, null);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        List<String> associated = service
                .describeVpcEndpoints("us-east-1", List.of(endpointId), Map.of())
                .getFirst().getRouteTableIds();
        assertEquals(n, associated.size(),
                "every concurrent association must survive; got " + associated);
    }

    @Test
    void modifyingAnUnknownEndpointIsRejected() {
        given()
            .formParam("Action", "ModifyVpcEndpoint")
            .formParam("VpcEndpointId", "vpce-does-not-exist")
            .formParam("ResetPolicy", "true")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            // Assert the code, not just the status: UnsupportedOperation is also a 400, so a
            // status-only assertion passes even when the action is not implemented at all.
            .body(hasXPath("//*[local-name()='Code']", equalTo("InvalidVpcEndpointId.NotFound")));
    }

    @Test
    void omittingVpcEndpointIdIsRejectedAsMissingParameter() {
        // VpcEndpointId is the sole required member of ModifyVpcEndpointRequest. An absent
        // required Query parameter is MissingParameter, not a lookup failure for the id "null".
        given()
            .formParam("Action", "ModifyVpcEndpoint")
            .formParam("ResetPolicy", "true")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(hasXPath("//*[local-name()='Code']", equalTo("MissingParameter")));
    }

    @Test
    void aPresentEmptyPolicyDocumentIsStoredRatherThanDropped() {
        // The model gives PolicyDocument no `min`, so "present" is the only test the request
        // supports; treating an empty string as absent would silently discard a supplied value.
        Fixture f = fixture("10.98.0.0/16");
        ec2("ModifyVpcEndpoint", "VpcEndpointId", f.endpointId(), "PolicyDocument", POLICY);
        ec2("ModifyVpcEndpoint", "VpcEndpointId", f.endpointId(), "PolicyDocument", "");

        assertEquals("", service
                .describeVpcEndpoints("us-east-1", List.of(f.endpointId()), Map.of())
                .getFirst().getPolicyDocument());
    }

    @Test
    void anUnknownRouteTableIsRejectedWithoutMutatingTheEndpoint() {
        Fixture f = fixture("10.96.0.0/16");
        ec2("ModifyVpcEndpoint", "VpcEndpointId", f.endpointId(), "AddRouteTableId.1", f.routeTableId());

        given()
            .formParam("Action", "ModifyVpcEndpoint")
            .formParam("VpcEndpointId", f.endpointId())
            .formParam("AddRouteTableId.1", "rtb-does-not-exist")
            .formParam("RemoveRouteTableId.1", f.routeTableId())
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(hasXPath("//*[local-name()='Code']", equalTo("InvalidRouteTableID.NotFound")));

        // The valid removal in the same request must not have been applied.
        given()
            .formParam("Action", "DescribeVpcEndpoints")
            .formParam("VpcEndpointId.1", f.endpointId())
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(hasXPath("//*[local-name()='routeTableIdSet']/*[local-name()='item']",
                    equalTo(f.routeTableId())));
    }
}
