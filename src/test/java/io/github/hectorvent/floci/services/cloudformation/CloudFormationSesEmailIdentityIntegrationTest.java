package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.ses.SesIdentityService;
import io.github.hectorvent.floci.services.ses.SesService;
import io.github.hectorvent.floci.services.ses.model.Identity;
import io.github.hectorvent.floci.testing.MutableClock;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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
    private String stackAuth = CFN_AUTH;

    @InjectSpy
    SesService sesService;

    @InjectSpy
    SesIdentityService identityService;

    @Inject
    MutableClock clock;

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
        assertEquals(null, identity.jsonPath().getString("DkimAttributes.SigningHostedZone"));

        cfn("DeleteStack", null).then().statusCode(200);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertEquals(404, sesIdentity(address).statusCode()));
        stack = null;
    }

    @Test
    void domainSigningHostedZoneDrivesGetAttInheritanceRotationAndDnsDetection() throws Exception {
        String domain = "ses-" + Long.toString(System.nanoTime(), 36) + ".example.com";
        String signingZone = "dkim.identity-specific.floci.test";
        String email = "user@" + domain;
        stack = "cfn-ses-" + Long.toString(System.nanoTime(), 36);
        createStackWithSigningHostedZone(domain, signingZone);
        List<String> originalTokens = sesIdentity(domain).jsonPath().getList("DkimAttributes.Tokens", String.class);
        cfn("UpdateStack", template(domain, true)).then().statusCode(200);
        String updated = awaitStatus("UPDATE_COMPLETE");
        Response identity = sesIdentity(domain);
        assertEquals(signingZone, identity.jsonPath().getString("DkimAttributes.SigningHostedZone"));
        List<String> tokens = identity.jsonPath().getList("DkimAttributes.Tokens", String.class);
        assertNotEquals(originalTokens, tokens);
        Map<String, String> outputs = XmlParser.extractPairs(updated, "Outputs", "OutputKey", "OutputValue");
        StringBuilder changes = new StringBuilder();
        for (int index = 1; index <= 3; index++) {
            String token = tokens.get(index - 1);
            assertEquals(token + "._domainkey." + domain, outputs.get("DkimName" + index));
            assertEquals(token + "." + signingZone, outputs.get("DkimValue" + index));
            changes.append("<Change><Action>CREATE</Action><ResourceRecordSet><Name>")
                    .append(outputs.get("DkimName" + index)).append("</Name><Type>CNAME</Type><TTL>60</TTL>")
                    .append("<ResourceRecords><ResourceRecord><Value>").append(outputs.get("DkimValue" + index))
                    .append("</Value></ResourceRecord></ResourceRecords></ResourceRecordSet></Change>");
        }
        String zoneId = null;
        boolean recordsCreated = false;
        try {
            given().header("Authorization", SES_AUTH).contentType("application/json")
                    .body(MAPPER.writeValueAsString(Map.of("EmailIdentity", email)))
                    .post("/v2/email/identities").then().statusCode(200)
                    .body("DkimAttributes.SigningHostedZone", is(signingZone));
            String zone = given().contentType("application/xml").body("""
                    <CreateHostedZoneRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                    <Name>%s</Name><CallerReference>%s</CallerReference></CreateHostedZoneRequest>
                    """.formatted(domain, stack)).post("/2013-04-01/hostedzone")
                    .then().statusCode(201).extract().asString();
            zoneId = XmlParser.extractFirst(zone, "Id", null);
            String recordPath = "/2013-04-01" + zoneId + "/rrset";
            given().contentType("application/xml").body(dnsChanges(changes.toString()))
                    .post(recordPath).then().statusCode(200);
            recordsCreated = true;
            String published = given().get(recordPath).then().statusCode(200).extract().asString();
            for (int index = 1; index <= 3; index++) {
                assertTrue(XmlParser.containsValue(published, "Name", outputs.get("DkimName" + index) + "."));
                assertTrue(XmlParser.containsValue(published, "Value", outputs.get("DkimValue" + index)));
            }
            clock.advance(Duration.ofSeconds(5));
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertEquals("SUCCESS", sesIdentity(domain).jsonPath().getString("DkimAttributes.Status")));
            assertEquals(signingZone, sesIdentity(email).jsonPath().getString("DkimAttributes.SigningHostedZone"));
        } finally {
            if (zoneId != null) {
                if (recordsCreated) {
                    given().contentType("application/xml")
                            .body(dnsChanges(changes.toString().replace("<Action>CREATE</Action>", "<Action>DELETE</Action>")))
                            .post("/2013-04-01" + zoneId + "/rrset").then().statusCode(200);
                }
                given().delete("/2013-04-01" + zoneId).then().statusCode(200);
            }
            given().header("Authorization", SES_AUTH).delete("/v2/email/identities/{identity}", email)
                    .then().statusCode(anyOf(is(200), is(404)));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void conditionalOptionsCanBeRemovedAndRestoredWithoutReplacingTheIdentity(boolean members) throws Exception {
        String identityName = "ses-" + Long.toString(System.nanoTime(), 36) + ".example.com";
        stack = "cfn-ses-" + Long.toString(System.nanoTime(), 36);
        cfn("CreateStack", conditionalOptionsTemplate(identityName, true, members)).then().statusCode(200);
        awaitStatus("CREATE_COMPLETE");
        assertConditionalOptions(identityName, true, members);
        clearInvocations(sesService);

        cfn("UpdateStack", conditionalOptionsTemplate(identityName, false, members)).then().statusCode(200);
        String omitted = await().atMost(Duration.ofSeconds(15)).until(
                () -> cfn("DescribeStacks", null).then().statusCode(200).extract().asString(),
                body -> List.of("UPDATE_COMPLETE", "UPDATE_ROLLBACK_COMPLETE", "UPDATE_ROLLBACK_FAILED")
                        .contains(XmlParser.extractFirst(body, "StackStatus", null)));
        assertEquals("UPDATE_COMPLETE", XmlParser.extractFirst(omitted, "StackStatus", null), omitted);
        assertEquals(identityName, XmlParser.extractPairs(omitted,
                "Outputs", "OutputKey", "OutputValue").get("IdentityRef"));
        assertConditionalOptions(identityName, false, members);

        cfn("UpdateStack", conditionalOptionsTemplate(identityName, true, members)).then().statusCode(200);
        String restored = awaitStatus("UPDATE_COMPLETE");
        Map<String, String> outputs = XmlParser.extractPairs(restored, "Outputs", "OutputKey", "OutputValue");
        assertEquals(identityName, outputs.get("IdentityRef"));
        assertConditionalOptions(identityName, true, members);
        List<String> tokens = sesIdentity(identityName).jsonPath().getList("DkimAttributes.Tokens", String.class);
        assertEquals(3, tokens.size());
        for (int index = 1; index <= 3; index++) {
            assertEquals(tokens.get(index - 1) + "._domainkey." + identityName, outputs.get("DkimName" + index));
            assertEquals(tokens.get(index - 1) + ".dkim.amazonses.com", outputs.get("DkimValue" + index));
        }
        verify(sesService, never()).createEmailIdentity(eq(identityName), any(), any(), eq("us-east-1"));
        verify(sesService, never()).deleteIdentity(identityName, "us-east-1");
    }

    @Test
    void optionalSettingsSelectedAsNoValueCanCreateAnIdentity() throws Exception {
        String identityName = "ses-" + Long.toString(System.nanoTime(), 36) + ".example.com";
        stack = "cfn-ses-" + Long.toString(System.nanoTime(), 36);
        ObjectNode template = (ObjectNode) MAPPER.readTree(conditionalOptionsTemplate(identityName, false, false));
        template.withObject("/Resources/Identity/Properties").set("ConfigurationSetAttributes",
                optionalValue(MAPPER.createObjectNode().put("ConfigurationSetName", "unused")));
        cfn("CreateStack", template.toString()).then().statusCode(200);
        String created = await().atMost(Duration.ofSeconds(15)).until(
                () -> cfn("DescribeStacks", null).then().statusCode(200).extract().asString(),
                body -> List.of("CREATE_COMPLETE", "ROLLBACK_COMPLETE", "ROLLBACK_FAILED")
                        .contains(XmlParser.extractFirst(body, "StackStatus", null)));
        assertEquals("CREATE_COMPLETE", XmlParser.extractFirst(created, "StackStatus", null), created);
        assertConditionalOptions(identityName, false, false);
        assertEquals(null, sesIdentity(identityName).jsonPath().getString("ConfigurationSetName"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DkimAttributes", "Tags", "Tag.Key", "Tag.Value"})
    void noValueSupportDoesNotAcceptInvalidShapesOrMissingRequiredTagFields(String invalid) throws Exception {
        String identityName = "ses-" + Long.toString(System.nanoTime(), 36) + ".example.com";
        stack = "cfn-ses-" + Long.toString(System.nanoTime(), 36);
        ObjectNode template = (ObjectNode) MAPPER.readTree(conditionalOptionsTemplate(identityName, false, false));
        ObjectNode properties = template.withObject("/Resources/Identity/Properties");
        if (invalid.startsWith("Tag.")) {
            ObjectNode tag = properties.putArray("Tags").addObject().put("Key", "purpose").put("Value", "");
            tag.set(invalid.substring(4), optionalValue(MAPPER.createObjectNode().put("Ref", "AWS::Region")));
        } else {
            properties.put(invalid, "");
        }
        cfn("CreateStack", template.toString()).then().statusCode(200);
        String failed = await().atMost(Duration.ofSeconds(15)).until(
                () -> cfn("DescribeStacks", null).then().statusCode(200).extract().asString(),
                body -> List.of("CREATE_COMPLETE", "ROLLBACK_COMPLETE", "ROLLBACK_FAILED")
                        .contains(XmlParser.extractFirst(body, "StackStatus", null)));
        assertEquals("ROLLBACK_COMPLETE", XmlParser.extractFirst(failed, "StackStatus", null), failed);
        sesIdentity(identityName).then().statusCode(404);
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
    void changeSetPreviewMatchesReplacementAndInPlaceExecution() throws Exception {
        String first = "ses-" + Long.toString(System.nanoTime(), 36) + ".example.com";
        String second = "other-" + first;
        stack = "cfn-ses-" + Long.toString(System.nanoTime(), 36);
        cfn("CreateStack", dependentTemplate(first, false)).then().statusCode(200);
        awaitStatus("CREATE_COMPLETE");
        assertEquals(first, dependentValue());

        changeSet("CreateChangeSet", "replace-identity", dependentTemplate(second, false))
                .then().statusCode(200);
        String replacementPreview = changeSet("DescribeChangeSet", "replace-identity", null)
                .then().statusCode(200).extract().asString();
        Map<String, String> identityChange = XmlParser.extractGroups(replacementPreview, "ResourceChange")
                .stream().filter(change -> "Identity".equals(change.get("LogicalResourceId")))
                .findFirst().orElseThrow();
        assertEquals("Modify", identityChange.get("Action"));
        assertEquals("True", identityChange.get("Replacement"));
        Map<String, String> dependentChange = XmlParser.extractGroups(replacementPreview, "ResourceChange")
                .stream().filter(change -> "DependentParameter".equals(change.get("LogicalResourceId")))
                .findFirst().orElseThrow();
        assertEquals("Modify", dependentChange.get("Action"));
        assertEquals("False", dependentChange.get("Replacement"));
        assertEquals(200, sesIdentity(first).statusCode(), "Preview must leave the deployed identity unchanged");
        assertEquals(404, sesIdentity(second).statusCode());
        assertEquals(first, dependentValue());

        changeSet("ExecuteChangeSet", "replace-identity", null).then().statusCode(200);
        String replaced = awaitStatus("UPDATE_COMPLETE");
        assertEquals(second, XmlParser.extractPairs(replaced,
                "Outputs", "OutputKey", "OutputValue").get("IdentityRef"));
        assertEquals(404, sesIdentity(first).statusCode());
        assertEquals(200, sesIdentity(second).statusCode());
        assertEquals(second, dependentValue());

        changeSet("CreateChangeSet", "update-options", dependentTemplate(second, true)).then().statusCode(200);
        String optionPreview = changeSet("DescribeChangeSet", "update-options", null)
                .then().statusCode(200).extract().asString();
        Map<String, String> optionChange = XmlParser.extractGroups(optionPreview, "ResourceChange")
                .stream().filter(change -> "Identity".equals(change.get("LogicalResourceId")))
                .findFirst().orElseThrow();
        assertEquals("False", optionChange.get("Replacement"));
        assertEquals(true, sesIdentity(second).jsonPath().getBoolean("FeedbackForwardingStatus"));
        changeSet("ExecuteChangeSet", "update-options", null).then().statusCode(200);
        awaitStatus("UPDATE_COMPLETE");
        assertEquals(false, sesIdentity(second).jsonPath().getBoolean("FeedbackForwardingStatus"));
        assertEquals(second, dependentValue());
    }

    @Test
    void replacementRetainKeepsThePriorIdentityAfterAnotherUpdateAndStackDeletion() throws Exception {
        String first = "ses-" + Long.toString(System.nanoTime(), 36) + ".example.com";
        String second = "retained-" + first;
        stack = "cfn-ses-" + Long.toString(System.nanoTime(), 36);
        cfn("CreateStack", retainedTemplate(first, false)).then().statusCode(200);
        awaitStatus("CREATE_COMPLETE");
        List<String> originalTokens = sesIdentity(first).jsonPath()
                .getList("DkimAttributes.Tokens", String.class);

        try {
            cfn("UpdateStack", retainedTemplate(second, false)).then().statusCode(200);
            String replaced = awaitStatus("UPDATE_COMPLETE");
            assertEquals(second, XmlParser.extractPairs(replaced,
                    "Outputs", "OutputKey", "OutputValue").get("IdentityRef"));
            assertEquals(originalTokens, sesIdentity(first).jsonPath()
                    .getList("DkimAttributes.Tokens", String.class));

            cfn("UpdateStack", retainedTemplate(second, true)).then().statusCode(200);
            awaitStatus("UPDATE_COMPLETE");
            assertEquals("old", sesIdentity(first).jsonPath()
                    .getString("Tags.find { it.Key == 'purpose' }.Value"));
            assertEquals("new", sesIdentity(second).jsonPath()
                    .getString("Tags.find { it.Key == 'purpose' }.Value"));

            cfn("DeleteStack", null).then().statusCode(200);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertEquals(404, sesIdentity(second).statusCode()));
            assertEquals(200, sesIdentity(first).statusCode(),
                    "UpdateReplacePolicy Retain must release the displaced identity from stack ownership");
            stack = null;
        } finally {
            given().header("Authorization", SES_AUTH)
                    .delete("/v2/email/identities/{identity}", first).then().statusCode(anyOf(is(200), is(404)));
        }
    }

    @Test
    void failedUpdateRestoresOnlyTheOwningAccountIdentity() throws Exception {
        String owner = "000000000001";
        String other = "000000000002";
        String identityName = "ses-" + Long.toString(System.nanoTime(), 36) + ".example.com";
        stack = "cfn-ses-" + Long.toString(System.nanoTime(), 36);
        stackAuth = auth(owner, "cloudformation");
        given().header("Authorization", auth(other, "ses")).contentType("application/json")
                .body(MAPPER.writeValueAsString(Map.of("EmailIdentity", identityName,
                        "Tags", List.of(Map.of("Key", "purpose", "Value", "external")))))
                .post("/v2/email/identities").then().statusCode(200);

        try {
            cfn("CreateStack", template(identityName, false)).then().statusCode(200);
            awaitStatus("CREATE_COMPLETE");
            List<String> ownerTokens = sesIdentity(identityName, owner).jsonPath()
                    .getList("DkimAttributes.Tokens", String.class);
            List<String> otherTokens = sesIdentity(identityName, other).jsonPath()
                    .getList("DkimAttributes.Tokens", String.class);
            ObjectNode attempted = (ObjectNode) MAPPER.readTree(template(identityName, true));
            attempted.withObject("/Resources").set("BadSecret", MAPPER.valueToTree(Map.of(
                    "Type", "AWS::SecretsManager::Secret", "DependsOn", "Identity",
                    "Properties", Map.of("SecretString", "explicit",
                            "GenerateSecretString", Map.of("PasswordLength", 32)))));

            cfn("UpdateStack", attempted.toString()).then().statusCode(200);
            awaitStatus("UPDATE_ROLLBACK_COMPLETE");
            Response restored = sesIdentity(identityName, owner);
            assertEquals(200, restored.statusCode(), restored.asString());
            assertEquals(ownerTokens, restored.jsonPath().getList("DkimAttributes.Tokens", String.class));
            assertEquals(true, restored.jsonPath().getBoolean("FeedbackForwardingStatus"));
            assertEquals("old", restored.jsonPath().getString("Tags.find { it.Key == 'purpose' }.Value"));
            Response untouched = sesIdentity(identityName, other);
            assertEquals(otherTokens, untouched.jsonPath().getList("DkimAttributes.Tokens", String.class));
            assertEquals("external", untouched.jsonPath().getString("Tags.find { it.Key == 'purpose' }.Value"));

            cfn("UpdateStack", template(identityName, true)).then().statusCode(200);
            awaitStatus("UPDATE_COMPLETE");
            cfn("DeleteStack", null).then().statusCode(200);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertEquals(404, sesIdentity(identityName, owner).statusCode()));
            assertEquals(200, sesIdentity(identityName, other).statusCode(),
                    "Deleting the owner stack must not delete another account's same-named identity");
            assertEquals("external", sesIdentity(identityName, other).jsonPath()
                    .getString("Tags.find { it.Key == 'purpose' }.Value"));
            stack = null;
        } finally {
            given().header("Authorization", auth(other, "ses"))
                    .delete("/v2/email/identities/{identity}", identityName)
                    .then().statusCode(anyOf(is(200), is(404)));
        }
    }

    @Test
    void failedLaterResourceRestoresIdentitySettingsAndTags() throws Exception {
        String identityName = "ses-" + Long.toString(System.nanoTime(), 36) + ".example.com";
        stack = "cfn-ses-" + Long.toString(System.nanoTime(), 36);
        String signingZone = "dkim.rollback.floci.test";
        createStackWithSigningHostedZone(identityName, signingZone);
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
        assertEquals(signingZone, identity.jsonPath().getString("DkimAttributes.SigningHostedZone"));
        assertEquals(null, identity.jsonPath().getString("MailFromAttributes.MailFromDomain"));
        assertEquals("old", identity.jsonPath().getString("Tags.find { it.Key == 'purpose' }.Value"));
        String restored = cfn("DescribeStacks", null).then().statusCode(200).extract().asString();
        assertEquals(originalTokens.getFirst() + "." + signingZone,
                XmlParser.extractPairs(restored, "Outputs", "OutputKey", "OutputValue").get("DkimValue1"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anotherFailedUpdateRetriesTheLastSuccessfulIdentitySnapshot(boolean replacing) throws Exception {
        String identityName = "ses-" + Long.toString(System.nanoTime(), 36) + ".example.com";
        stack = "cfn-ses-" + Long.toString(System.nanoTime(), 36);
        cfn("CreateStack", template(identityName, false)).then().statusCode(200);
        awaitStatus("CREATE_COMPLETE");
        List<String> originalTokens = sesIdentity(identityName).jsonPath()
                .getList("DkimAttributes.Tokens", String.class);
        ObjectNode firstAttempt = (ObjectNode) MAPPER.readTree(template(identityName, true));
        firstAttempt.withObject("/Resources").set("BadSecret", MAPPER.valueToTree(Map.of(
                "Type", "AWS::SecretsManager::Secret", "DependsOn", "Identity",
                "Properties", Map.of("SecretString", "first-attempt",
                        "GenerateSecretString", Map.of("PasswordLength", 32)))));
        clearInvocations(identityService);
        doThrow(new AwsException("ServiceUnavailableException", "temporary restore failure", 503))
                .doCallRealMethod().when(identityService).save(
                        argThat(identity -> isOriginalSnapshot(identity, identityName, originalTokens)),
                        eq("us-east-1"));

        try {
            cfn("UpdateStack", firstAttempt.toString()).then().statusCode(200);
            awaitStatus("UPDATE_ROLLBACK_FAILED");
            assertEquals(false, sesIdentity(identityName).jsonPath().getBoolean("FeedbackForwardingStatus"));
            assertEquals("new", sesIdentity(identityName).jsonPath()
                    .getString("Tags.find { it.Key == 'purpose' }.Value"));
            String nextIdentity = replacing ? "replacement-" + identityName : identityName;
            ObjectNode secondAttempt = (ObjectNode) MAPPER.readTree(template(nextIdentity, false));
            secondAttempt.withObject("/Resources/Identity/Properties").set("Tags",
                    MAPPER.valueToTree(List.of(Map.of("Key", "purpose", "Value", "second-attempt"))));
            secondAttempt.withObject("/Resources").set("BadSecret", MAPPER.valueToTree(Map.of(
                    "Type", "AWS::SecretsManager::Secret", "DependsOn", "Identity",
                    "Properties", Map.of("SecretString", "second-attempt",
                            "GenerateSecretString", Map.of("PasswordLength", 64)))));

            cfn("UpdateStack", secondAttempt.toString()).then().statusCode(200);
            awaitStatus("UPDATE_ROLLBACK_COMPLETE");

            Response recovered = sesIdentity(identityName);
            assertEquals(200, recovered.statusCode(), recovered.asString());
            assertEquals(originalTokens, recovered.jsonPath().getList("DkimAttributes.Tokens", String.class));
            assertEquals(true, recovered.jsonPath().getBoolean("FeedbackForwardingStatus"));
            assertEquals(true, recovered.jsonPath().getBoolean("DkimAttributes.SigningEnabled"));
            assertEquals("RSA_2048_BIT", recovered.jsonPath().getString("DkimAttributes.NextSigningKeyLength"));
            assertEquals(null, recovered.jsonPath().getString("MailFromAttributes.MailFromDomain"));
            assertEquals("old", recovered.jsonPath().getString("Tags.find { it.Key == 'purpose' }.Value"));
            if (replacing) {
                assertEquals(404, sesIdentity(nextIdentity).statusCode());
                String restored = cfn("DescribeStacks", null).then().statusCode(200).extract().asString();
                assertEquals(identityName, XmlParser.extractPairs(restored,
                        "Outputs", "OutputKey", "OutputValue").get("IdentityRef"));
            }
        } finally {
            doCallRealMethod().when(identityService).save(any(Identity.class), eq("us-east-1"));
        }
    }

    @Test
    void outputsOnlyUpdateDoesNotClearAnUnrestoredIdentitySnapshot() throws Exception {
        String identityName = "ses-" + Long.toString(System.nanoTime(), 36) + ".example.com";
        String otherAccount = "000000000002";
        stack = "cfn-ses-" + Long.toString(System.nanoTime(), 36);
        given().header("Authorization", auth(otherAccount, "ses")).contentType("application/json")
                .body(MAPPER.writeValueAsString(Map.of("EmailIdentity", identityName,
                        "Tags", List.of(Map.of("Key", "purpose", "Value", "external")))))
                .post("/v2/email/identities").then().statusCode(200);
        cfn("CreateStack", template(identityName, false)).then().statusCode(200);
        awaitStatus("CREATE_COMPLETE");
        List<String> originalTokens = sesIdentity(identityName).jsonPath()
                .getList("DkimAttributes.Tokens", String.class);
        List<String> otherTokens = sesIdentity(identityName, otherAccount).jsonPath()
                .getList("DkimAttributes.Tokens", String.class);
        ObjectNode failed = (ObjectNode) MAPPER.readTree(template(identityName, true));
        failed.withObject("/Resources").set("BadSecret", MAPPER.valueToTree(Map.of(
                "Type", "AWS::SecretsManager::Secret", "DependsOn", "Identity",
                "Properties", Map.of("SecretString", "explicit",
                        "GenerateSecretString", Map.of("PasswordLength", 32)))));
        clearInvocations(identityService);
        doThrow(new AwsException("ServiceUnavailableException", "restore unavailable", 503))
                .when(identityService).save(
                        argThat(identity -> isOriginalSnapshot(identity, identityName, originalTokens)),
                        eq("us-east-1"));

        try {
            cfn("UpdateStack", failed.toString()).then().statusCode(200);
            awaitStatus("UPDATE_ROLLBACK_FAILED");
            ObjectNode outputsOnly = (ObjectNode) MAPPER.readTree(template(identityName, true));
            outputsOnly.withObject("/Outputs").set("Marker", MAPPER.valueToTree(Map.of("Value", "cleanup")));
            cfn("UpdateStack", outputsOnly.toString()).then().statusCode(200);
            String completed = await().atMost(Duration.ofSeconds(15)).until(
                    () -> cfn("DescribeStacks", null).then().statusCode(200).extract().asString(),
                    body -> List.of("UPDATE_COMPLETE", "UPDATE_COMPLETE_CLEANUP_IN_PROGRESS")
                            .contains(XmlParser.extractFirst(body, "StackStatus", null)));
            assertEquals("UPDATE_COMPLETE_CLEANUP_IN_PROGRESS",
                    XmlParser.extractFirst(completed, "StackStatus", null));
            assertEquals(false, sesIdentity(identityName).jsonPath().getBoolean("FeedbackForwardingStatus"));
            assertEquals("new", sesIdentity(identityName).jsonPath()
                    .getString("Tags.find { it.Key == 'purpose' }.Value"));
            cfn("UpdateStack", template(identityName, false)).then().statusCode(400);
            verify(identityService, times(1)).save(
                    argThat(identity -> isOriginalSnapshot(identity, identityName, originalTokens)),
                    eq("us-east-1"));

            doThrow(new AwsException("ServiceUnavailableException", "temporary delete failure", 503))
                    .doCallRealMethod().when(sesService).deleteIdentity(identityName, "us-east-1");
            cfn("DeleteStack", null).then().statusCode(200);
            awaitStatus("DELETE_FAILED");
            assertEquals(200, sesIdentity(identityName).statusCode());
            cfn("DeleteStack", null).then().statusCode(200);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertEquals(404, sesIdentity(identityName).statusCode()));
            assertEquals(otherTokens, sesIdentity(identityName, otherAccount).jsonPath()
                    .getList("DkimAttributes.Tokens", String.class));
            assertEquals("external", sesIdentity(identityName, otherAccount).jsonPath()
                    .getString("Tags.find { it.Key == 'purpose' }.Value"));
            stack = null;
        } finally {
            doCallRealMethod().when(identityService).save(any(Identity.class), eq("us-east-1"));
            doCallRealMethod().when(sesService).deleteIdentity(identityName, "us-east-1");
            given().header("Authorization", auth(otherAccount, "ses"))
                    .delete("/v2/email/identities/{identity}", identityName)
                    .then().statusCode(anyOf(is(200), is(404)));
        }
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
                .when(identityService).setMailFromDomain(identityName, mailFromDomain,
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
            doCallRealMethod().when(identityService).setMailFromDomain(identityName, mailFromDomain,
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
        cfn("CreateStack", retainedTemplate(original, false)).then().statusCode(200);
        awaitStatus("CREATE_COMPLETE");
        List<String> originalTokens = sesIdentity(original).jsonPath()
                .getList("DkimAttributes.Tokens", String.class);
        doThrow(new AwsException("BadRequestException", "MAIL FROM rejected", 400))
                .when(identityService).setMailFromDomain(replacement, mailFromDomain,
                        "RejectMessage", "us-east-1");
        doThrow(new AwsException("ServiceUnavailableException", "temporary delete failure", 503))
                .doCallRealMethod().when(sesService).deleteIdentity(replacement, "us-east-1");

        try {
            cfn("UpdateStack", retainedTemplate(replacement, true)).then().statusCode(200);
            String rolledBack = awaitStatus("UPDATE_ROLLBACK_COMPLETE");
            assertEquals(original, XmlParser.extractPairs(rolledBack,
                    "Outputs", "OutputKey", "OutputValue").get("IdentityRef"));
            assertEquals(200, sesIdentity(replacement).statusCode(),
                    "The failed cleanup must retain the replacement for a later retry");
            Response identity = sesIdentity(original);
            assertEquals(200, identity.statusCode(), identity.asString());
            assertEquals(originalTokens, identity.jsonPath().getList("DkimAttributes.Tokens", String.class));
            assertEquals("old", identity.jsonPath().getString("Tags.find { it.Key == 'purpose' }.Value"));

            ObjectNode rejected = (ObjectNode) MAPPER.readTree(retainedTemplate(original, false));
            rejected.withObject("/Resources/Identity/Properties").set("DkimSigningAttributes",
                    MAPPER.valueToTree(Map.of("NextSigningKeyLength", "INVALID")));
            cfn("UpdateStack", rejected.toString()).then().statusCode(200);
            awaitStatus("UPDATE_ROLLBACK_COMPLETE");
            assertEquals(originalTokens,
                    sesIdentity(original).jsonPath().getList("DkimAttributes.Tokens", String.class));
            assertEquals(200, sesIdentity(replacement).statusCode());
            verify(sesService, times(1)).deleteIdentity(replacement, "us-east-1");

            ObjectNode outputsOnly = (ObjectNode) MAPPER.readTree(retainedTemplate(original, false));
            outputsOnly.withObject("/Outputs").set("Marker", MAPPER.valueToTree(Map.of("Value", "cleanup")));
            cfn("UpdateStack", outputsOnly.toString()).then().statusCode(200);
            awaitStatus("UPDATE_COMPLETE");
            assertEquals(404, sesIdentity(replacement).statusCode(),
                    "An outputs-only update must retry cleanup even when SES provisioning is skipped");
            assertEquals(originalTokens,
                    sesIdentity(original).jsonPath().getList("DkimAttributes.Tokens", String.class));
            assertEquals("old", sesIdentity(original).jsonPath()
                    .getString("Tags.find { it.Key == 'purpose' }.Value"));
            verify(sesService, times(2)).deleteIdentity(replacement, "us-east-1");

            cfn("DeleteStack", null).then().statusCode(200);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertEquals(404, sesIdentity(original).statusCode()));
            stack = null;
        } finally {
            doCallRealMethod().when(identityService).setMailFromDomain(replacement, mailFromDomain,
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
                .when(identityService).getIdentityVerificationAttributes(replacement, "us-east-1");
        doThrow(new AwsException("ServiceUnavailableException", "temporary delete failure", 503))
                .doCallRealMethod().when(sesService).deleteIdentity(replacement, "us-east-1");

        try {
            cfn("UpdateStack", template(replacement, false)).then().statusCode(200);
            awaitStatus("UPDATE_ROLLBACK_COMPLETE");
            doCallRealMethod().when(identityService).getIdentityVerificationAttributes(replacement, "us-east-1");
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
            doCallRealMethod().when(identityService).getIdentityVerificationAttributes(replacement, "us-east-1");
            doCallRealMethod().when(sesService).deleteIdentity(replacement, "us-east-1");
            given().header("Authorization", SES_AUTH)
                    .delete("/v2/email/identities/{identity}", replacement).then().statusCode(anyOf(is(200), is(404)));
        }
    }

    @Test
    void stackDeletionAbandonsFailedReplacementAfterCleanupExhaustion() throws Exception {
        String original = "ses-" + Long.toString(System.nanoTime(), 36) + ".example.com";
        String replacement = "replacement-" + original;
        stack = "cfn-ses-" + Long.toString(System.nanoTime(), 36);
        cfn("CreateStack", template(original, false)).then().statusCode(200);
        awaitStatus("CREATE_COMPLETE");
        doThrow(new AwsException("ServiceUnavailableException", "temporary read failure", 503))
                .when(identityService).getIdentityVerificationAttributes(replacement, "us-east-1");
        doThrow(new AwsException("ServiceUnavailableException", "delete unavailable", 503))
                .when(sesService).deleteIdentity(replacement, "us-east-1");

        try {
            cfn("UpdateStack", template(replacement, false)).then().statusCode(200);
            awaitStatus("UPDATE_ROLLBACK_COMPLETE");
            doCallRealMethod().when(identityService).getIdentityVerificationAttributes(replacement, "us-east-1");
            assertEquals(200, sesIdentity(original).statusCode());
            assertEquals(200, sesIdentity(replacement).statusCode());
            clearInvocations(sesService);

            cfn("DeleteStack", null).then().statusCode(200);
            awaitStatus("DELETE_FAILED");
            assertEquals(404, sesIdentity(original).statusCode());
            assertEquals(200, sesIdentity(replacement).statusCode());
            verify(sesService, times(3)).deleteIdentity(replacement, "us-east-1");

            cfn("DeleteStack", null).then().statusCode(200);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertEquals(400, cfn("DescribeStacks", null).statusCode()));
            assertEquals(200, sesIdentity(replacement).statusCode());
            verify(sesService, times(3)).deleteIdentity(replacement, "us-east-1");
            stack = null;
        } finally {
            doCallRealMethod().when(identityService).getIdentityVerificationAttributes(replacement, "us-east-1");
            doCallRealMethod().when(sesService).deleteIdentity(replacement, "us-east-1");
            given().header("Authorization", SES_AUTH)
                    .delete("/v2/email/identities/{identity}", replacement).then().statusCode(anyOf(is(200), is(404)));
        }
    }

    @Test
    void deletionRetryPreservesAnotherStacksIdentityAtAnExhaustedOrphanAddress() throws Exception {
        String original = "ses-" + Long.toString(System.nanoTime(), 36) + ".example.com";
        String replacement = "replacement-" + original;
        stack = "cfn-ses-history-" + Long.toString(System.nanoTime(), 36);
        String originalStack = stack;
        String adoptingStack = "cfn-ses-adopting-" + Long.toString(System.nanoTime(), 36);
        cfn("CreateStack", template(original, false)).then().statusCode(200);
        awaitStatus("CREATE_COMPLETE");
        doThrow(new AwsException("ServiceUnavailableException", "temporary read failure", 503))
                .when(identityService).getIdentityVerificationAttributes(replacement, "us-east-1");
        doThrow(new AwsException("ServiceUnavailableException", "delete unavailable", 503))
                .when(sesService).deleteIdentity(replacement, "us-east-1");

        try {
            cfn("UpdateStack", template(replacement, false)).then().statusCode(200);
            awaitStatus("UPDATE_ROLLBACK_COMPLETE");
            doCallRealMethod().when(identityService).getIdentityVerificationAttributes(replacement, "us-east-1");
            assertEquals(200, sesIdentity(replacement).statusCode());
            cfn("DeleteStack", null).then().statusCode(200);
            awaitStatus("DELETE_FAILED");
            assertEquals(200, sesIdentity(replacement).statusCode());

            doCallRealMethod().when(sesService).deleteIdentity(replacement, "us-east-1");
            given().header("Authorization", SES_AUTH)
                    .delete("/v2/email/identities/{identity}", replacement).then().statusCode(200);
            sesIdentity(replacement).then().statusCode(404);

            stack = adoptingStack;
            cfn("CreateStack", template(replacement, true)).then().statusCode(200);
            String adopted = awaitStatus("CREATE_COMPLETE");
            assertEquals(replacement, XmlParser.extractPairs(adopted,
                    "Outputs", "OutputKey", "OutputValue").get("IdentityRef"));
            Response claimed = sesIdentity(replacement);
            claimed.then().statusCode(200);
            List<String> adoptedTokens = claimed.jsonPath().getList("DkimAttributes.Tokens", String.class);
            assertEquals("new", claimed.jsonPath().getString("Tags.find { it.Key == 'purpose' }.Value"));
            clearInvocations(sesService);

            stack = originalStack;
            cfn("DeleteStack", null).then().statusCode(200);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    assertEquals(400, cfn("DescribeStacks", null).statusCode()));
            assertEquals(404, sesIdentity(original).statusCode());
            stack = adoptingStack;
            Response stillClaimed = cfn("DescribeStacks", null);
            stillClaimed.then().statusCode(200);
            assertEquals("CREATE_COMPLETE", XmlParser.extractFirst(stillClaimed.asString(), "StackStatus", null));
            Response remaining = sesIdentity(replacement);
            assertEquals(200, remaining.statusCode(),
                    "An exhausted cleanup must not delete Stack B's replacement identity: " + remaining.asString());
            assertEquals(adoptedTokens, remaining.jsonPath().getList("DkimAttributes.Tokens", String.class));
            verify(sesService, never()).deleteIdentity(replacement, "us-east-1");
        } finally {
            doCallRealMethod().when(identityService).getIdentityVerificationAttributes(replacement, "us-east-1");
            doCallRealMethod().when(sesService).deleteIdentity(replacement, "us-east-1");
            for (String ownedStack : List.of(adoptingStack, originalStack)) {
                stack = ownedStack;
                if (cfn("DescribeStacks", null).statusCode() == 200) {
                    cfn("DeleteStack", null).then().statusCode(200);
                    await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                            assertEquals(400, cfn("DescribeStacks", null).statusCode()));
                }
            }
            sesIdentity(original).then().statusCode(404);
            sesIdentity(replacement).then().statusCode(404);
            stack = null;
        }
    }

    private String awaitStatus(String expected) {
        return await().atMost(Duration.ofSeconds(15)).until(
                () -> cfn("DescribeStacks", null).then().statusCode(200).extract().asString(),
                body -> expected.equals(XmlParser.extractFirst(body, "StackStatus", null)));
    }

    private boolean isOriginalSnapshot(Identity identity, String identityName, List<String> originalTokens) {
        return identity != null && identityName.equals(identity.getIdentity())
                && originalTokens.equals(identity.getDkimTokens())
                && identity.isFeedbackForwardingEnabled() && identity.isDkimEnabled()
                && "RSA_2048_BIT".equals(identity.getDkimNextSigningKeyLength())
                && identity.getMailFromDomain() == null
                && identity.getTags().stream().anyMatch(tag -> "purpose".equals(tag.key()) && "old".equals(tag.value()));
    }

    private Response cfn(String action, String template) {
        RequestSpecification request = given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", stackAuth).formParam("Action", action).formParam("StackName", stack);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        return request.post("/");
    }

    private Response sesIdentity(String identity) {
        return given().header("Authorization", SES_AUTH).get("/v2/email/identities/{identity}", identity);
    }

    private Response changeSet(String action, String name, String template) {
        RequestSpecification request = given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", stackAuth).formParam("Action", action).formParam("StackName", stack)
                .formParam("ChangeSetName", name);
        if (template != null) {
            request.formParam("ChangeSetType", "UPDATE").formParam("TemplateBody", template);
        }
        return request.post("/");
    }

    private String dependentValue() {
        return given().header("Authorization", auth("000000000000", "ssm"))
                .contentType("application/x-amz-json-1.1").header("X-Amz-Target", "AmazonSSM.GetParameter")
                .body(Map.of("Name", stack + "-identity")).post("/").then().statusCode(200)
                .extract().jsonPath().getString("Parameter.Value");
    }

    private String dependentTemplate(String identity, boolean updated) throws Exception {
        ObjectNode dependent = (ObjectNode) MAPPER.readTree(template(identity, updated));
        dependent.withObject("/Resources").set("DependentParameter", MAPPER.valueToTree(Map.of(
                "Type", "AWS::SSM::Parameter", "Properties", Map.of("Name", stack + "-identity",
                        "Type", "String", "Value", Map.of("Ref", "Identity")))));
        return dependent.toString();
    }

    private Response sesIdentity(String identity, String account) {
        return given().header("Authorization", auth(account, "ses"))
                .get("/v2/email/identities/{identity}", identity);
    }

    private String auth(String account, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20261002/us-east-1/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }

    private String retainedTemplate(String identity, boolean updated) throws Exception {
        ObjectNode retained = (ObjectNode) MAPPER.readTree(template(identity, updated));
        retained.withObject("/Resources/Identity").put("UpdateReplacePolicy", "Retain");
        return retained.toString();
    }

    private String conditionalOptionsTemplate(String identity, boolean include, boolean members) throws Exception {
        ObjectNode conditional = (ObjectNode) MAPPER.readTree(template(identity, true));
        conditional.putObject("Conditions").putObject("IncludeOptions")
                .putArray("Fn::Equals").add("enabled").add(include ? "enabled" : "disabled");
        ObjectNode properties = conditional.withObject("/Resources/Identity/Properties");
        for (String name : List.of("DkimSigningAttributes", "DkimAttributes", "MailFromAttributes", "FeedbackAttributes")) {
            JsonNode value = properties.get(name);
            if (members) {
                ObjectNode object = MAPPER.createObjectNode();
                value.fields().forEachRemaining(field -> object.set(field.getKey(), optionalValue(field.getValue())));
                properties.set(name, object);
            } else {
                properties.set(name, optionalValue(value));
            }
        }
        if (members) {
            properties.putArray("Tags").addObject().put("Key", "stable").put("Value", "");
            properties.withArray("Tags").add(optionalValue(MAPPER.valueToTree(Map.of("Key", "purpose", "Value", "new"))));
        } else {
            properties.set("Tags", optionalValue(properties.get("Tags")));
        }
        return conditional.toString();
    }

    private ObjectNode optionalValue(JsonNode value) {
        ObjectNode conditional = MAPPER.createObjectNode();
        conditional.putArray("Fn::If").add("IncludeOptions").add(value)
                .add(MAPPER.createObjectNode().put("Ref", "AWS::NoValue"));
        return conditional;
    }

    private void assertConditionalOptions(String identityName, boolean include, boolean members) {
        Response identity = sesIdentity(identityName);
        assertEquals(200, identity.statusCode(), identity.asString());
        assertEquals(!include, identity.jsonPath().getBoolean("FeedbackForwardingStatus"));
        assertEquals(!include, identity.jsonPath().getBoolean("DkimAttributes.SigningEnabled"));
        assertEquals(include ? "RSA_1024_BIT" : "RSA_2048_BIT",
                identity.jsonPath().getString("DkimAttributes.NextSigningKeyLength"));
        assertEquals(include ? "mail." + identityName : null,
                identity.jsonPath().getString("MailFromAttributes.MailFromDomain"));
        assertEquals(include ? "REJECT_MESSAGE" : "USE_DEFAULT_VALUE",
                identity.jsonPath().getString("MailFromAttributes.BehaviorOnMxFailure"));
        List<Map<String, String>> tags = identity.jsonPath().getList("Tags");
        assertEquals((include ? 1 : 0) + (members ? 1 : 0), tags.size());
        if (include) {
            assertEquals("new", identity.jsonPath().getString("Tags.find { it.Key == 'purpose' }.Value"));
        }
        if (members) {
            assertEquals("", identity.jsonPath().getString("Tags.find { it.Key == 'stable' }.Value"));
        }
    }

    private void createStackWithSigningHostedZone(String identityName, String signingZone) throws Exception {
        doAnswer(call -> {
            Identity created = (Identity) call.callRealMethod();
            created.setDkimSigningHostedZone(signingZone);
            identityService.save(created, "us-east-1");
            return created;
        }).when(sesService).createEmailIdentity(eq(identityName), any(), any(), eq("us-east-1"));
        try {
            cfn("CreateStack", template(identityName, false)).then().statusCode(200);
            awaitStatus("CREATE_COMPLETE");
        } finally {
            doCallRealMethod().when(sesService).createEmailIdentity(eq(identityName), any(), any(), eq("us-east-1"));
        }
    }

    private String dnsChanges(String changes) {
        return "<ChangeResourceRecordSetsRequest xmlns=\"https://route53.amazonaws.com/doc/2013-04-01/\">"
                + "<ChangeBatch><Changes>" + changes
                + "</Changes></ChangeBatch></ChangeResourceRecordSetsRequest>";
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
