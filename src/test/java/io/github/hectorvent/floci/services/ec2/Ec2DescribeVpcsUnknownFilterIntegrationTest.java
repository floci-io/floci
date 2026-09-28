package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * DescribeVpcs rejects a filter name it does not serve.
 *
 * <p>{@code matchesFilters} answers true for an unrecognised name, so an unsupported filter used to
 * match every VPC in the account rather than narrowing anything. A caller reading that result sees
 * a wrong answer with no indication it was wrong, which is worse than an error. Real EC2 refuses
 * the name.
 */
@QuarkusTest
class Ec2DescribeVpcsUnknownFilterIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260908/us-east-1/ec2/aws4_request";

    private static ValidatableResponse describeVpcs(String filterName, String value) {
        return given()
            .formParam("Action", "DescribeVpcs")
            .formParam("Filter.1.Name", filterName)
            .formParam("Filter.1.Value.1", value)
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then();
    }

    @Test
    void anUnknownFilterNameIsRefused() {
        describeVpcs("not-a-filter", "anything")
            .statusCode(400)
            .body(containsString("InvalidParameterValue"))
            .body(containsString("not-a-filter"));
    }

    @Test
    void theDocumentedFilterNamesAreAccepted() {
        for (String name : new String[]{"cidr", "cidr-block-association.state", "dhcp-options-id",
                "ipv6-cidr-block-association.ipv6-pool", "is-default", "owner-id", "state",
                "tag-key", "vpc-id"}) {
            describeVpcs(name, "whatever").statusCode(200);
        }
    }

    /** The undocumented alias matchesFilters already honours, which real EC2 accepts too. */
    @Test
    void theCidrBlockAliasStaysAccepted() {
        describeVpcs("cidr-block", "10.0.0.0/16").statusCode(200);
    }

    /** A tag filter names the tag in the filter name, so the prefix has to pass the check. */
    @Test
    void aTagFilterIsAccepted() {
        describeVpcs("tag:Owner", "TeamA").statusCode(200);
    }

    /**
     * Accepting a name the matcher does not implement is worse than refusing it, because the filter
     * then matches every VPC rather than narrowing. A 200 alone does not catch that, so these check
     * the result actually narrows.
     */
    @Test
    void everyAcceptedNameNarrowsRatherThanMatchingEverything() {
        String vpcId = given()
            .formParam("Action", "CreateVpc").formParam("CidrBlock", "10.91.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when().post("/").then().statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        // Values that cannot belong to any VPC, so a working matcher returns none.
        describeVpcs("dhcp-options-id", "dopt-no-such-option")
            .statusCode(200).body(not(containsString(vpcId)));
        describeVpcs("owner-id", "999999999999")
            .statusCode(200).body(not(containsString(vpcId)));
        describeVpcs("ipv6-cidr-block-association.ipv6-pool", "no-such-pool")
            .statusCode(200).body(not(containsString(vpcId)));

        // And the matching value still finds it.
        describeVpcs("dhcp-options-id", "dopt-default")
            .statusCode(200).body(containsString(vpcId));
    }
}
