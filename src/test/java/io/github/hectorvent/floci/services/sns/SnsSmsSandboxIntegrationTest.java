package io.github.hectorvent.floci.services.sns;

import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SMS sandbox and opt-out operations. Floci answers as an account that has left the sandbox,
 * since it delivers SMS to any number, and a verification code is "texted" the way every SMS is
 * here: stored for {@code GET /_aws/sns} to read back.
 */
@QuarkusTest
class SnsSmsSandboxIntegrationTest {

    private static final Pattern CODE = Pattern.compile("[0-9]{6}");
    private static final String INVALID_PHONE_NUMBER =
            "Invalid parameter: PhoneNumber Reason: input incorrectly formatted";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void theAccountIsOutsideTheSandbox() {
        query("GetSMSSandboxAccountStatus", Map.of())
                .statusCode(200)
                .body("GetSMSSandboxAccountStatusResponse.GetSMSSandboxAccountStatusResult.IsInSandbox",
                        equalTo("false"));
        json("GetSMSSandboxAccountStatus", "{}")
                .statusCode(200)
                .body("IsInSandbox", equalTo(false));
    }

    @Test
    void aSandboxNumberIsVerifiedWithTheCodeTextedToIt() {
        String phoneNumber = addSandboxNumber();
        assertEquals("Pending", statusOf(phoneNumber));

        String code = codeTextedTo(phoneNumber);
        query("VerifySMSSandboxPhoneNumber", Map.of("PhoneNumber", phoneNumber, "OneTimePassword", anotherCode(code)))
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("VerificationException"))
                .body("ErrorResponse.Error.Status", equalTo("Pending"));
        assertEquals("Pending", statusOf(phoneNumber));

        query("VerifySMSSandboxPhoneNumber", Map.of("PhoneNumber", phoneNumber, "OneTimePassword", code))
                .statusCode(200);
        assertEquals("Verified", statusOf(phoneNumber));
    }

    @Test
    void aDeletedNumberIsGoneAndCannotBeDeletedTwice() {
        String phoneNumber = addSandboxNumber();

        query("DeleteSMSSandboxPhoneNumber", Map.of("PhoneNumber", phoneNumber)).statusCode(200);

        assertTrue(!allSandboxNumbers().containsKey(phoneNumber), phoneNumber + " is still listed");
        query("DeleteSMSSandboxPhoneNumber", Map.of("PhoneNumber", phoneNumber))
                .statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("ResourceNotFound"));
        query("VerifySMSSandboxPhoneNumber", Map.of("PhoneNumber", phoneNumber, "OneTimePassword", "123456"))
                .statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("ResourceNotFound"));
    }

    @Test
    void theListPagesThroughMaxResults() {
        List<String> added = List.of(addSandboxNumber(), addSandboxNumber(), addSandboxNumber());

        List<String> seen = new ArrayList<>();
        String nextToken = null;
        do {
            Map<String, String> params = new HashMap<>(Map.of("MaxResults", "1"));
            if (nextToken != null) {
                params.put("NextToken", nextToken);
            }
            XmlPath page = query("ListSMSSandboxPhoneNumbers", params).statusCode(200).extract().xmlPath();
            List<String> numbers = page.getList(
                    "ListSMSSandboxPhoneNumbersResponse.ListSMSSandboxPhoneNumbersResult.PhoneNumbers.member.PhoneNumber");
            assertTrue(numbers.size() <= 1, "a page held " + numbers);
            seen.addAll(numbers);
            nextToken = page.getString(
                    "ListSMSSandboxPhoneNumbersResponse.ListSMSSandboxPhoneNumbersResult.NextToken");
        } while (nextToken != null && !nextToken.isEmpty());

        assertTrue(seen.containsAll(added), "paging returned " + seen + ", missing some of " + added);
        query("ListSMSSandboxPhoneNumbers", Map.of("MaxResults", "101"))
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidParameter"));
    }

    @Test
    void aMalformedSandboxNumberIsRefused() {
        query("CreateSMSSandboxPhoneNumber", Map.of("PhoneNumber", "not-a-number"))
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidParameter"))
                .body("ErrorResponse.Error.Message", equalTo(INVALID_PHONE_NUMBER));
    }

    @Test
    void theJsonProtocolReachesTheSameSandbox() {
        String phoneNumber = randomPhoneNumber();
        cleanup.register(() -> query("DeleteSMSSandboxPhoneNumber", Map.of("PhoneNumber", phoneNumber)));
        json("CreateSMSSandboxPhoneNumber", "{\"PhoneNumber\": \"%s\", \"LanguageCode\": \"en-US\"}"
                .formatted(phoneNumber))
                .statusCode(200);

        String code = codeTextedTo(phoneNumber);
        json("VerifySMSSandboxPhoneNumber", "{\"PhoneNumber\": \"%s\", \"OneTimePassword\": \"%s\"}"
                .formatted(phoneNumber, anotherCode(code)))
                .statusCode(400)
                .body("__type", equalTo("VerificationException"))
                .body("Status", equalTo("Pending"));

        json("VerifySMSSandboxPhoneNumber", "{\"PhoneNumber\": \"%s\", \"OneTimePassword\": \"%s\"}"
                .formatted(phoneNumber, code))
                .statusCode(200);

        assertEquals("Verified", statusOf(phoneNumber));
        json("ListSMSSandboxPhoneNumbers", "{\"MaxResults\": 100}")
                .statusCode(200)
                .body("PhoneNumbers.PhoneNumber", hasItem(phoneNumber));
    }

    @Test
    void noNumberIsEverOptedOut() {
        query("CheckIfPhoneNumberIsOptedOut", Map.of("phoneNumber", "+4411111111"))
                .statusCode(200)
                .body("CheckIfPhoneNumberIsOptedOutResponse.CheckIfPhoneNumberIsOptedOutResult.isOptedOut",
                        equalTo("false"));
        List<String> optedOut = query("ListPhoneNumbersOptedOut", Map.of())
                .statusCode(200)
                .extract().xmlPath()
                .getList("ListPhoneNumbersOptedOutResponse.ListPhoneNumbersOptedOutResult.phoneNumbers.member");
        assertTrue(optedOut.isEmpty(), "opted out: " + optedOut);
        json("ListPhoneNumbersOptedOut", "{}")
                .statusCode(200)
                .body("phoneNumbers", empty());
    }

    @Test
    void optingInANumberThatIsNotOptedOutSucceeds() {
        query("OptInPhoneNumber", Map.of("phoneNumber", "+4411111111")).statusCode(200);
        json("OptInPhoneNumber", "{\"phoneNumber\": \"+4411111111\"}").statusCode(200);
    }

    @Test
    void aMalformedNumberIsRefusedByTheOptOutCalls() {
        query("OptInPhoneNumber", Map.of("phoneNumber", "invalid"))
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidParameter"))
                .body("ErrorResponse.Error.Message", equalTo(INVALID_PHONE_NUMBER));
        query("CheckIfPhoneNumberIsOptedOut", Map.of("phoneNumber", "invalid"))
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidParameter"));
    }

    private String addSandboxNumber() {
        String phoneNumber = randomPhoneNumber();
        cleanup.register(() -> query("DeleteSMSSandboxPhoneNumber", Map.of("PhoneNumber", phoneNumber)));
        query("CreateSMSSandboxPhoneNumber", Map.of("PhoneNumber", phoneNumber, "LanguageCode", "en-US"))
                .statusCode(200);
        return phoneNumber;
    }

    /** A number of its own for each test, so one test's sandbox entry and texts are not another's. */
    private static String randomPhoneNumber() {
        return String.format("+1555%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }

    private static String codeTextedTo(String phoneNumber) {
        List<String> texts = given()
                .queryParam("phone", phoneNumber)
                .when().get("/_aws/sns")
                .then().statusCode(200)
                .extract().jsonPath().getList("messages.Message");
        assertEquals(1, texts.size(), "expected one verification text to " + phoneNumber + ", got " + texts);
        Matcher code = CODE.matcher(texts.get(0));
        assertTrue(code.find(), "no six-digit code in " + texts.get(0));
        return code.group();
    }

    private static String statusOf(String phoneNumber) {
        String status = allSandboxNumbers().get(phoneNumber);
        assertTrue(status != null, phoneNumber + " is not listed");
        return status;
    }

    private static Map<String, String> allSandboxNumbers() {
        XmlPath page = query("ListSMSSandboxPhoneNumbers", Map.of("MaxResults", "100"))
                .statusCode(200).extract().xmlPath();
        String members = "ListSMSSandboxPhoneNumbersResponse.ListSMSSandboxPhoneNumbersResult.PhoneNumbers.member";
        List<String> numbers = page.getList(members + ".PhoneNumber");
        List<String> statuses = page.getList(members + ".Status");
        Map<String, String> byNumber = new HashMap<>();
        for (int i = 0; i < numbers.size(); i++) {
            byNumber.put(numbers.get(i), statuses.get(i));
        }
        return byNumber;
    }

    /** A well-formed one-time password that is not {@code code}. */
    private static String anotherCode(String code) {
        return String.format("%06d", (Integer.parseInt(code) + 1) % 1_000_000);
    }

    private static ValidatableResponse query(String action, Map<String, String> params) {
        RequestSpecification spec = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("Version", "2010-03-31");
        params.forEach(spec::formParam);
        return spec.when().post("/").then();
    }

    private static ValidatableResponse json(String action, String body) {
        return given()
                .contentType("application/x-amz-json-1.0")
                .header("X-Amz-Target", "SNS_20100331." + action)
                .body(body)
                .when().post("/").then();
    }
}
