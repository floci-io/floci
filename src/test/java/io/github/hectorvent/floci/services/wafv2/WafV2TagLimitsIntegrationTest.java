package io.github.hectorvent.floci.services.wafv2;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Validates that WAFv2 tag mutations enforce the AWS tag contract: at most 50 tags per
 * resource, keys of 1-128 characters and values of up to 256 characters, restricted to the
 * documented character set (issue #4035).
 */
@QuarkusTest
class WafV2TagLimitsIntegrationTest {

    private static final String CT = "application/x-amz-json-1.1";
    private static final String PREFIX = "AWSWAF_20190729.";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response call(String action, String body) {
        return given().contentType(CT).header("X-Amz-Target", PREFIX + action)
                .body(body).when().post("/");
    }

    private static String createIpSet(String name) {
        Response created = call("CreateIPSet",
                "{\"Name\":\"" + name + "\",\"Scope\":\"REGIONAL\",\"IPAddressVersion\":\"IPV4\","
                        + "\"Addresses\":[]}");
        created.then().statusCode(200).body("Summary.ARN", notNullValue());
        return created.jsonPath().getString("Summary.ARN");
    }

    private static String tags(int from, int toExclusive) {
        return IntStream.range(from, toExclusive)
                .mapToObj(i -> "{\"Key\":\"key-" + i + "\",\"Value\":\"value-" + i + "\"}")
                .collect(Collectors.joining(","));
    }

    private static String tagRequest(String arn, String tagList) {
        return "{\"ResourceARN\":\"" + arn + "\",\"Tags\":[" + tagList + "]}";
    }

    @Test
    void tagResourceAllowsUpToFiftyTags() {
        String arn = createIpSet("tag-limits-fifty");

        call("TagResource", tagRequest(arn, tags(0, 50))).then().statusCode(200);

        call("ListTagsForResource", "{\"ResourceARN\":\"" + arn + "\"}")
                .then().statusCode(200)
                .body("TagInfoForResource.TagList", hasSize(50));
    }

    @Test
    void tagResourceRejectsMoreThanFiftyTagsInOneRequest() {
        String arn = createIpSet("tag-limits-fifty-one");

        call("TagResource", tagRequest(arn, tags(0, 51)))
                .then().statusCode(400)
                .body("__type", equalTo("WAFLimitsExceededException"));

        call("ListTagsForResource", "{\"ResourceARN\":\"" + arn + "\"}")
                .then().statusCode(200)
                .body("TagInfoForResource.TagList", hasSize(0));
    }

    @Test
    void tagResourceRejectsAccumulatingPastFiftyTags() {
        String arn = createIpSet("tag-limits-accumulate");

        call("TagResource", tagRequest(arn, tags(0, 50))).then().statusCode(200);

        call("TagResource", tagRequest(arn, tags(50, 51)))
                .then().statusCode(400)
                .body("__type", equalTo("WAFLimitsExceededException"));

        call("ListTagsForResource", "{\"ResourceARN\":\"" + arn + "\"}")
                .then().statusCode(200)
                .body("TagInfoForResource.TagList", hasSize(50));
    }

    @Test
    void tagResourceOverwritingExistingKeysDoesNotCountTwice() {
        String arn = createIpSet("tag-limits-overwrite");

        call("TagResource", tagRequest(arn, tags(0, 50))).then().statusCode(200);

        call("TagResource", tagRequest(arn, "{\"Key\":\"key-0\",\"Value\":\"updated\"}"))
                .then().statusCode(200);

        call("ListTagsForResource", "{\"ResourceARN\":\"" + arn + "\"}")
                .then().statusCode(200)
                .body("TagInfoForResource.TagList", hasSize(50))
                .body("TagInfoForResource.TagList.find { it.Key == 'key-0' }.Value", equalTo("updated"));
    }

    @Test
    void tagResourceRejectsKeyLongerThan128Characters() {
        String arn = createIpSet("tag-limits-long-key");
        String longKey = "k".repeat(129);

        call("TagResource", tagRequest(arn, "{\"Key\":\"" + longKey + "\",\"Value\":\"v\"}"))
                .then().statusCode(400)
                .body("__type", equalTo("WAFInvalidParameterException"))
                .body("Field", equalTo("TAGS"))
                .body("Parameter", equalTo(longKey))
                .body("Reason", equalTo("INVALID_TAG_KEY"));

        call("TagResource", tagRequest(arn, "{\"Key\":\"" + "k".repeat(128) + "\",\"Value\":\"v\"}"))
                .then().statusCode(200);
    }

    @Test
    void tagResourceRejectsEmptyKey() {
        String arn = createIpSet("tag-limits-empty-key");

        call("TagResource", tagRequest(arn, "{\"Key\":\"\",\"Value\":\"v\"}"))
                .then().statusCode(400)
                .body("__type", equalTo("WAFInvalidParameterException"))
                .body("Field", equalTo("TAGS"))
                .body("Reason", equalTo("INVALID_TAG_KEY"));
    }

    @Test
    void tagResourceRejectsKeyWithDisallowedCharacters() {
        String arn = createIpSet("tag-limits-bad-key");

        call("TagResource", tagRequest(arn, "{\"Key\":\"bad!key\",\"Value\":\"v\"}"))
                .then().statusCode(400)
                .body("__type", equalTo("WAFInvalidParameterException"))
                .body("Field", equalTo("TAGS"))
                .body("Parameter", equalTo("bad!key"))
                .body("Reason", equalTo("INVALID_TAG_KEY"));
    }

    @Test
    void tagResourceRejectsValueLongerThan256Characters() {
        String arn = createIpSet("tag-limits-long-value");

        call("TagResource", tagRequest(arn, "{\"Key\":\"k\",\"Value\":\"" + "v".repeat(257) + "\"}"))
                .then().statusCode(400)
                .body("__type", equalTo("WAFInvalidParameterException"))
                .body("Field", equalTo("TAGS"))
                .body("Parameter", equalTo("k"))
                .body("Reason", equalTo("ILLEGAL_ARGUMENT"));

        call("TagResource", tagRequest(arn, "{\"Key\":\"k\",\"Value\":\"" + "v".repeat(256) + "\"}"))
                .then().statusCode(200);

        call("TagResource", tagRequest(arn, "{\"Key\":\"empty\",\"Value\":\"\"}"))
                .then().statusCode(200);
    }

    @Test
    void untagResourceRejectsInvalidKeys() {
        String arn = createIpSet("tag-limits-untag");

        call("UntagResource", "{\"ResourceARN\":\"" + arn + "\",\"TagKeys\":[\"" + "k".repeat(129) + "\"]}")
                .then().statusCode(400)
                .body("__type", equalTo("WAFInvalidParameterException"))
                .body("Field", equalTo("TAG_KEYS"))
                .body("Reason", equalTo("INVALID_TAG_KEY"));

        call("UntagResource", "{\"ResourceARN\":\"" + arn + "\",\"TagKeys\":[\"\"]}")
                .then().statusCode(400)
                .body("__type", equalTo("WAFInvalidParameterException"))
                .body("Field", equalTo("TAG_KEYS"));

        call("UntagResource", "{\"ResourceARN\":\"" + arn + "\",\"TagKeys\":[\"absent\"]}")
                .then().statusCode(200);
    }

    @Test
    void createRejectsMoreThanFiftyTags() {
        call("CreateIPSet",
                "{\"Name\":\"tag-limits-create\",\"Scope\":\"REGIONAL\",\"IPAddressVersion\":\"IPV4\","
                        + "\"Addresses\":[],\"Tags\":[" + tags(0, 51) + "]}")
                .then().statusCode(400)
                .body("__type", equalTo("WAFLimitsExceededException"));

        call("CreateIPSet",
                "{\"Name\":\"tag-limits-create\",\"Scope\":\"REGIONAL\",\"IPAddressVersion\":\"IPV4\","
                        + "\"Addresses\":[],\"Tags\":[{\"Key\":\"bad!key\",\"Value\":\"v\"}]}")
                .then().statusCode(400)
                .body("__type", equalTo("WAFInvalidParameterException"))
                .body("Field", equalTo("TAGS"));

        call("CreateIPSet",
                "{\"Name\":\"tag-limits-create\",\"Scope\":\"REGIONAL\",\"IPAddressVersion\":\"IPV4\","
                        + "\"Addresses\":[],\"Tags\":[" + tags(0, 50) + "]}")
                .then().statusCode(200)
                .body("Summary.Id", notNullValue());
    }
}
