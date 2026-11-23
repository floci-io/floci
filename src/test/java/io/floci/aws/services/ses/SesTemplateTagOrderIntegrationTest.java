package io.floci.aws.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.floci.aws.services.ses.SesV2Requests.AUTH;
import static io.floci.aws.services.ses.SesV2Requests.v2;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Where CreateEmailTemplate checks its tags, as probed on us-west-2: a malformed tag is refused before
 * the template name and content, a reserved {@code aws:} key only after them and as "Invalid tags".
 */
@QuarkusTest
class SesTemplateTagOrderIntegrationTest {

    private static final String SUBJECT_ONLY = "{\"Subject\": \"S\"}";
    private static final String VALID = "{\"Subject\": \"S\", \"Text\": \"t\"}";
    private static final String CHARSET = "Tags can only contain letters, numbers, spaces, and the following "
            + "special characters: _ . : / = + - @";
    private static final String KEY_TOO_LONG = "1 validation error detected: Value at 'tags.1.member.key' "
            + "failed to satisfy constraint: Member must have length less than or equal to 128";
    private static final String TOO_MANY = "Maximum of 50 user tags are allowed per resource, consider "
            + "reducing the number of tags in the request";

    private static String tag(String key) {
        return "{\"Key\": \"" + key + "\", \"Value\": \"v\"}";
    }

    static Stream<Arguments> refused() {
        String fiftyOne = IntStream.range(0, 51).mapToObj(i -> tag("k" + i)).collect(Collectors.joining(", "));
        return Stream.of(
                // a malformed tag wins over a subject-only content and over a blank name
                Arguments.of("tag-order", SUBJECT_ONLY, tag("k".repeat(129)), KEY_TOO_LONG),
                Arguments.of("tag-order", "{\"Text\": \"t\"}", tag("a!b"), CHARSET),
                Arguments.of("tag-order", SUBJECT_ONLY, fiftyOne, TOO_MANY),
                Arguments.of("tag-order", SUBJECT_ONLY, tag("dup") + ", " + tag("dup"),
                        "Cannot provide multiple tags with the same key"),
                Arguments.of("tag-order", SUBJECT_ONLY, tag(""), null),
                Arguments.of(" ", VALID, tag("k".repeat(129)), KEY_TOO_LONG),
                Arguments.of(" ", VALID, tag("a!b"), CHARSET),
                // the length is reported for a reserved key too
                Arguments.of("tag-order", VALID, tag("aws:" + "k".repeat(125)), KEY_TOO_LONG),
                // a reserved key waits for the content
                Arguments.of("tag-order", SUBJECT_ONLY, tag("aws:x"),
                        "Template must specify at least one of the parts."),
                Arguments.of("tag-order", VALID, tag("aws:x"), "Invalid tags"),
                Arguments.of("tag-order", VALID, tag("AWS:x"), "Invalid tags"));
    }

    @ParameterizedTest
    @MethodSource("refused")
    void create_refusesInTheOrderSesDoes(String name, String content, String tags, String message) {
        v2()
            .body(createBody(name, content, tags))
        .when()
            .post("/v2/email/templates")
        .then()
            .statusCode(400)
            .body("__type", equalTo("BadRequestException"))
            .body("message", equalTo(message));
    }

    @Test
    void create_refusedForAReservedKeyStoresNothing() {
        v2()
            .body(createBody("tag-order-reserved", VALID, tag("aws:x")))
        .when()
            .post("/v2/email/templates")
        .then()
            .statusCode(400)
            .body("message", equalTo("Invalid tags"));

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v2/email/templates/tag-order-reserved")
        .then()
            .statusCode(404);
    }

    private static String createBody(String name, String content, String tags) {
        return "{\"TemplateName\": \"" + name + "\", \"TemplateContent\": " + content
                + ", \"Tags\": [" + tags + "]}";
    }
}
