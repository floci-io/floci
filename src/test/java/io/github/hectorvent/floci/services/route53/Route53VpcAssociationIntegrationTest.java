package io.github.hectorvent.floci.services.route53;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class Route53VpcAssociationIntegrationTest {

    private static final String XML = "application/xml";

    @Test
    void associateListAndDisassociateRoundTrip() {
        String zoneId = createPrivateZone("roundtrip.internal.", "vpc-assoc-roundtrip", "vpc-roundtrip-primary");

        given().contentType(XML)
                .body(vpcRequest("AssociateVPCWithHostedZoneRequest", "vpc-roundtrip-secondary",
                        "<Comment>central endpoints</Comment>"))
                .post("/2013-04-01/hostedzone/" + zoneId + "/associatevpc")
                .then().statusCode(200)
                .body(containsString("<AssociateVPCWithHostedZoneResponse"))
                .body(containsString("<ChangeInfo>"))
                .body(containsString("<Status>INSYNC</Status>"));

        given().get("/2013-04-01/hostedzone/" + zoneId)
                .then().statusCode(200)
                .body(containsString("<VPCId>vpc-roundtrip-primary</VPCId>"))
                .body(containsString("<VPCId>vpc-roundtrip-secondary</VPCId>"));

        given().get("/2013-04-01/hostedzonesbyvpc?vpcid=vpc-roundtrip-secondary&vpcregion=us-east-1")
                .then().statusCode(200)
                .body(containsString("<ListHostedZonesByVPCResponse"))
                .body(containsString("<HostedZoneSummary>"))
                .body(containsString("<HostedZoneId>" + zoneId + "</HostedZoneId>"))
                .body(containsString("<Name>roundtrip.internal.</Name>"))
                .body(containsString("<OwningAccount>"))
                .body(containsString("<MaxItems>"));

        given().contentType(XML)
                .body(vpcRequest("DisassociateVPCFromHostedZoneRequest", "vpc-roundtrip-secondary", ""))
                .post("/2013-04-01/hostedzone/" + zoneId + "/disassociatevpc")
                .then().statusCode(200)
                .body(containsString("<DisassociateVPCFromHostedZoneResponse"))
                .body(containsString("<Status>INSYNC</Status>"));

        given().get("/2013-04-01/hostedzone/" + zoneId)
                .then().statusCode(200)
                .body(containsString("<VPCId>vpc-roundtrip-primary</VPCId>"))
                .body(not(containsString("<VPCId>vpc-roundtrip-secondary</VPCId>")));

        given().get("/2013-04-01/hostedzonesbyvpc?vpcid=vpc-roundtrip-secondary&vpcregion=us-east-1")
                .then().statusCode(200)
                .body(not(containsString("<HostedZoneId>" + zoneId + "</HostedZoneId>")));
    }

    @Test
    void associateIsIdempotentForAnAlreadyAssociatedVpc() {
        String zoneId = createPrivateZone("idempotent.internal.", "vpc-assoc-idempotent", "vpc-idempotent-primary");

        given().contentType(XML)
                .body(vpcRequest("AssociateVPCWithHostedZoneRequest", "vpc-idempotent-primary", ""))
                .post("/2013-04-01/hostedzone/" + zoneId + "/associatevpc")
                .then().statusCode(200);

        // The zone must still carry exactly one association, so removing it is the
        // last-association case rather than leaving a duplicate behind.
        given().contentType(XML)
                .body(vpcRequest("DisassociateVPCFromHostedZoneRequest", "vpc-idempotent-primary", ""))
                .post("/2013-04-01/hostedzone/" + zoneId + "/disassociatevpc")
                .then().statusCode(400)
                .body(containsString("<Code>LastVPCAssociation</Code>"));
    }

    @Test
    void listHostedZonesByVpcPaginatesWithNextToken() {
        String shared = "vpc-paginated-shared";
        String firstZone = createPrivateZone("aaa-paged.internal.", "vpc-assoc-paged-a", shared);
        String secondZone = createPrivateZone("bbb-paged.internal.", "vpc-assoc-paged-b", shared);

        String token = given().get("/2013-04-01/hostedzonesbyvpc?vpcid=" + shared
                        + "&vpcregion=us-east-1&maxitems=1")
                .then().statusCode(200)
                .body(containsString("<HostedZoneId>" + firstZone + "</HostedZoneId>"))
                .body(not(containsString("<HostedZoneId>" + secondZone + "</HostedZoneId>")))
                .body(containsString("<NextToken>"))
                .extract().path("ListHostedZonesByVPCResponse.NextToken").toString();

        // Following the token must advance the page rather than repeat the first one.
        given().get("/2013-04-01/hostedzonesbyvpc?vpcid=" + shared
                        + "&vpcregion=us-east-1&maxitems=1&nexttoken=" + token)
                .then().statusCode(200)
                .body(containsString("<HostedZoneId>" + secondZone + "</HostedZoneId>"))
                .body(not(containsString("<HostedZoneId>" + firstZone + "</HostedZoneId>")))
                .body(not(containsString("<NextToken>")));
    }

    @Test
    void associateWithPublicZoneIsRejected() {
        String create = """
                <CreateHostedZoneRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <Name>public-assoc.example.com</Name>
                  <CallerReference>vpc-assoc-public-zone</CallerReference>
                </CreateHostedZoneRequest>
                """;
        String zoneId = zoneIdFrom(given().contentType(XML).body(create)
                .post("/2013-04-01/hostedzone")
                .then().statusCode(201).extract().header("Location"));

        given().contentType(XML)
                .body(vpcRequest("AssociateVPCWithHostedZoneRequest", "vpc-public-reject", ""))
                .post("/2013-04-01/hostedzone/" + zoneId + "/associatevpc")
                .then().statusCode(400)
                .body(containsString("<Code>PublicZoneVPCAssociation</Code>"));
    }

    @Test
    void disassociateUnknownVpcReturnsVpcAssociationNotFound() {
        String zoneId = createPrivateZone("notfound.internal.", "vpc-assoc-notfound", "vpc-notfound-primary");

        given().contentType(XML)
                .body(vpcRequest("DisassociateVPCFromHostedZoneRequest", "vpc-never-associated", ""))
                .post("/2013-04-01/hostedzone/" + zoneId + "/disassociatevpc")
                .then().statusCode(404)
                .body(containsString("<Code>VPCAssociationNotFound</Code>"));
    }

    @Test
    void associateWithoutVpcElementIsInvalidInput() {
        String zoneId = createPrivateZone("novpc.internal.", "vpc-assoc-missing-vpc", "vpc-missing-primary");

        String body = """
                <AssociateVPCWithHostedZoneRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <Comment>no vpc supplied</Comment>
                </AssociateVPCWithHostedZoneRequest>
                """;
        given().contentType(XML).body(body)
                .post("/2013-04-01/hostedzone/" + zoneId + "/associatevpc")
                .then().statusCode(400)
                .body(containsString("<Code>InvalidInput</Code>"));
    }

    @Test
    void associateWithUnknownHostedZoneReturnsNoSuchHostedZone() {
        given().contentType(XML)
                .body(vpcRequest("AssociateVPCWithHostedZoneRequest", "vpc-orphan", ""))
                .post("/2013-04-01/hostedzone/ZNOSUCHZONE123/associatevpc")
                .then().statusCode(404)
                .body(containsString("<Code>NoSuchHostedZone</Code>"));
    }

    @Test
    void createAndDeleteVpcAssociationAuthorization() {
        String zoneId = createPrivateZone("authz.internal.", "vpc-assoc-authorization", "vpc-authz-primary");

        given().contentType(XML)
                .body(vpcRequest("CreateVPCAssociationAuthorizationRequest", "vpc-authz-guest", ""))
                .post("/2013-04-01/hostedzone/" + zoneId + "/authorizevpcassociation")
                .then().statusCode(200)
                .body(containsString("<CreateVPCAssociationAuthorizationResponse"))
                .body(containsString("<HostedZoneId>" + zoneId + "</HostedZoneId>"))
                .body(containsString("<VPCId>vpc-authz-guest</VPCId>"))
                .body(containsString("<VPCRegion>us-east-1</VPCRegion>"));

        given().contentType(XML)
                .body(vpcRequest("DeleteVPCAssociationAuthorizationRequest", "vpc-authz-guest", ""))
                .post("/2013-04-01/hostedzone/" + zoneId + "/deauthorizevpcassociation")
                .then().statusCode(200);
    }

    @Test
    void authorizeVpcAssociationOnUnknownZoneReturnsNoSuchHostedZone() {
        given().contentType(XML)
                .body(vpcRequest("CreateVPCAssociationAuthorizationRequest", "vpc-authz-orphan", ""))
                .post("/2013-04-01/hostedzone/ZNOSUCHZONE456/authorizevpcassociation")
                .then().statusCode(404)
                .body(containsString("<Code>NoSuchHostedZone</Code>"));
    }

    @Test
    void listHostedZonesByVpcRequiresVpcIdAndRegion() {
        given().get("/2013-04-01/hostedzonesbyvpc?vpcregion=us-east-1")
                .then().statusCode(400)
                .body(containsString("<Code>InvalidInput</Code>"))
                .body(containsString("VPCId is required"));

        given().get("/2013-04-01/hostedzonesbyvpc?vpcid=vpc-no-region")
                .then().statusCode(400)
                .body(containsString("<Code>InvalidInput</Code>"))
                .body(containsString("VPCRegion is required"));
    }

    private static String createPrivateZone(String name, String callerReference, String vpcId) {
        String create = """
                <CreateHostedZoneRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <Name>%s</Name>
                  <CallerReference>%s</CallerReference>
                  <VPC><VPCRegion>us-east-1</VPCRegion><VPCId>%s</VPCId></VPC>
                </CreateHostedZoneRequest>
                """.formatted(name, callerReference, vpcId);
        return zoneIdFrom(given().contentType(XML).body(create)
                .post("/2013-04-01/hostedzone")
                .then().statusCode(201).extract().header("Location"));
    }

    private static String vpcRequest(String rootElement, String vpcId, String extra) {
        return """
                <%s xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <VPC><VPCRegion>us-east-1</VPCRegion><VPCId>%s</VPCId></VPC>%s
                </%s>
                """.formatted(rootElement, vpcId, extra, rootElement);
    }

    private static String zoneIdFrom(String location) {
        return location.substring(location.lastIndexOf('/') + 1);
    }
}
