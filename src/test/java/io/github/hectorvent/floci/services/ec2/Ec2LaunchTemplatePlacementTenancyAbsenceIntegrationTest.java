package io.github.hectorvent.floci.services.ec2;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * lex00/floci#126: DescribeLaunchTemplateVersions synthesized Placement.Tenancy = "default" for a
 * launch template whose creator never set tenancy at all. The bug was in the shared Placement
 * model class: its tenancy field carries a "default" field initializer, which is correct for a
 * running Instance's Placement (a live instance always reports a tenancy) but wrong for a launch
 * template, which is a stored document rather than a running instance - AWS's Describe leaves an
 * unset field absent rather than inventing a value. parseLaunchTemplatePlacement only called
 * Placement.setTenancy() when the request carried LaunchTemplateData.Placement.Tenancy, so an
 * unset tenancy silently kept the class field's "default" instead of becoming null, and it leaked
 * onto the wire whenever any OTHER placement field (e.g. GroupName) made the placement block
 * non-empty. Oracle: botocore's ec2/2016-11-15/service-2.json Placement/ResponseLaunchTemplateData
 * shape, plus the general rule that a stored template's Describe echoes exactly what was stored.
 * Asserts the raw DescribeLaunchTemplateVersions wire response, not the store.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2LaunchTemplatePlacementTenancyAbsenceIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-west-2/ec2/aws4_request";

    @Test
    @Order(1)
    void unsetTenancyStaysAbsentEvenWhenAnotherPlacementFieldIsSet() {
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", "placement-no-tenancy-test")
            .formParam("LaunchTemplateData.ImageId", "ami-0123456789abcdef0")
            .formParam("LaunchTemplateData.InstanceType", "t3.micro")
            .formParam("LaunchTemplateData.Placement.GroupName", "cluster-group")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String xml = given()
            .formParam("Action", "DescribeLaunchTemplateVersions")
            .formParam("LaunchTemplateName", "placement-no-tenancy-test")
            .formParam("Versions.1", "$Latest")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            // The field the request DID set still round-trips...
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.placement.groupName",
                    equalTo("cluster-group"))
            .extract().asString();
        // ...but tenancy, which the request never carried, must not be invented.
        assertTrue(xml.contains("<groupName>cluster-group</groupName>"), xml);
        assertFalse(xml.contains("<tenancy>"), xml);
    }

    @Test
    @Order(2)
    void explicitTenancyStillRoundTripsAlongsideAnotherPlacementField() {
        given()
            .formParam("Action", "CreateLaunchTemplate")
            .formParam("LaunchTemplateName", "placement-explicit-tenancy-test")
            .formParam("LaunchTemplateData.ImageId", "ami-0123456789abcdef0")
            .formParam("LaunchTemplateData.InstanceType", "t3.micro")
            .formParam("LaunchTemplateData.Placement.GroupName", "cluster-group")
            .formParam("LaunchTemplateData.Placement.Tenancy", "host")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DescribeLaunchTemplateVersions")
            .formParam("LaunchTemplateName", "placement-explicit-tenancy-test")
            .formParam("Versions.1", "$Latest")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.placement.groupName",
                    equalTo("cluster-group"))
            .body("DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item.launchTemplateData.placement.tenancy",
                    equalTo("host"));
    }
}
