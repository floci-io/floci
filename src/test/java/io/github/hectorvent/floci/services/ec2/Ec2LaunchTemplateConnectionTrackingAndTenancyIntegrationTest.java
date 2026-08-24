package io.github.hectorvent.floci.services.ec2;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * lex00/floci#119's own round-5 re-measure (choudoufu's round-5 repin, commit ae2a613b25):
 * network_interfaces.connection_tracking_specification and placement.tenancy were named as
 * "still entirely absent on read" when set together (direct probe: CreateLaunchTemplate with
 * both set echoed back neither on DescribeLaunchTemplateVersions). connection_tracking_
 * specification genuinely had no field at all - fixed here. placement.tenancy turned out to
 * already round-trip on its own (see Ec2LaunchTemplateFieldsIntegrationTest, unchanged); this
 * class proves it keeps working when BOTH fields are set on the same template, matching the
 * probe's exact combination. Oracle: botocore's ec2/2016-11-15/service-2.json
 * ConnectionTrackingSpecificationRequest/-Response and Placement/ResponseLaunchTemplateData
 * shapes. Asserts the raw DescribeLaunchTemplateVersions wire response, not the store.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2LaunchTemplateConnectionTrackingAndTenancyIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-west-2/ec2/aws4_request";

    @Test
    @Order(1)
    void createRoundTripsConnectionTrackingAndTenancyTogether() {
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", "connection-tracking-tenancy-test")
            .formParam("LaunchTemplateData.ImageId", "ami-0123456789abcdef0")
            .formParam("LaunchTemplateData.InstanceType", "t3.micro")
            .formParam("LaunchTemplateData.NetworkInterface.1.DeviceIndex", "0")
            .formParam("LaunchTemplateData.NetworkInterface.1.SubnetId", "subnet-0123456789abcdef0")
            .formParam("LaunchTemplateData.NetworkInterface.1.ConnectionTrackingSpecification.TcpEstablishedTimeout", "300")
            .formParam("LaunchTemplateData.NetworkInterface.1.ConnectionTrackingSpecification.UdpStreamTimeout", "120")
            .formParam("LaunchTemplateData.NetworkInterface.1.ConnectionTrackingSpecification.UdpTimeout", "45")
            .formParam("LaunchTemplateData.Placement.Tenancy", "dedicated")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void describeLaunchTemplateVersionsEchoesBothBack() {
        given()
            .formParam("Action", "DescribeLaunchTemplateVersions")
            .formParam("LaunchTemplateName", "connection-tracking-tenancy-test")
            .formParam("Versions.1", "$Latest")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.networkInterfaceSet.item.connectionTrackingSpecification.tcpEstablishedTimeout",
                    equalTo("300"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.networkInterfaceSet.item.connectionTrackingSpecification.udpStreamTimeout",
                    equalTo("120"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.networkInterfaceSet.item.connectionTrackingSpecification.udpTimeout",
                    equalTo("45"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.placement.tenancy",
                    equalTo("dedicated"));
    }
}
