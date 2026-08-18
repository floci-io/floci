package io.github.hectorvent.floci.services.route53;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * A private hosted zone is one with a VPC attached. The Terraform AWS provider never sends
 * {@code HostedZoneConfig.PrivateZone}; it sends a {@code VPC} and lets the API infer the rest,
 * so a zone created that way has to come back private or no {@code private_zone = true} data
 * source can ever match it.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Route53PrivateZoneVpcIntegrationTest {

    private static final String XML = "application/xml";

    private static String zoneId;

    private static String createRequest(String name, String ref, String vpcBlock) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <CreateHostedZoneRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <Name>%s</Name>
                  <CallerReference>%s</CallerReference>
                  %s
                </CreateHostedZoneRequest>
                """.formatted(name, ref, vpcBlock);
    }

    private static String vpc(String id, String region) {
        return "<VPC><VPCRegion>" + region + "</VPCRegion><VPCId>" + id + "</VPCId></VPC>";
    }

    @Test
    @Order(1)
    void createWithVpcAndNoPrivateZoneFlagYieldsAPrivateZone() {
        String location = given()
                .contentType(XML)
                .body(createRequest("vpczone.internal", "vpc-ref-001", vpc("vpc-aaa111", "eu-west-1")))
                .when().post("/2013-04-01/hostedzone")
                .then()
                .statusCode(201)
                .body("CreateHostedZoneResponse.HostedZone.Config.PrivateZone", equalTo("true"))
                .body("CreateHostedZoneResponse.VPC.VPCId", equalTo("vpc-aaa111"))
                .body("CreateHostedZoneResponse.VPC.VPCRegion", equalTo("eu-west-1"))
                .extract().header("Location");
        zoneId = location.substring(location.lastIndexOf('/') + 1);
    }

    @Test
    @Order(2)
    void getHostedZoneReportsTheAssociatedVpcs() {
        given()
                .when().get("/2013-04-01/hostedzone/" + zoneId)
                .then()
                .statusCode(200)
                .body("GetHostedZoneResponse.HostedZone.Config.PrivateZone", equalTo("true"))
                .body("GetHostedZoneResponse.VPCs.VPC.VPCId", equalTo("vpc-aaa111"));
    }

    @Test
    @Order(3)
    void listHostedZonesReportsThePrivateFlag() {
        given()
                .when().get("/2013-04-01/hostedzone")
                .then()
                .statusCode(200)
                .body(containsString("vpczone.internal."))
                .body(containsString("<PrivateZone>true</PrivateZone>"));
    }

    @Test
    @Order(4)
    void associateVpcAddsASecondAssociation() {
        given()
                .contentType(XML)
                .body("<AssociateVPCWithHostedZoneRequest>" + vpc("vpc-bbb222", "eu-west-1")
                        + "</AssociateVPCWithHostedZoneRequest>")
                .when().post("/2013-04-01/hostedzone/" + zoneId + "/associatevpc")
                .then()
                .statusCode(200)
                .body("AssociateVPCWithHostedZoneResponse.ChangeInfo.Id", startsWith("/change/C"));

        given()
                .when().get("/2013-04-01/hostedzone/" + zoneId)
                .then()
                .statusCode(200)
                .body(containsString("vpc-aaa111"))
                .body(containsString("vpc-bbb222"));
    }

    @Test
    @Order(5)
    void associatingTheSameVpcTwiceDoesNotDuplicateIt() {
        given()
                .contentType(XML)
                .body("<AssociateVPCWithHostedZoneRequest>" + vpc("vpc-bbb222", "eu-west-1")
                        + "</AssociateVPCWithHostedZoneRequest>")
                .when().post("/2013-04-01/hostedzone/" + zoneId + "/associatevpc")
                .then()
                .statusCode(200);

        String body = given()
                .when().get("/2013-04-01/hostedzone/" + zoneId)
                .then().statusCode(200).extract().asString();

        int first = body.indexOf("vpc-bbb222");
        assert first >= 0 : "expected the association to still be reported";
        assert body.indexOf("vpc-bbb222", first + 1) < 0 : "expected exactly one vpc-bbb222 association";
    }

    @Test
    @Order(6)
    void listHostedZonesByVpcFindsTheZone() {
        given()
                .when().get("/2013-04-01/hostedzonesbyvpc?vpcid=vpc-bbb222&vpcregion=eu-west-1")
                .then()
                .statusCode(200)
                .body(containsString(zoneId))
                .body(containsString("vpczone.internal."));

        given()
                .when().get("/2013-04-01/hostedzonesbyvpc?vpcid=vpc-nope&vpcregion=eu-west-1")
                .then()
                .statusCode(200)
                .body(not(containsString("vpczone.internal.")));
    }

    @Test
    @Order(7)
    void disassociateRemovesOneButRefusesTheLast() {
        given()
                .contentType(XML)
                .body("<DisassociateVPCFromHostedZoneRequest>" + vpc("vpc-bbb222", "eu-west-1")
                        + "</DisassociateVPCFromHostedZoneRequest>")
                .when().post("/2013-04-01/hostedzone/" + zoneId + "/disassociatevpc")
                .then()
                .statusCode(200);

        given()
                .contentType(XML)
                .body("<DisassociateVPCFromHostedZoneRequest>" + vpc("vpc-aaa111", "eu-west-1")
                        + "</DisassociateVPCFromHostedZoneRequest>")
                .when().post("/2013-04-01/hostedzone/" + zoneId + "/disassociatevpc")
                .then()
                .statusCode(400)
                .body(containsString("LastVPCAssociation"));
    }

    @Test
    @Order(8)
    void aPublicZoneRefusesAVpcAssociation() {
        String location = given()
                .contentType(XML)
                .body(createRequest("publiczone.example", "vpc-ref-002", ""))
                .when().post("/2013-04-01/hostedzone")
                .then()
                .statusCode(201)
                .body("CreateHostedZoneResponse.HostedZone.Config.PrivateZone", equalTo("false"))
                .extract().header("Location");
        String publicId = location.substring(location.lastIndexOf('/') + 1);

        given()
                .contentType(XML)
                .body("<AssociateVPCWithHostedZoneRequest>" + vpc("vpc-ccc333", "eu-west-1")
                        + "</AssociateVPCWithHostedZoneRequest>")
                .when().post("/2013-04-01/hostedzone/" + publicId + "/associatevpc")
                .then()
                .statusCode(400)
                .body(containsString("PublicZoneVPCAssociation"));
    }

    @Test
    @Order(9)
    void explicitPrivateZoneFlagStillWorksWithoutAVpc() {
        given()
                .contentType(XML)
                .body(createRequest("flagonly.internal", "vpc-ref-003",
                        "<HostedZoneConfig><PrivateZone>true</PrivateZone></HostedZoneConfig>"))
                .when().post("/2013-04-01/hostedzone")
                .then()
                .statusCode(201)
                .body("CreateHostedZoneResponse.HostedZone.Config.PrivateZone", equalTo("true"));
    }
}
