package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.ses.SesService;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@QuarkusTest
class CloudFormationSesEmailIdentityIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261001/us-east-1/cloudformation/aws4_request";
    private static final String SES_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261001/us-east-1/ses/aws4_request";
    private String stack;

    @InjectSpy
    SesService sesService;

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @AfterEach
    void cleanUp() {
        if (stack != null) {
            cfn("DeleteStack", null).then().statusCode(200);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertEquals(400, cfn("DescribeStacks", null).statusCode()));
        }
    }

    @Test
    void domainIdentityExposesRealDkimDnsAttributesAndReconcilesUpdates() throws Exception {
        String first = "ses-" + Long.toString(System.nanoTime(), 36) + ".example.com";
        stack = "cfn-ses-" + Long.toString(System.nanoTime(), 36);
        cfn("CreateStack", template(first, false)).then().statusCode(200);
        String created = awaitStatus("CREATE_COMPLETE");
        Map<String, String> outputs = XmlParser.extractPairs(created, "Outputs", "OutputKey", "OutputValue");
        assertEquals(first, outputs.get("IdentityRef"));
        Response identity = sesIdentity(first);
        assertEquals(200, identity.statusCode(), identity.asString());
        List<String> tokens = identity.jsonPath().getList("DkimAttributes.Tokens", String.class);
        assertEquals(3, tokens.size());
        for (int index = 1; index <= 3; index++) {
            String token = tokens.get(index - 1);
            assertEquals(token + "._domainkey." + first, outputs.get("DkimName" + index));
            assertEquals(token + ".dkim.amazonses.com", outputs.get("DkimValue" + index));
        }

        cfn("UpdateStack", template(first, true)).then().statusCode(200);
        assertEquals(first, XmlParser.extractPairs(awaitStatus("UPDATE_COMPLETE"),
                "Outputs", "OutputKey", "OutputValue").get("IdentityRef"));
        identity = sesIdentity(first);
        assertEquals(false, identity.jsonPath().getBoolean("FeedbackForwardingStatus"));
        assertEquals(false, identity.jsonPath().getBoolean("DkimAttributes.SigningEnabled"));
        assertEquals("mail." + first, identity.jsonPath().getString("MailFromAttributes.MailFromDomain"));
        assertEquals("REJECT_MESSAGE", identity.jsonPath().getString("MailFromAttributes.BehaviorOnMxFailure"));
        assertEquals("new", identity.jsonPath().getString("Tags.find { it.Key == 'purpose' }.Value"));
        assertEquals("RSA_1024_BIT", identity.jsonPath().getString("DkimAttributes.NextSigningKeyLength"));

        ObjectNode invalid = (ObjectNode) MAPPER.readTree(template(first, true));
        invalid.withObject("/Resources/Identity/Properties/DkimSigningAttributes")
                .put("NextSigningKeyLength", "INVALID");
        cfn("UpdateStack", invalid.toString()).then().statusCode(200);
        awaitStatus("UPDATE_ROLLBACK_COMPLETE");
        identity = sesIdentity(first);
        assertEquals("RSA_1024_BIT", identity.jsonPath().getString("DkimAttributes.NextSigningKeyLength"));
        assertEquals(false, identity.jsonPath().getBoolean("DkimAttributes.SigningEnabled"));
        assertEquals("new", identity.jsonPath().getString("Tags.find { it.Key == 'purpose' }.Value"));

        cfn("DeleteStack", null).then().statusCode(200);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertEquals(404, sesIdentity(first).statusCode()));
        stack = null;
    }

    @Test
    void emailAddressIdentityHasNoDomainDkimRecordsAndDeletesWithStack() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String address = "ses-" + suffix + "@unregistered-" + suffix + ".example.com";
        stack = "cfn-ses-" + suffix;
        cfn("CreateStack", template(address, false)).then().statusCode(200);
        String created = awaitStatus("CREATE_COMPLETE");
        Map<String, String> outputs = XmlParser.extractPairs(created, "Outputs", "OutputKey", "OutputValue");
        assertEquals(address, outputs.get("IdentityRef"));
        for (int index = 1; index <= 3; index++) {
            assertEquals("", outputs.get("DkimName" + index));
            assertEquals("", outputs.get("DkimValue" + index));
        }

        Response identity = sesIdentity(address);
        assertEquals(200, identity.statusCode(), identity.asString());
        assertEquals("EMAIL_ADDRESS", identity.jsonPath().getString("IdentityType"));
        assertEquals(List.of(), identity.jsonPath().getList("DkimAttributes.Tokens", String.class));

        cfn("DeleteStack", null).then().statusCode(200);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertEquals(404, sesIdentity(address).statusCode()));
        stack = null;
    }

    @Test
    void changingIdentityReplacesBackingSesResource() throws Exception {
        String first = "ses-" + Long.toString(System.nanoTime(), 36) + ".example.com";
        String second = "other-" + first;
        stack = "cfn-ses-" + Long.toString(System.nanoTime(), 36);
        cfn("CreateStack", template(first, false)).then().statusCode(200);
        awaitStatus("CREATE_COMPLETE");

        cfn("UpdateStack", template(second, false)).then().statusCode(200);
        String updated = awaitStatus("UPDATE_COMPLETE");

        assertEquals(second, XmlParser.extractPairs(updated, "Outputs", "OutputKey", "OutputValue")
                .get("IdentityRef"));
        assertEquals(404, sesIdentity(first).statusCode());
        assertEquals(200, sesIdentity(second).statusCode());
    }

    @Test
    void failedLaterResourceRestoresIdentitySettingsAndTags() throws Exception {
        String identityName = "ses-" + Long.toString(System.nanoTime(), 36) + ".example.com";
        stack = "cfn-ses-" + Long.toString(System.nanoTime(), 36);
        cfn("CreateStack", template(identityName, false)).then().statusCode(200);
        awaitStatus("CREATE_COMPLETE");
        List<String> originalTokens = sesIdentity(identityName).jsonPath()
                .getList("DkimAttributes.Tokens", String.class);

        ObjectNode attempted = (ObjectNode) MAPPER.readTree(template(identityName, true));
        attempted.withObject("/Resources").set("BadSecret", MAPPER.valueToTree(Map.of(
                "Type", "AWS::SecretsManager::Secret", "DependsOn", "Identity",
                "Properties", Map.of("SecretString", "explicit",
                        "GenerateSecretString", Map.of("PasswordLength", 32)))));
        cfn("UpdateStack", attempted.toString()).then().statusCode(200);
        awaitStatus("UPDATE_ROLLBACK_COMPLETE");

        Response identity = sesIdentity(identityName);
        assertEquals(200, identity.statusCode(), identity.asString());
        assertEquals(true, identity.jsonPath().getBoolean("FeedbackForwardingStatus"));
        assertEquals(true, identity.jsonPath().getBoolean("DkimAttributes.SigningEnabled"));
        assertEquals("RSA_2048_BIT", identity.jsonPath().getString("DkimAttributes.NextSigningKeyLength"));
        assertEquals(originalTokens, identity.jsonPath().getList("DkimAttributes.Tokens", String.class));
        assertEquals(null, identity.jsonPath().getString("MailFromAttributes.MailFromDomain"));
        assertEquals("old", identity.jsonPath().getString("Tags.find { it.Key == 'purpose' }.Value"));
        String restored = cfn("DescribeStacks", null).then().statusCode(200).extract().asString();
        assertEquals(originalTokens.getFirst() + ".dkim.amazonses.com",
                XmlParser.extractPairs(restored, "Outputs", "OutputKey", "OutputValue").get("DkimValue1"));
    }

    @Test
    void failedPostCreateCleanupIsRetriedByStackRollback() throws Exception {
        String identityName = "ses-" + Long.toString(System.nanoTime(), 36) + ".example.com";
        String mailFromDomain = "mail." + identityName;
        stack = "cfn-ses-" + Long.toString(System.nanoTime(), 36);
        ObjectNode attempted = (ObjectNode) MAPPER.readTree(template(identityName, false));
        attempted.withObject("/Resources/Identity/Properties").set("MailFromAttributes",
                MAPPER.valueToTree(Map.of("MailFromDomain", mailFromDomain)));
        doThrow(new AwsException("BadRequestException", "MAIL FROM rejected", 400))
                .when(sesService).setEmailIdentityMailFromAttributes(identityName, mailFromDomain,
                        "UseDefaultValue", "us-east-1");
        doThrow(new AwsException("ServiceUnavailableException", "temporary delete failure", 503))
                .doCallRealMethod().when(sesService).deleteIdentity(identityName, "us-east-1");

        try {
            cfn("CreateStack", attempted.toString()).then().statusCode(200);
            awaitStatus("ROLLBACK_COMPLETE");
            assertEquals(404, sesIdentity(identityName).statusCode(),
                    "Stack rollback must delete an identity left by failed post-create cleanup");
            verify(sesService, times(2)).deleteIdentity(identityName, "us-east-1");
        } finally {
            doCallRealMethod().when(sesService).setEmailIdentityMailFromAttributes(identityName, mailFromDomain,
                    "UseDefaultValue", "us-east-1");
            doCallRealMethod().when(sesService).deleteIdentity(identityName, "us-east-1");
        }
    }

    @Test
    void failedReplacementCleanupSurvivesRejectedUpdatesUntilSuccessfulUpdate() throws Exception {
        String original = "ses-" + Long.toString(System.nanoTime(), 36) + ".example.com";
        String replacement = "replacement-" + original;
        String mailFromDomain = "mail." + replacement;
        stack = "cfn-ses-" + Long.toString(System.nanoTime(), 36);
        cfn("CreateStack", template(original, false)).then().statusCode(200);
        awaitStatus("CREATE_COMPLETE");
        List<String> originalTokens = sesIdentity(original).jsonPath()
                .getList("DkimAttributes.Tokens", String.class);
        doThrow(new AwsException("BadRequestException", "MAIL FROM rejected", 400))
                .when(sesService).setEmailIdentityMailFromAttributes(replacement, mailFromDomain,
                        "RejectMessage", "us-east-1");
        doThrow(new AwsException("ServiceUnavailableException", "temporary delete failure", 503))
                .doCallRealMethod().when(sesService).deleteIdentity(replacement, "us-east-1");

        try {
            cfn("UpdateStack", template(replacement, true)).then().statusCode(200);
            String rolledBack = awaitStatus("UPDATE_ROLLBACK_COMPLETE");
            assertEquals(original, XmlParser.extractPairs(rolledBack,
                    "Outputs", "OutputKey", "OutputValue").get("IdentityRef"));
            assertEquals(200, sesIdentity(replacement).statusCode(),
                    "The failed cleanup must retain the replacement for a later retry");
            Response identity = sesIdentity(original);
            assertEquals(200, identity.statusCode(), identity.asString());
            assertEquals(originalTokens, identity.jsonPath().getList("DkimAttributes.Tokens", String.class));
            assertEquals("old", identity.jsonPath().getString("Tags.find { it.Key == 'purpose' }.Value"));

            ObjectNode rejected = (ObjectNode) MAPPER.readTree(template(original, false));
            rejected.withObject("/Resources/Identity/Properties").set("DkimSigningAttributes",
                    MAPPER.valueToTree(Map.of("NextSigningKeyLength", "INVALID")));
            cfn("UpdateStack", rejected.toString()).then().statusCode(200);
            awaitStatus("UPDATE_ROLLBACK_COMPLETE");
            assertEquals(originalTokens,
                    sesIdentity(original).jsonPath().getList("DkimAttributes.Tokens", String.class));
            assertEquals(200, sesIdentity(replacement).statusCode());
            verify(sesService, times(1)).deleteIdentity(replacement, "us-east-1");

            cfn("UpdateStack", template(original, true)).then().statusCode(200);
            awaitStatus("UPDATE_COMPLETE");
            assertEquals(404, sesIdentity(replacement).statusCode(),
                    "A committed update must retry cleanup of the earlier failed replacement");
            assertEquals("new", sesIdentity(original).jsonPath()
                    .getString("Tags.find { it.Key == 'purpose' }.Value"));
            verify(sesService, times(2)).deleteIdentity(replacement, "us-east-1");

            cfn("DeleteStack", null).then().statusCode(200);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertEquals(404, sesIdentity(original).statusCode()));
            stack = null;
        } finally {
            doCallRealMethod().when(sesService).setEmailIdentityMailFromAttributes(replacement, mailFromDomain,
                    "RejectMessage", "us-east-1");
            doCallRealMethod().when(sesService).deleteIdentity(replacement, "us-east-1");
            given().header("Authorization", SES_AUTH)
                    .delete("/v2/email/identities/{identity}", replacement).then().statusCode(anyOf(is(200), is(404)));
        }
    }

    @Test
    void stackDeletionRetriesCleanupOfFailedReplacement() throws Exception {
        String original = "ses-" + Long.toString(System.nanoTime(), 36) + ".example.com";
        String replacement = "replacement-" + original;
        stack = "cfn-ses-" + Long.toString(System.nanoTime(), 36);
        cfn("CreateStack", template(original, false)).then().statusCode(200);
        awaitStatus("CREATE_COMPLETE");
        doThrow(new AwsException("ServiceUnavailableException", "temporary read failure", 503))
                .when(sesService).getEmailIdentity(replacement, "us-east-1");
        doThrow(new AwsException("ServiceUnavailableException", "temporary delete failure", 503))
                .doCallRealMethod().when(sesService).deleteIdentity(replacement, "us-east-1");

        try {
            cfn("UpdateStack", template(replacement, false)).then().statusCode(200);
            awaitStatus("UPDATE_ROLLBACK_COMPLETE");
            assertEquals(200, sesIdentity(original).statusCode());
            assertEquals(200, sesIdentity(replacement).statusCode());

            cfn("DeleteStack", null).then().statusCode(200);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                assertEquals(404, sesIdentity(original).statusCode());
                assertEquals(404, sesIdentity(replacement).statusCode());
            });
            verify(sesService, times(2)).deleteIdentity(replacement, "us-east-1");
            stack = null;
        } finally {
            doCallRealMethod().when(sesService).getEmailIdentity(replacement, "us-east-1");
            doCallRealMethod().when(sesService).deleteIdentity(replacement, "us-east-1");
            given().header("Authorization", SES_AUTH)
                    .delete("/v2/email/identities/{identity}", replacement).then().statusCode(anyOf(is(200), is(404)));
        }
    }

    private String awaitStatus(String expected) {
        return await().atMost(Duration.ofSeconds(15)).until(
                () -> cfn("DescribeStacks", null).then().statusCode(200).extract().asString(),
                body -> expected.equals(XmlParser.extractFirst(body, "StackStatus", null)));
    }

    private Response cfn(String action, String template) {
        RequestSpecification request = given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH).formParam("Action", action).formParam("StackName", stack);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        return request.post("/");
    }

    private Response sesIdentity(String identity) {
        return given().header("Authorization", SES_AUTH).get("/v2/email/identities/{identity}", identity);
    }

    private String template(String identity, boolean updated) throws Exception {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("EmailIdentity", identity);
        properties.put("Tags", List.of(Map.of("Key", "purpose", "Value", updated ? "new" : "old")));
        if (updated) {
            properties.put("DkimSigningAttributes", Map.of("NextSigningKeyLength", "RSA_1024_BIT"));
            properties.put("DkimAttributes", Map.of("SigningEnabled", false));
            properties.put("MailFromAttributes", Map.of("MailFromDomain", "mail." + identity,
                    "BehaviorOnMxFailure", "REJECT_MESSAGE"));
            properties.put("FeedbackAttributes", Map.of("EmailForwardingEnabled", false));
        }
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("IdentityRef", Map.of("Value", Map.of("Ref", "Identity")));
        for (int index = 1; index <= 3; index++) {
            outputs.put("DkimName" + index,
                    Map.of("Value", Map.of("Fn::GetAtt", List.of("Identity", "DkimDNSTokenName" + index))));
            outputs.put("DkimValue" + index,
                    Map.of("Value", Map.of("Fn::GetAtt", List.of("Identity", "DkimDNSTokenValue" + index))));
        }
        return MAPPER.writeValueAsString(Map.of("Resources",
                Map.of("Identity", Map.of("Type", "AWS::SES::EmailIdentity", "Properties", properties)),
                "Outputs", outputs));
    }
}
