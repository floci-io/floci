package io.floci.aws.testutil;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.restassured.specification.RequestSpecification;

import static io.restassured.RestAssured.given;

/**
 * Reads Floci's SES inspection mailbox, {@code GET /_aws/ses}, for one recipient. The endpoint's
 * {@code email} parameter filters by message source, as on LocalStack, so the messages sent to an
 * address are picked out here. Pass an account to read mail stored under that account: a
 * credential-free read only sees the default account.
 */
public final class SesMailbox {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SesMailbox() {
    }

    /** The captured messages whose To addresses include {@code recipient}, oldest first. */
    public static ArrayNode messagesTo(String recipient) {
        return messages(given(), recipient);
    }

    /**
     * The captured messages in {@code accountId} whose To addresses include {@code recipient},
     * oldest first. The account is the SigV4 access key, which is how the account filter selects
     * the mailbox.
     */
    public static ArrayNode messagesTo(String accountId, String recipient) {
        return messages(given().header("Authorization", authorization(accountId)), recipient);
    }

    private static ArrayNode messages(RequestSpecification request, String recipient) {
        String body = request.when().get("/_aws/ses").then().statusCode(200).extract().asString();
        JsonNode messages;
        try {
            messages = MAPPER.readTree(body).path("messages");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("GET /_aws/ses did not return JSON: " + body, e);
        }
        ArrayNode matching = MAPPER.createArrayNode();
        for (JsonNode message : messages) {
            for (JsonNode to : message.path("Destination").path("ToAddresses")) {
                if (recipient.equals(to.asText())) {
                    matching.add(message);
                    break;
                }
            }
        }
        return matching;
    }

    private static String authorization(String accountId) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId + "/20260905/us-east-1/email/aws4_request";
    }
}
