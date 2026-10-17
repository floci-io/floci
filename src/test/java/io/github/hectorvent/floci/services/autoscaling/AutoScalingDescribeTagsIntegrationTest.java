package io.github.hectorvent.floci.services.autoscaling;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * DescribeTags over the tags CreateAutoScalingGroup and CreateOrUpdateTags store. Tags are read
 * back through DescribeTags itself, never trusted from the mutating call's response.
 */
@QuarkusTest
class AutoScalingDescribeTagsIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260501/us-east-1/autoscaling/aws4_request";
    private static final String ROOT = "DescribeTagsResponse.DescribeTagsResult.Tags.member";

    private static final String GROUP_A = "describe-tags-a";
    private static final String GROUP_B = "describe-tags-b";
    private static final String LC = "describe-tags-lc";

    private static boolean created;

    // Not @BeforeAll: a static hook runs before the Quarkus test server is listening.
    @BeforeEach
    void createGroups() {
        if (created) { return; }
        created = true;
        given()
                .formParam("Action", "CreateLaunchConfiguration")
                .formParam("LaunchConfigurationName", LC)
                .formParam("ImageId", "ami-12345678")
                .formParam("InstanceType", "t3.micro")
                .header("Authorization", AUTH)
            .when().post("/")
            .then().statusCode(200);
        createGroup(GROUP_A, "AsgId", "1", true);
        createGroup(GROUP_B, "AsgId", "2", false);
        given()
                .formParam("Action", "CreateOrUpdateTags")
                .formParam("Tags.member.1.ResourceId", GROUP_A)
                .formParam("Tags.member.1.ResourceType", "auto-scaling-group")
                .formParam("Tags.member.1.Key", "team")
                .formParam("Tags.member.1.Value", "blue")
                .formParam("Tags.member.1.PropagateAtLaunch", "false")
                .header("Authorization", AUTH)
            .when().post("/")
            .then().statusCode(200);
    }

    // The corpus call: terraform-aws-asg's get-desired-capacity.py finds its group by
    // describe_tags(Filters=[{Name: key, Values: [AsgId]}, {Name: value, Values: [<id>]}]).
    @Test
    void keyAndValueFiltersFindTheOneGroup() {
        XmlPath xml = describe()
                .formParam("Filters.member.1.Name", "key")
                .formParam("Filters.member.1.Values.member.1", "AsgId")
                .formParam("Filters.member.2.Name", "value")
                .formParam("Filters.member.2.Values.member.1", "1")
            .when().post("/")
            .then().statusCode(200).extract().xmlPath();

        assertEquals(List.of(GROUP_A), xml.getList(ROOT + ".ResourceId"));
        assertEquals("auto-scaling-group", xml.getString(ROOT + ".ResourceType"));
        assertEquals("AsgId", xml.getString(ROOT + ".Key"));
        assertEquals("1", xml.getString(ROOT + ".Value"));
        assertEquals("true", xml.getString(ROOT + ".PropagateAtLaunch"));
    }

    @Test
    void autoScalingGroupFilterReturnsEveryTagOfThatGroupSortedByKey() {
        XmlPath xml = describe()
                .formParam("Filters.member.1.Name", "auto-scaling-group")
                .formParam("Filters.member.1.Values.member.1", GROUP_A)
            .when().post("/")
            .then().statusCode(200).extract().xmlPath();

        assertEquals(List.of("AsgId", "team"), xml.getList(ROOT + ".Key"));
        assertEquals(List.of("true", "false"), xml.getList(ROOT + ".PropagateAtLaunch"));
    }

    // Values inside one filter are ORed; separate filters are ANDed.
    @Test
    void valuesWithinAFilterAreOredAndPropagateAtLaunchFilters() {
        XmlPath xml = describe()
                .formParam("Filters.member.1.Name", "auto-scaling-group")
                .formParam("Filters.member.1.Values.member.1", GROUP_A)
                .formParam("Filters.member.1.Values.member.2", GROUP_B)
                .formParam("Filters.member.2.Name", "propagate-at-launch")
                .formParam("Filters.member.2.Values.member.1", "false")
            .when().post("/")
            .then().statusCode(200).extract().xmlPath();

        assertEquals(List.of(GROUP_A, GROUP_B), xml.getList(ROOT + ".ResourceId"));
        assertEquals(List.of("team", "AsgId"), xml.getList(ROOT + ".Key"));
    }

    // Two filters with the same name are still two filters: ANDed, not merged into one ORed list.
    @Test
    void repeatedFilterNamesAreAndedNotMerged() {
        XmlPath xml = describe()
                .formParam("Filters.member.1.Name", "key")
                .formParam("Filters.member.1.Values.member.1", "AsgId")
                .formParam("Filters.member.1.Values.member.2", "team")
                .formParam("Filters.member.2.Name", "key")
                .formParam("Filters.member.2.Values.member.1", "team")
                .formParam("Filters.member.3.Name", "auto-scaling-group")
                .formParam("Filters.member.3.Values.member.1", GROUP_A)
            .when().post("/")
            .then().statusCode(200).extract().xmlPath();

        assertEquals(List.of("team"), xml.getList(ROOT + ".Key"));
    }

    @Test
    void maxRecordsPagesWithNextToken() {
        XmlPath first = describe()
                .formParam("Filters.member.1.Name", "auto-scaling-group")
                .formParam("Filters.member.1.Values.member.1", GROUP_A)
                .formParam("Filters.member.1.Values.member.2", GROUP_B)
                .formParam("MaxRecords", "2")
            .when().post("/")
            .then().statusCode(200).extract().xmlPath();
        assertEquals(2, first.getList(ROOT + ".Key").size());
        String token = first.getString("DescribeTagsResponse.DescribeTagsResult.NextToken");
        assertNotNull(token);

        XmlPath second = describe()
                .formParam("Filters.member.1.Name", "auto-scaling-group")
                .formParam("Filters.member.1.Values.member.1", GROUP_A)
                .formParam("Filters.member.1.Values.member.2", GROUP_B)
                .formParam("MaxRecords", "2")
                .formParam("NextToken", token)
            .when().post("/")
            .then().statusCode(200).extract().xmlPath();
        assertEquals(List.of(GROUP_B), second.getList(ROOT + ".ResourceId"));
        assertEquals("", second.getString("DescribeTagsResponse.DescribeTagsResult.NextToken"));
    }

    @Test
    void unknownFilterNameIsAValidationError() {
        describe()
                .formParam("Filters.member.1.Name", "tag-key")
                .formParam("Filters.member.1.Values.member.1", "AsgId")
            .when().post("/")
            .then().statusCode(400)
                .body(containsString("ValidationError"));
    }

    @Test
    void nonNumericMaxRecordsIsAValidationError() {
        describe()
                .formParam("MaxRecords", "abc")
            .when().post("/")
            .then().statusCode(400)
                .body(containsString("ValidationError"));
    }

    // Like every other member list in this handler, filters are read up to the first missing index.
    @Test
    void filtersAreReadUpToTheFirstMissingIndex() {
        XmlPath xml = describe()
                .formParam("Filters.member.1.Name", "auto-scaling-group")
                .formParam("Filters.member.1.Values.member.1", GROUP_B)
                .formParam("Filters.member.3.Name", "key")
                .formParam("Filters.member.3.Values.member.1", "no-such-key")
            .when().post("/")
            .then().statusCode(200).extract().xmlPath();

        assertEquals(List.of("AsgId"), xml.getList(ROOT + ".Key"));
    }

    // Filter values are case-sensitive (AWS Filter model), propagate-at-launch included.
    @Test
    void propagateAtLaunchFilterIsCaseSensitive() {
        XmlPath xml = describe()
                .formParam("Filters.member.1.Name", "auto-scaling-group")
                .formParam("Filters.member.1.Values.member.1", GROUP_A)
                .formParam("Filters.member.2.Name", "propagate-at-launch")
                .formParam("Filters.member.2.Values.member.1", "True")
            .when().post("/")
            .then().statusCode(200).extract().xmlPath();

        assertEquals(List.of(), xml.getList(ROOT + ".Key"));
    }

    // A tag deleted from an already-returned page must not shift the next page past an unread tag.
    @Test
    void nextTokenSurvivesATagDeletedBeforeTheCursor() {
        assertSecondPageAfterDeleting("describe-tags-cursor", "k1");
    }

    // The exact tag the NextToken encodes is deleted: the next page resumes at the first greater
    // key, rather than failing or restarting the listing.
    @Test
    void nextTokenSurvivesDeletingTheCursorTagItself() {
        assertSecondPageAfterDeleting("describe-tags-cursor-gone", "k2");
    }

    // Group has tags k1, k2, k3; the first page (MaxRecords=2) returns k1, k2, so the token encodes k2.
    private static void assertSecondPageAfterDeleting(String group, String deletedKey) {
        createGroup(group, "k1", "v", false);
        given()
                .formParam("Action", "CreateOrUpdateTags")
                .formParam("Tags.member.1.ResourceId", group)
                .formParam("Tags.member.1.ResourceType", "auto-scaling-group")
                .formParam("Tags.member.1.Key", "k2")
                .formParam("Tags.member.1.Value", "v")
                .formParam("Tags.member.2.ResourceId", group)
                .formParam("Tags.member.2.ResourceType", "auto-scaling-group")
                .formParam("Tags.member.2.Key", "k3")
                .formParam("Tags.member.2.Value", "v")
                .header("Authorization", AUTH)
            .when().post("/")
            .then().statusCode(200);

        XmlPath first = describe()
                .formParam("Filters.member.1.Name", "auto-scaling-group")
                .formParam("Filters.member.1.Values.member.1", group)
                .formParam("MaxRecords", "2")
            .when().post("/")
            .then().statusCode(200).extract().xmlPath();
        assertEquals(List.of("k1", "k2"), first.getList(ROOT + ".Key"));
        String token = first.getString("DescribeTagsResponse.DescribeTagsResult.NextToken");

        given()
                .formParam("Action", "DeleteTags")
                .formParam("Tags.member.1.ResourceId", group)
                .formParam("Tags.member.1.ResourceType", "auto-scaling-group")
                .formParam("Tags.member.1.Key", deletedKey)
                .header("Authorization", AUTH)
            .when().post("/")
            .then().statusCode(200);

        XmlPath second = describe()
                .formParam("Filters.member.1.Name", "auto-scaling-group")
                .formParam("Filters.member.1.Values.member.1", group)
                .formParam("MaxRecords", "2")
                .formParam("NextToken", token)
            .when().post("/")
            .then().statusCode(200).extract().xmlPath();
        assertEquals(List.of("k3"), second.getList(ROOT + ".Key"));
    }

    @Test
    void garbageNextTokenIsInvalidNextToken() {
        describe()
                .formParam("NextToken", "!!not-a-token!!")
            .when().post("/")
            .then().statusCode(400)
                .body(containsString("InvalidNextToken"));
    }

    // A token that decodes to a plausible (group, key) but lacks this list's namespace was not
    // minted here.
    @Test
    void tokenWithoutTheNamespaceIsInvalidNextToken() {
        assertInvalidNextToken(Base64.getUrlEncoder().encodeToString(
                (GROUP_A + "\0AsgId").getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertInvalidNextToken(String token) {
        describe()
                .formParam("NextToken", token)
            .when().post("/")
            .then().statusCode(400)
                .body(containsString("InvalidNextToken"));
    }

    private static RequestSpecification describe() {
        return given().formParam("Action", "DescribeTags").header("Authorization", AUTH);
    }

    private static void createGroup(String name, String key, String value, boolean propagate) {
        given()
                .formParam("Action", "CreateAutoScalingGroup")
                .formParam("AutoScalingGroupName", name)
                .formParam("LaunchConfigurationName", LC)
                .formParam("MinSize", "0")
                .formParam("MaxSize", "1")
                .formParam("DesiredCapacity", "0")
                .formParam("AvailabilityZones.member.1", "us-east-1a")
                .formParam("Tags.member.1.Key", key)
                .formParam("Tags.member.1.Value", value)
                .formParam("Tags.member.1.PropagateAtLaunch", String.valueOf(propagate))
                .header("Authorization", AUTH)
            .when().post("/")
            .then().statusCode(200);
    }
}
