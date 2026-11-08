package com.floci.test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.Change;
import software.amazon.awssdk.services.route53.model.ChangeAction;
import software.amazon.awssdk.services.route53.model.ChangeBatch;
import software.amazon.awssdk.services.route53.model.RRType;
import software.amazon.awssdk.services.route53.model.ResourceRecord;
import software.amazon.awssdk.services.route53.model.ResourceRecordSet;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.DkimSigningKeyLength;
import software.amazon.awssdk.services.sesv2.model.DkimStatus;
import software.amazon.awssdk.services.sesv2.model.GetEmailIdentityResponse;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudFormationSesEmailIdentityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SIGNING_ZONE = "dkim.amazonses.com";
    private CloudFormationClient cfn;
    private SesV2Client ses;
    private Route53Client route53;
    private String stackName;
    private String stackId;

    @BeforeEach
    void setup() {
        cfn = TestFixtures.cloudFormationClient();
        ses = TestFixtures.sesV2Client();
        route53 = TestFixtures.route53Client();
        stackName = TestFixtures.uniqueName("compat-cfn-ses");
    }

    @AfterEach
    void cleanup() throws InterruptedException {
        try {
            deleteStack();
        } finally {
            route53.close();
            ses.close();
            cfn.close();
        }
    }

    @Test
    void domainDnsAttributesTrackRotationAndVerifyWithInheritedEmail() throws Exception {
        String domain = stackName + ".floci.test";
        String email = "user@" + domain;
        createStack(domain);
        List<String> originalTokens = identity(domain).dkimAttributes().tokens();
        assertDnsOutputs(domain, originalTokens);
        ses.createEmailIdentity(r -> r.emailIdentity(email));
        String zoneId = null;
        List<ResourceRecordSet> records = List.of();
        boolean published = false;
        try {
            assertThat(identity(email).dkimAttributes().signingHostedZone()).isEqualTo(SIGNING_ZONE);
            cfn.updateStack(r -> r.stackName(stackId).templateBody(template(domain, true, false)));
            awaitStatus("UPDATE_COMPLETE");
            GetEmailIdentityResponse updated = identity(domain);
            List<String> tokens = updated.dkimAttributes().tokens();
            assertThat(tokens).hasSize(3).doesNotContainAnyElementsOf(originalTokens);
            assertThat(updated.dkimAttributes().signingHostedZone()).isEqualTo(SIGNING_ZONE);
            assertDnsOutputs(domain, tokens);
            assertThat(identity(email).dkimAttributes().tokens()).containsExactlyElementsOf(tokens);
            assertThat(identity(email).dkimAttributes().signingHostedZone()).isEqualTo(SIGNING_ZONE);

            Map<String, String> outputs = outputs();
            records = List.of(1, 2, 3).stream().map(index -> ResourceRecordSet.builder()
                    .name(outputs.get("DkimName" + index)).type(RRType.CNAME).ttl(60L)
                    .resourceRecords(ResourceRecord.builder().value(outputs.get("DkimValue" + index)).build())
                    .build()).toList();
            zoneId = route53.createHostedZone(r -> r.name(domain).callerReference(stackName)).hostedZone().id();
            changeRecords(zoneId, records, ChangeAction.CREATE);
            published = true;
            TestFixtures.await(() -> identity(domain).dkimAttributes().status() == DkimStatus.SUCCESS,
                    domain, "DKIM verification", TestFixtures.isRealAws() ? 600_000_000_000L : 60_000_000_000L,
                    TestFixtures.isRealAws() ? 2_000 : 500);
            GetEmailIdentityResponse verified = identity(domain);
            assertThat(verified.dkimAttributes().status()).isEqualTo(DkimStatus.SUCCESS);
            assertThat(verified.verifiedForSendingStatus()).isTrue();
            assertThat(identity(email).dkimAttributes().status()).isEqualTo(DkimStatus.SUCCESS);
        } finally {
            try {
                if (zoneId != null) {
                    if (published) {
                        changeRecords(zoneId, records, ChangeAction.DELETE);
                    }
                    String cleanupZone = zoneId;
                    route53.deleteHostedZone(r -> r.id(cleanupZone));
                }
            } finally {
                ses.deleteEmailIdentity(r -> r.emailIdentity(email));
            }
        }
    }

    @Test
    void failedDependentRestoresDkimTokensSettingsTagsAndOutputs() throws Exception {
        String domain = stackName + ".floci.test";
        createStack(domain);
        List<String> originalTokens = identity(domain).dkimAttributes().tokens();
        Map<String, String> originalOutputs = outputs();

        cfn.updateStack(r -> r.stackName(stackId).templateBody(template(domain, true, true)));
        awaitStatus("UPDATE_ROLLBACK_COMPLETE");

        assertThat(cfn.describeStackEvents(r -> r.stackName(stackId)).stackEvents())
                .filteredOn(event -> "Broken".equals(event.logicalResourceId()))
                .anySatisfy(event -> {
                    assertThat(event.resourceStatusAsString()).isEqualTo("CREATE_FAILED");
                    assertThat(event.resourceStatusReason())
                            .isEqualTo("You can't specify both SecretString and GenerateSecretString");
                });
        GetEmailIdentityResponse restored = identity(domain);
        assertThat(restored.dkimAttributes().tokens()).containsExactlyElementsOf(originalTokens);
        assertThat(restored.dkimAttributes().signingHostedZone()).isEqualTo(SIGNING_ZONE);
        assertThat(restored.dkimAttributes().nextSigningKeyLength()).isEqualTo(DkimSigningKeyLength.RSA_2048_BIT);
        assertThat(restored.dkimAttributes().signingEnabled()).isTrue();
        assertThat(restored.feedbackForwardingStatus()).isTrue();
        assertThat(restored.mailFromAttributes().mailFromDomain()).isNull();
        assertThat(restored.mailFromAttributes().behaviorOnMxFailureAsString()).isEqualTo("USE_DEFAULT_VALUE");
        assertThat(restored.tags()).anySatisfy(tag -> {
            assertThat(tag.key()).isEqualTo("purpose");
            assertThat(tag.value()).isEqualTo("old");
        });
        assertThat(outputs()).isEqualTo(originalOutputs);
        assertDnsOutputs(domain, originalTokens);
    }

    @Test
    void standaloneEmailHasRefAndEmptyDnsAttributesAndDeletesWithItsStack() throws Exception {
        String email = "user@" + stackName + ".floci.test";
        createStack(email);
        assertThat(outputs().get("IdentityRef")).isEqualTo(email);
        assertThat(identity(email).dkimAttributes().tokens()).isEmpty();
        assertThat(identity(email).dkimAttributes().signingHostedZone()).isNull();
        for (int index = 1; index <= 3; index++) {
            assertThat(outputs().get("DkimName" + index)).isEmpty();
            assertThat(outputs().get("DkimValue" + index)).isEmpty();
        }
        deleteStack();
        assertThatThrownBy(() -> identity(email)).isInstanceOf(NotFoundException.class);
    }

    private String template(String identity, boolean updated, boolean fail) {
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
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("Identity", Map.of("Type", "AWS::SES::EmailIdentity", "Properties", properties));
        if (fail) {
            resources.put("Broken", Map.of("Type", "AWS::SecretsManager::Secret", "DependsOn", "Identity",
                    "Properties", Map.of("SecretString", "explicit",
                            "GenerateSecretString", Map.of("PasswordLength", 32))));
        }
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("IdentityRef", Map.of("Value", Map.of("Ref", "Identity")));
        for (int index = 1; index <= 3; index++) {
            outputs.put("DkimName" + index,
                    Map.of("Value", Map.of("Fn::GetAtt", List.of("Identity", "DkimDNSTokenName" + index))));
            outputs.put("DkimValue" + index,
                    Map.of("Value", Map.of("Fn::GetAtt", List.of("Identity", "DkimDNSTokenValue" + index))));
        }
        try {
            return MAPPER.writeValueAsString(Map.of("Resources", resources, "Outputs", outputs));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void createStack(String identity) throws InterruptedException {
        stackId = cfn.createStack(r -> r.stackName(stackName).templateBody(template(identity, false, false))).stackId();
        awaitStatus("CREATE_COMPLETE");
    }

    private void awaitStatus(String expected) throws InterruptedException {
        TestFixtures.await(() -> {
            Stack stack = cfn.describeStacks(r -> r.stackName(stackId)).stacks().get(0);
            String actual = stack.stackStatusAsString();
            if (expected.equals(actual)) {
                return true;
            }
            if (actual.endsWith("_FAILED") || (!"DELETE_COMPLETE".equals(expected)
                    && actual.contains("ROLLBACK") && !actual.endsWith("_IN_PROGRESS"))) {
                throw new AssertionError(stackName + " reached " + actual + " instead of " + expected + ": "
                        + stack.stackStatusReason());
            }
            return false;
        }, stackName, expected, TestFixtures.isRealAws() ? 600_000_000_000L : 60_000_000_000L,
                TestFixtures.isRealAws() ? 2_000 : 100);
    }

    private GetEmailIdentityResponse identity(String name) {
        return ses.getEmailIdentity(r -> r.emailIdentity(name));
    }

    private Map<String, String> outputs() {
        return cfn.describeStacks(r -> r.stackName(stackId)).stacks().get(0).outputs().stream()
                .collect(Collectors.toMap(Output::outputKey, Output::outputValue));
    }

    private void assertDnsOutputs(String domain, List<String> tokens) {
        Map<String, String> values = outputs();
        assertThat(values.get("IdentityRef")).isEqualTo(domain);
        for (int index = 1; index <= 3; index++) {
            assertThat(values.get("DkimName" + index)).isEqualTo(tokens.get(index - 1) + "._domainkey." + domain);
            assertThat(values.get("DkimValue" + index)).isEqualTo(tokens.get(index - 1) + "." + SIGNING_ZONE);
        }
    }

    private void changeRecords(String zoneId, List<ResourceRecordSet> records, ChangeAction action) {
        List<Change> changes = records.stream().map(record -> Change.builder().action(action)
                .resourceRecordSet(record).build()).toList();
        route53.changeResourceRecordSets(r -> r.hostedZoneId(zoneId)
                .changeBatch(ChangeBatch.builder().changes(changes).build()));
    }

    private void deleteStack() throws InterruptedException {
        if (stackId == null) {
            return;
        }
        cfn.deleteStack(r -> r.stackName(stackId));
        try {
            awaitStatus("DELETE_COMPLETE");
        } catch (CloudFormationException failure) {
            assertThat(failure.awsErrorDetails().errorCode()).isEqualTo("ValidationError");
            assertThat(failure.awsErrorDetails().errorMessage())
                    .isEqualTo("Stack with id " + stackId + " does not exist");
        }
        stackId = null;
    }
}
