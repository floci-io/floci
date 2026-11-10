package io.floci.aws.services.route53resolver;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * TagResource / UntagResource / ListTagsForResource for the Route 53 Resolver resources Floci
 * stores: resolver endpoints, resolver rules and custom firewall domain lists. Tags given to
 * a Create call must be readable back through ListTagsForResource, which is how the Terraform
 * provider reads them after every create.
 */
@QuarkusTest
class Route53ResolverTaggingIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/route53resolver/aws4_request";
    private static final String OTHER_ACCOUNT_AUTH =
            "AWS4-HMAC-SHA256 Credential=000000000077/20260101/us-east-1/route53resolver/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static ValidatableResponse call(String action, String body) {
        return call(AUTH_HEADER, action, body);
    }

    private static ValidatableResponse call(String auth, String action, String body) {
        return given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "Route53Resolver." + action)
            .header("Authorization", auth)
            .body(body)
        .when()
            .post("/")
        .then();
    }

    private static String createEndpoint(String tagsJson) {
        return call("CreateResolverEndpoint", """
                {"Name":"tagged","Direction":"OUTBOUND","SecurityGroupIds":["sg-1"],
                 "IpAddresses":[{"SubnetId":"subnet-a"},{"SubnetId":"subnet-b"}],
                 "Tags":%s}""".formatted(tagsJson))
            .statusCode(200)
            .extract().path("ResolverEndpoint.Arn");
    }

    private static ValidatableResponse listTags(String arn) {
        return call("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}");
    }

    @Test
    void createResolverEndpoint_tagsAreListed_andNotOnTheEndpointShape() {
        String arn = createEndpoint("[{\"Key\":\"env\",\"Value\":\"dev\"},{\"Key\":\"team\",\"Value\":\"net\"}]");

        listTags(arn)
            .statusCode(200)
            .body("Tags.Key", containsInAnyOrder("env", "team"))
            .body("Tags.find { it.Key == 'env' }.Value", equalTo("dev"));

        String id = arn.substring(arn.lastIndexOf('/') + 1);
        call("GetResolverEndpoint", "{\"ResolverEndpointId\":\"" + id + "\"}")
            .statusCode(200)
            .body("ResolverEndpoint.Tags", nullValue());
    }

    @Test
    void tagUntagLifecycle_onResolverEndpoint() {
        String arn = createEndpoint("[]");
        listTags(arn).statusCode(200).body("Tags", empty());

        call("TagResource", """
                {"ResourceArn":"%s","Tags":[{"Key":"a","Value":"1"},{"Key":"b","Value":"2"}]}""".formatted(arn))
            .statusCode(200);
        call("TagResource", """
                {"ResourceArn":"%s","Tags":[{"Key":"a","Value":"changed"}]}""".formatted(arn))
            .statusCode(200);
        listTags(arn)
            .statusCode(200)
            .body("Tags", hasSize(2))
            .body("Tags.find { it.Key == 'a' }.Value", equalTo("changed"));

        call("UntagResource", "{\"ResourceArn\":\"" + arn + "\",\"TagKeys\":[\"a\",\"absent\"]}")
            .statusCode(200);
        listTags(arn)
            .statusCode(200)
            .body("Tags.Key", containsInAnyOrder("b"));
    }

    @Test
    void resolverRule_isTaggable() {
        String arn = call("CreateResolverRule", """
                {"Name":"r","RuleType":"FORWARD","DomainName":"example.com",
                 "TargetIps":[{"Ip":"10.0.0.2","Port":53}],
                 "Tags":[{"Key":"k","Value":"v"}]}""")
            .statusCode(200)
            .extract().path("ResolverRule.Arn");

        listTags(arn).statusCode(200).body("Tags.Key", containsInAnyOrder("k"));
        call("TagResource", "{\"ResourceArn\":\"" + arn + "\",\"Tags\":[{\"Key\":\"k2\",\"Value\":\"\"}]}")
            .statusCode(200);
        listTags(arn).statusCode(200).body("Tags.Key", containsInAnyOrder("k", "k2"));
    }

    @Test
    void firewallDomainList_isTaggable() {
        String arn = call("CreateFirewallDomainList", """
                {"Name":"fdl","Tags":[{"Key":"k","Value":"v"}]}""")
            .statusCode(200)
            .extract().path("FirewallDomainList.Arn");

        listTags(arn).statusCode(200).body("Tags.Key", containsInAnyOrder("k"));
    }

    @Test
    void listTags_pagesWithMaxResults() {
        String arn = createEndpoint("[{\"Key\":\"a\",\"Value\":\"1\"},{\"Key\":\"b\",\"Value\":\"2\"}]");

        String token = call("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\",\"MaxResults\":1}")
            .statusCode(200)
            .body("Tags", hasSize(1))
            .body("NextToken", notNullValue())
            .extract().path("NextToken");
        call("ListTagsForResource",
                "{\"ResourceArn\":\"" + arn + "\",\"MaxResults\":1,\"NextToken\":\"" + token + "\"}")
            .statusCode(200)
            .body("Tags", hasSize(1))
            .body("NextToken", nullValue());

        for (String malformed : new String[] {"1.5", "true", "\"1\"", "0", "101"}) {
            call("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\",\"MaxResults\":" + malformed + "}")
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterException"));
        }

        // The token is an opaque cursor, not an offset a caller can forge.
        for (String forged : new String[] {"bogus", "1", "0"}) {
            call("ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\",\"NextToken\":\"" + forged + "\"}")
                .statusCode(400)
                .body("__type", equalTo("InvalidNextTokenException"));
        }
    }

    @Test
    void tagListsAreCappedAt200EntriesPerRequest() {
        String arn = createEndpoint("[]");
        call("TagResource", "{\"ResourceArn\":\"" + arn + "\",\"Tags\":" + tags(200) + "}").statusCode(200);
        call("TagResource", "{\"ResourceArn\":\"" + arn + "\",\"Tags\":" + tags(201) + "}")
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
        call("UntagResource", "{\"ResourceArn\":\"" + arn + "\",\"TagKeys\":" + keys(201) + "}")
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
        listTags(arn).statusCode(200).body("Tags", hasSize(100));
        call("UntagResource", "{\"ResourceArn\":\"" + arn + "\",\"TagKeys\":" + keys(200) + "}").statusCode(200);
        listTags(arn).statusCode(200).body("Tags", empty());

        createEndpoint(tags(200));
        call("CreateResolverEndpoint", """
                {"Name":"over","Direction":"OUTBOUND","SecurityGroupIds":["sg-1"],
                 "IpAddresses":[{"SubnetId":"subnet-a"},{"SubnetId":"subnet-b"}],"Tags":%s}""".formatted(tags(201)))
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
        call("CreateResolverRule", """
                {"Name":"ok","RuleType":"SYSTEM","DomainName":"ok.example","Tags":%s}""".formatted(tags(200)))
            .statusCode(200);
        call("CreateResolverRule", """
                {"Name":"over","RuleType":"SYSTEM","DomainName":"over.example","Tags":%s}""".formatted(tags(201)))
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
        call("CreateFirewallDomainList", "{\"Name\":\"ok\",\"Tags\":" + tags(200) + "}").statusCode(200);
        call("CreateFirewallDomainList", "{\"Name\":\"over\",\"Tags\":" + tags(201) + "}")
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    private static String tags(int n) {
        return IntStream.range(0, n).mapToObj(i -> "{\"Key\":\"k" + i + "\",\"Value\":\"v\"}")
            .collect(Collectors.joining(",", "[", "]"));
    }

    private static String keys(int n) {
        return IntStream.range(0, n).mapToObj(i -> "\"k" + i + "\"").collect(Collectors.joining(",", "[", "]"));
    }

    @Test
    void unknownOrDeletedResource_isResourceNotFound() {
        listTags("arn:aws:route53resolver:us-east-1:000000000000:resolver-endpoint/rslvr-out-doesnotexist1")
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));

        String arn = createEndpoint("[{\"Key\":\"a\",\"Value\":\"1\"}]");
        String id = arn.substring(arn.lastIndexOf('/') + 1);
        call("DeleteResolverEndpoint", "{\"ResolverEndpointId\":\"" + id + "\"}").statusCode(200);
        listTags(arn)
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
        call("TagResource", "{\"ResourceArn\":\"" + arn + "\",\"Tags\":[{\"Key\":\"a\",\"Value\":\"1\"}]}")
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void arnNamingAnotherRegionOrAccount_isResourceNotFound() {
        String arn = createEndpoint("[{\"Key\":\"a\",\"Value\":\"1\"}]");

        listTags(arn.replace(":us-east-1:", ":us-west-2:"))
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
        listTags(arn.replace(":000000000000:", ":000000000077:"))
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
        listTags(arn.replace("arn:aws:", "arn:aws-cn:"))
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
        // Another account cannot read or write this account's resource by its exact ARN.
        call(OTHER_ACCOUNT_AUTH, "ListTagsForResource", "{\"ResourceArn\":\"" + arn + "\"}")
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
        call(OTHER_ACCOUNT_AUTH, "UntagResource", "{\"ResourceArn\":\"" + arn + "\",\"TagKeys\":[\"a\"]}")
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
        listTags(arn).statusCode(200).body("Tags.Key", containsInAnyOrder("a"));
    }

    @Test
    void invalidTags_areRejected_andCreateWithInvalidTagStoresNothing() {
        String arn = createEndpoint("[]");
        call("TagResource", "{\"ResourceArn\":\"" + arn + "\",\"Tags\":[{\"Key\":\"\",\"Value\":\"v\"}]}")
            .statusCode(400)
            .body("__type", equalTo("InvalidTagException"));
        call("TagResource", "{\"ResourceArn\":\"" + arn + "\",\"Tags\":[{\"Key\":\"k\"}]}")
            .statusCode(400)
            .body("__type", equalTo("InvalidTagException"));
        for (String malformed : new String[] {
                "{\"Key\":123,\"Value\":\"v\"}", "{\"Key\":\"k\",\"Value\":true}",
                "{\"Key\":\"k\",\"Value\":{\"x\":1}}", "{\"Key\":[\"k\"],\"Value\":\"v\"}"}) {
            call("TagResource", "{\"ResourceArn\":\"" + arn + "\",\"Tags\":[" + malformed + "]}")
                .statusCode(400)
                .body("__type", equalTo("InvalidTagException"));
        }
        call("UntagResource", "{\"ResourceArn\":\"" + arn + "\",\"TagKeys\":[123]}")
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
        call("TagResource", "{\"ResourceArn\":\"" + arn + "\"}")
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
        call("UntagResource", "{\"ResourceArn\":\"" + arn + "\"}")
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
        listTags(arn).statusCode(200).body("Tags", empty());

        int before = call("ListResolverRules", "{}").statusCode(200).extract().path("ResolverRules.size()");
        call("CreateResolverRule", """
                {"Name":"bad","RuleType":"SYSTEM","DomainName":"bad.example","Tags":[{"Key":"","Value":"v"}]}""")
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
        call("ListResolverRules", "{}").statusCode(200).body("ResolverRules", hasSize(before));
    }
}
