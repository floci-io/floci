package io.github.hectorvent.floci.services.bedrockagentcore;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;

import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;

/**
 * {@code InvokeHarness} streams an event-stream response.
 *
 * <p>The body is binary event-stream framing rather than JSON, but the event names and the JSON
 * payload text still appear as UTF-8 substrings between the framing bytes, which is how the
 * bedrock-runtime streaming tests assert on the same encoding.
 */
@QuarkusTest
class BedrockAgentCoreHarnessIntegrationTest {

    /** Matches the modelled ARN pattern: a name then a ten character suffix. */
    private static final String ARN =
            "arn:aws:bedrock-agentcore:us-east-1:000000000000:harness/myHarness-abc1234567";
    /** A runtime session id has a documented minimum of 33 characters. */
    private static final String SESSION = "session-0123456789abcdef0123456789abcdef";
    private static final String SESSION_HEADER = "X-Amzn-Bedrock-AgentCore-Runtime-Session-Id";

    /**
     * Rebuilds the assistant text from the contentBlockDelta frames, the way a consumer does.
     * Asserting on the whole phrase directly would fail: the reply is chunked, so it is split
     * across frames and never appears contiguously in the body.
     */
    private static String assistantText(String eventStreamBody) {
        Matcher matcher = Pattern.compile("\\{\"text\":\"(.*?)\"\\}").matcher(eventStreamBody);
        StringBuilder text = new StringBuilder();
        while (matcher.find()) {
            text.append(matcher.group(1));
        }
        return text.toString();
    }

    /**
     * Posts with the bindings the SDK uses: {@code harnessArn} is a query parameter and
     * {@code runtimeSessionId} a header. Only messages/model/tools travel in the body.
     */
    private static RequestSpecification request(String harnessArn, String sessionId) {
        RequestSpecification spec = given().contentType("application/json");
        if (harnessArn != null) {
            spec = spec.queryParam("harnessArn", harnessArn);
        }
        if (sessionId != null) {
            spec = spec.header(SESSION_HEADER, sessionId);
        }
        return spec;
    }

    private static String invoke(String harnessArn, String sessionId, String body, int expectedStatus) {
        return request(harnessArn, sessionId).body(body)
                .when().post("/harnesses/invoke")
                .then().statusCode(expectedStatus)
                .extract().body().asString();
    }

    @Test
    void theReplyEchoesTheLastUserMessage() {
        String body = request(ARN, SESSION)
                .body("""
                        {"messages": [{"role": "user", "content": [{"text": "what is EIDR?"}]}]}
                        """)
                .when().post("/harnesses/invoke")
                .then().statusCode(200)
                .header("Content-Type", containsString("application/vnd.amazon.eventstream"))
                .extract().body().asString();

        assertThat(assistantText(body), containsString("what is EIDR?"));
    }

    @Test
    void theStreamCarriesTheFullEventSequenceInOrder() {
        String body = request(ARN, SESSION)
                .body("""
                        {"messages": [{"role": "user", "content": [{"text": "hello"}]}]}
                        """)
                .when().post("/harnesses/invoke")
                .then().statusCode(200)
                .extract().body().asString();

        assertThat(body, containsString("messageStart"));
        assertThat(body, containsString("assistant"));
        assertThat(body, containsString("contentBlockStart"));
        assertThat(body, containsString("contentBlockDelta"));
        assertThat(body, containsString("contentBlockStop"));
        assertThat(body, containsString("messageStop"));
        assertThat(body, containsString("end_turn"));
        assertThat(body, containsString("metadata"));

        // Order matters to a consumer building a message from the stream.
        assertThat(body.indexOf("messageStart"), lessThan(body.indexOf("contentBlockDelta")));
        assertThat(body.indexOf("contentBlockDelta"), lessThan(body.indexOf("messageStop")));
        assertThat(body.indexOf("messageStop"), lessThan(body.indexOf("metadata")));
    }

    @Test
    void onlyTheLastUserMessageIsEchoed() {
        String body = request(ARN, SESSION)
                .body("""
                        {"messages": [
                           {"role": "user", "content": [{"text": "first question"}]},
                           {"role": "assistant", "content": [{"text": "an answer"}]},
                           {"role": "user", "content": [{"text": "second question"}]}]}
                        """)
                .when().post("/harnesses/invoke")
                .then().statusCode(200)
                .extract().body().asString();

        assertThat(assistantText(body), containsString("second question"));
        assertThat(assistantText(body), not(containsString("first question")));
    }

    @Test
    void anEmptyMessagesArrayStillStreamsAWellFormedResponse() {
        // Legitimate: a caller whose memory already holds the current turn sends no messages.
        String body = invoke(ARN, SESSION, "{\"messages\": []}", 200);

        assertThat(body, containsString("messageStart"));
        assertThat(body, containsString("messageStop"));
        assertThat(assistantText(body), containsString("No user message was supplied."));
    }

    @Test
    void aRequestWithoutAHarnessArnIsRejected() {
        String body = invoke(null, SESSION, "{}", 400);
        assertThat(body, containsString("ValidationException"));
        assertThat(body, containsString("harnessArn"));
    }

    @Test
    void aRequestWithoutARuntimeSessionIdIsRejected() {
        String body = invoke(ARN, null, "{}", 400);
        assertThat(body, containsString("ValidationException"));
        assertThat(body, containsString("runtimeSessionId"));
    }

    @Test
    void anUnknownHarnessArnIsAccepted() {
        // The emulator models no harness resource, so there is nothing to resolve an ARN against.
        // Well formed but naming no harness: the shape is checked, the resource is not resolved.
        request("arn:aws:bedrock-agentcore:us-east-1:000000000000:harness/unknownOne-zzzz999999", SESSION)
                .body("""
                        {"messages": [{"role": "user", "content": [{"text": "hi"}]}]}
                        """)
                .when().post("/harnesses/invoke")
                .then().statusCode(200);
    }

    @Test
    void anOmittedMessagesMemberIsRejected() {
        // messages is required. An empty array is a legitimate request; omitting the member is not,
        // so the two must not collapse into the same canned reply.
        String body = invoke(ARN, SESSION, "{}", 400);
        assertThat(body, containsString("messages"));
    }

    @Test
    void aNonArrayMessagesMemberIsRejected() {
        invoke(ARN, SESSION, "{\"messages\": \"hello\"}", 400);
    }

    @Test
    void aRuntimeSessionIdShorterThanTheMinimumIsRejected() {
        String body = invoke(ARN, "short", "{\"messages\": []}", 400);
        assertThat(body, containsString("runtimeSessionId"));
    }

    @Test
    void aMalformedHarnessArnIsRejected() {
        String body = invoke("anything", SESSION, "{\"messages\": []}", 400);
        assertThat(body, containsString("harness ARN"));
    }

    @Test
    void aMalformedArnIsReportedAheadOfAMissingRuntimeSessionId() {
        String body = invoke("anything", null, "{\"messages\": []}", 400);
        assertThat(body, containsString("harness ARN"));
        assertThat(body, not(containsString("runtimeSessionId")));
    }

    @Test
    void anArnWithoutTheTenCharacterSuffixIsRejected() {
        invoke("arn:aws:bedrock-agentcore:us-east-1:000000000000:harness/h-1", SESSION,
                "{\"messages\": []}", 400);
    }
}
