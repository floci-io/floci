package io.floci.aws.services.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class AccountContactInformationIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger ACCOUNTS = new AtomicInteger();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void putThenGetReturnsTheContactAndItsVerificationStatus() {
        String account = newAccount();
        ObjectNode request = request();
        contactOf(request).put("CompanyName", "Example Corp");

        put(account, request).statusCode(200).body(equalTo(""));

        get(account, "{}").statusCode(200)
                .body("ContactInformation.FullName", equalTo("Jane Doe"))
                .body("ContactInformation.AddressLine1", equalTo("1 Main St"))
                .body("ContactInformation.City", equalTo("Seattle"))
                .body("ContactInformation.StateOrRegion", equalTo("WA"))
                .body("ContactInformation.PostalCode", equalTo("98101"))
                .body("ContactInformation.CountryCode", equalTo("US"))
                .body("ContactInformation.PhoneNumber", equalTo("+12065550100"))
                .body("ContactInformation.CompanyName", equalTo("Example Corp"))
                .body("ContactInformation", not(hasKey("AddressLine2")))
                .body("ContactInformation", not(hasKey("WebsiteUrl")))
                .body("VerificationStatus", equalTo("NOT_SUPPORTED"));
    }

    @Test
    void putReplacesTheStoredContact() {
        String account = newAccount();
        ObjectNode first = request();
        contactOf(first).put("CompanyName", "Example Corp");
        put(account, first).statusCode(200);

        ObjectNode second = request();
        contactOf(second).put("FullName", "John Roe");
        put(account, second).statusCode(200);

        get(account, "{}").statusCode(200)
                .body("ContactInformation.FullName", equalTo("John Roe"))
                .body("ContactInformation", not(hasKey("CompanyName")));
    }

    @Test
    void getBeforePutReturnsResourceNotFound() {
        get(newAccount(), "{}").statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void contactsAreIsolatedByCallerAccount() {
        String owner = newAccount();
        put(owner, request()).statusCode(200);

        get(owner, "{}").statusCode(200);
        get(newAccount(), "{}").statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void putWithoutContactInformationReturnsValidationException() {
        put(newAccount(), MAPPER.createObjectNode())
                .statusCode(400).body("__type", equalTo("ValidationException"));
    }

    @Test
    void putWithNonStructureContactInformationReturnsSerializationException() {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("ContactInformation", "Jane Doe");

        put(newAccount(), request).statusCode(400).body("__type", equalTo("SerializationException"));
    }

    @Test
    void nonObjectRequestBodyReturnsSerializationException() {
        String account = newAccount();
        put(account, request()).statusCode(200);

        get(account, "[]").statusCode(400).body("__type", equalTo("SerializationException"));
        post(account, "/putContactInformation", "[]")
                .statusCode(400).body("__type", equalTo("SerializationException"));
    }

    @Test
    void rejectedPutKeepsThePreviousContact() {
        String account = newAccount();
        put(account, request()).statusCode(200);
        ObjectNode invalid = request();
        contactOf(invalid).put("FullName", "John Roe");
        contactOf(invalid).put("PhoneNumber", "12065550100");

        put(account, invalid).statusCode(400).body("__type", equalTo("ValidationException"));

        get(account, "{}").statusCode(200).body("ContactInformation.FullName", equalTo("Jane Doe"));
    }

    @Test
    void putRequiresStateOrRegionForTheUnitedStates() {
        ObjectNode request = request();
        contactOf(request).remove("StateOrRegion");

        put(newAccount(), request).statusCode(400).body("__type", equalTo("ValidationException"));
    }

    @Test
    void nonStringAccountIdReturnsSerializationException() {
        String account = newAccount();
        ObjectNode request = request();
        request.put("AccountId", 123456789012L);

        put(account, request).statusCode(400).body("__type", equalTo("SerializationException"));
        get(account, "{\"AccountId\":123456789012}")
                .statusCode(400).body("__type", equalTo("SerializationException"));
    }

    @Test
    void anotherAccountIsRefusedOutsideAnOrganization() {
        String account = newAccount();
        ObjectNode request = request();
        request.put("AccountId", "111122223333");

        put(account, request).statusCode(403).body("__type", equalTo("AccessDeniedException"));
        get(account, "{\"AccountId\":\"111122223333\"}")
                .statusCode(403).body("__type", equalTo("AccessDeniedException"));
    }

    /**
     * Contact information cannot be deleted, so every test acts on an account of its own. A 12-digit access key
     * ID is the account ID a request acts as.
     */
    private static String newAccount() {
        return String.format("7310008259%02d", ACCOUNTS.incrementAndGet());
    }

    private static ObjectNode request() {
        ObjectNode contact = MAPPER.createObjectNode();
        contact.put("FullName", "Jane Doe");
        contact.put("AddressLine1", "1 Main St");
        contact.put("City", "Seattle");
        contact.put("StateOrRegion", "WA");
        contact.put("PostalCode", "98101");
        contact.put("CountryCode", "US");
        contact.put("PhoneNumber", "+12065550100");
        ObjectNode request = MAPPER.createObjectNode();
        request.set("ContactInformation", contact);
        return request;
    }

    private static ObjectNode contactOf(ObjectNode request) {
        return (ObjectNode) request.get("ContactInformation");
    }

    private static ValidatableResponse put(String account, ObjectNode request) {
        return post(account, "/putContactInformation", request.toString());
    }

    private static ValidatableResponse get(String account, String body) {
        return post(account, "/getContactInformation", body);
    }

    private static ValidatableResponse post(String account, String path, String body) {
        return given().contentType("application/json").header("Authorization", authorization(account))
                .body(body).post(path).then();
    }

    private static String authorization(String account) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20261114/us-east-1/account/aws4_request";
    }
}
