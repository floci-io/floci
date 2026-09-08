package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.ses.model.ReceiptAction;
import io.github.hectorvent.floci.services.ses.model.ReceiptFilter;
import io.github.hectorvent.floci.services.ses.model.ReceiptRule;
import io.github.hectorvent.floci.services.ses.model.ReceiptRuleSet;
import io.github.hectorvent.floci.services.sns.SnsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for the extracted receipt-rule domain. The payoff of the split: the
 * service is constructed with just its own store, mocked resource services, and a clock (no
 * 14-argument SesService needed).
 */
class SesReceiptRuleServiceTest {

    private static final String REGION = "us-east-1";
    private static final Predicate<String> ANY_SENDER_VERIFIED = sender -> true;

    private SesReceiptRuleService service;
    private S3Service s3Service;
    private SnsService snsService;
    private LambdaService lambdaService;

    @BeforeEach
    void setUp() {
        s3Service = mock(S3Service.class);
        snsService = mock(SnsService.class);
        lambdaService = mock(LambdaService.class);
        when(s3Service.bucketExists(anyString())).thenReturn(true);
        when(snsService.topicExists(anyString(), anyString())).thenReturn(true);
        when(lambdaService.functionExists(anyString(), anyString())).thenReturn(true);
        service = new SesReceiptRuleService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                s3Service, snsService, lambdaService, Clock.systemUTC());
    }

    private static ReceiptFilter filter(String name, String policy, String cidr) {
        return new ReceiptFilter(name, policy, cidr);
    }

    private static ReceiptRule rule(String name, ReceiptAction... actions) {
        ReceiptRule rule = new ReceiptRule();
        rule.setName(name);
        rule.setActions(new ArrayList<>(List.of(actions)));
        return rule;
    }

    private static ReceiptAction action(String type, String... props) {
        ReceiptAction action = new ReceiptAction(type);
        for (int i = 0; i < props.length; i += 2) {
            action.getProperties().put(props[i], props[i + 1]);
        }
        return action;
    }

    private List<String> ruleNames(String ruleSetName) {
        return service.describeReceiptRuleSet(ruleSetName, REGION).getRules().stream()
                .map(ReceiptRule::getName)
                .toList();
    }

    @Test
    void create_thenDescribe_roundTrips() {
        service.createReceiptRuleSet("rules-a", REGION);
        assertEquals("rules-a", service.describeReceiptRuleSet("rules-a", REGION).getName());
    }

    @Test
    void create_duplicate_throwsAlreadyExists() {
        service.createReceiptRuleSet("rules-a", REGION);
        AwsException e = assertThrows(AwsException.class,
                () -> service.createReceiptRuleSet("rules-a", REGION));
        assertEquals("AlreadyExists", e.getErrorCode());
    }

    @Test
    void describe_unknown_throwsRuleSetDoesNotExist() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.describeReceiptRuleSet("ghost", REGION));
        assertEquals("RuleSetDoesNotExist", e.getErrorCode());
    }

    @Test
    void setActive_thenDescribeActive_andDeleteActiveRejected() {
        service.createReceiptRuleSet("rules-a", REGION);
        service.setActiveReceiptRuleSet("rules-a", REGION);

        ReceiptRuleSet active = service.describeActiveReceiptRuleSet(REGION);
        assertNotNull(active);
        assertEquals("rules-a", active.getName());

        AwsException e = assertThrows(AwsException.class,
                () -> service.deleteReceiptRuleSet("rules-a", REGION));
        assertEquals("CannotDelete", e.getErrorCode());
    }

    @Test
    void invalidName_throwsValidationError() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.createReceiptRuleSet("bad name!", REGION));
        assertEquals("ValidationError", e.getErrorCode());
    }

    // ---- receipt rules ----

    @Test
    void createRule_withoutAfter_insertsAtFront() {
        service.createReceiptRuleSet("rules-a", REGION);
        service.createReceiptRule("rules-a", rule("r1"), null, REGION, ANY_SENDER_VERIFIED);
        service.createReceiptRule("rules-a", rule("r2"), null, REGION, ANY_SENDER_VERIFIED);
        assertEquals(List.of("r2", "r1"), ruleNames("rules-a"));
    }

    @Test
    void createRule_withAfter_insertsBehindNamedRule() {
        service.createReceiptRuleSet("rules-a", REGION);
        service.createReceiptRule("rules-a", rule("r1"), null, REGION, ANY_SENDER_VERIFIED);
        service.createReceiptRule("rules-a", rule("r2"), null, REGION, ANY_SENDER_VERIFIED);
        service.createReceiptRule("rules-a", rule("r3"), "r1", REGION, ANY_SENDER_VERIFIED);
        assertEquals(List.of("r2", "r1", "r3"), ruleNames("rules-a"));
    }

    @Test
    void createRule_afterMissing_throwsRuleDoesNotExist() {
        service.createReceiptRuleSet("rules-a", REGION);
        AwsException e = assertThrows(AwsException.class,
                () -> service.createReceiptRule("rules-a", rule("r1"), "ghost", REGION, ANY_SENDER_VERIFIED));
        assertEquals("RuleDoesNotExist", e.getErrorCode());
        assertEquals("Rule does not exist: ghost", e.getMessage());
    }

    @Test
    void createRule_duplicate_throwsAlreadyExists() {
        service.createReceiptRuleSet("rules-a", REGION);
        service.createReceiptRule("rules-a", rule("r1"), null, REGION, ANY_SENDER_VERIFIED);
        AwsException e = assertThrows(AwsException.class,
                () -> service.createReceiptRule("rules-a", rule("r1"), null, REGION, ANY_SENDER_VERIFIED));
        assertEquals("AlreadyExists", e.getErrorCode());
        assertEquals("Rule already exists: r1", e.getMessage());
    }

    @Test
    void createRule_inMissingSet_throwsRuleSetDoesNotExist() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.createReceiptRule("ghost", rule("r1"), null, REGION, ANY_SENDER_VERIFIED));
        assertEquals("RuleSetDoesNotExist", e.getErrorCode());
    }

    @Test
    void createRule_defaults_tlsPolicyOptionalAndDisabled() {
        service.createReceiptRuleSet("rules-a", REGION);
        service.createReceiptRule("rules-a", rule("r1"), null, REGION, ANY_SENDER_VERIFIED);
        ReceiptRule stored = service.describeReceiptRule("rules-a", "r1", REGION);
        assertEquals("Optional", stored.getTlsPolicy());
        assertFalse(stored.isEnabled());
        assertFalse(stored.isScanEnabled());
        assertTrue(stored.getActions().isEmpty());
    }

    @Test
    void createRule_emptyName_reportsBothSmithyViolations() {
        service.createReceiptRuleSet("rules-a", REGION);
        AwsException e = assertThrows(AwsException.class,
                () -> service.createReceiptRule("rules-a", rule(""), null, REGION, ANY_SENDER_VERIFIED));
        assertEquals("ValidationError", e.getErrorCode());
        assertTrue(e.getMessage().startsWith("2 validation errors detected: "));
        assertTrue(e.getMessage().contains("Member must have length greater than or equal to 1"));
        assertTrue(e.getMessage().contains("^[a-zA-Z0-9_.-]+$"));
    }

    @Test
    void createRule_badBoundaryName_throwsServiceLevelInvalidParameterValue() {
        service.createReceiptRuleSet("rules-a", REGION);
        AwsException e = assertThrows(AwsException.class,
                () -> service.createReceiptRule("rules-a", rule("-abc"), null, REGION, ANY_SENDER_VERIFIED));
        assertEquals("InvalidParameterValue", e.getErrorCode());
        assertEquals("Not a valid ruleName: -abc", e.getMessage());
    }

    @Test
    void createRule_badTlsPolicy_throwsEnumViolation() {
        service.createReceiptRuleSet("rules-a", REGION);
        ReceiptRule rule = rule("r1");
        rule.setTlsPolicy("Bogus");
        AwsException e = assertThrows(AwsException.class,
                () -> service.createReceiptRule("rules-a", rule, null, REGION, ANY_SENDER_VERIFIED));
        assertEquals("ValidationError", e.getErrorCode());
        assertTrue(e.getMessage().contains("'rule.tlsPolicy'"));
        assertTrue(e.getMessage().contains("[Optional, Require]"));
    }

    @Test
    void createRule_missingRequiredActionMembers_collectsViolationsInOrder() {
        service.createReceiptRuleSet("rules-a", REGION);
        AwsException e = assertThrows(AwsException.class,
                () -> service.createReceiptRule("rules-a",
                        rule("r1", action("BounceAction", "SmtpReplyCode", "550")),
                        null, REGION, ANY_SENDER_VERIFIED));
        assertEquals("ValidationError", e.getErrorCode());
        assertTrue(e.getMessage().startsWith("2 validation errors detected: "));
        assertTrue(e.getMessage().indexOf("bounceAction.sender")
                < e.getMessage().indexOf("bounceAction.message"));
    }

    @Test
    void connectAndWorkmailActions_requireTheirWireRequiredMembers() {
        service.createReceiptRuleSet("rules-a", REGION);

        AwsException connect = assertThrows(AwsException.class, () -> service.createReceiptRule(
                "rules-a", rule("r1", action("ConnectAction", "InstanceARN",
                        "arn:aws:connect:us-east-1:000000000000:instance/i-1")),
                null, REGION, ANY_SENDER_VERIFIED));
        assertEquals("ValidationError", connect.getErrorCode());
        assertTrue(connect.getMessage().contains("'rule.actions.1.member.connectAction.iAMRoleARN'"));

        AwsException workmail = assertThrows(AwsException.class, () -> service.createReceiptRule(
                "rules-a", rule("r1", action("WorkmailAction", "TopicArn",
                        "arn:aws:sns:us-east-1:000000000000:topic")),
                null, REGION, ANY_SENDER_VERIFIED));
        assertEquals("ValidationError", workmail.getErrorCode());
        assertTrue(workmail.getMessage().contains("'rule.actions.1.member.workmailAction.organizationArn'"));

        // A well-formed OrganizationArn is stored without an existence check (probed: real AWS
        // accepts a fabricated organization ARN).
        service.createReceiptRule("rules-a",
                rule("r1", action("WorkmailAction", "OrganizationArn",
                        "arn:aws:workmail:us-east-1:000000000000:organization/m-1")),
                null, REGION, ANY_SENDER_VERIFIED);
        assertEquals(1, service.describeReceiptRule("rules-a", "r1", REGION).getActions().size());
    }

    @Test
    void presentActionStructs_enforceTheirRequiredMembers() {
        // Probed: once an action carries any member, the type's required members are Smithy
        // violations (an all-empty struct never reaches this layer; it has no wire keys).
        service.createReceiptRuleSet("rules-a", REGION);

        AwsException s3 = assertThrows(AwsException.class, () -> service.createReceiptRule(
                "rules-a", rule("r1", action("S3Action", "ObjectKeyPrefix", "inbox/")),
                null, REGION, ANY_SENDER_VERIFIED));
        assertTrue(s3.getMessage().contains("'rule.actions.1.member.s3Action.bucketName'"));

        AwsException stop = assertThrows(AwsException.class, () -> service.createReceiptRule(
                "rules-a", rule("r1", action("StopAction", "TopicArn",
                        "arn:aws:sns:us-east-1:000000000000:topic")),
                null, REGION, ANY_SENDER_VERIFIED));
        assertTrue(stop.getMessage().contains("'rule.actions.1.member.stopAction.scope'"));

        AwsException sns = assertThrows(AwsException.class, () -> service.createReceiptRule(
                "rules-a", rule("r1", action("SNSAction", "Encoding", "UTF-8")),
                null, REGION, ANY_SENDER_VERIFIED));
        assertTrue(sns.getMessage().contains("'rule.actions.1.member.sNSAction.topicArn'"));

        // Probed order for a bounce action with only TopicArn: smtpReplyCode, sender, message.
        AwsException bounce = assertThrows(AwsException.class, () -> service.createReceiptRule(
                "rules-a", rule("r1", action("BounceAction", "TopicArn",
                        "arn:aws:sns:us-east-1:000000000000:topic")),
                null, REGION, ANY_SENDER_VERIFIED));
        assertTrue(bounce.getMessage().startsWith("3 validation errors detected: "));
        assertTrue(bounce.getMessage().indexOf("bounceAction.smtpReplyCode")
                < bounce.getMessage().indexOf("bounceAction.sender"));
        assertTrue(bounce.getMessage().indexOf("bounceAction.sender")
                < bounce.getMessage().indexOf("bounceAction.message"));
    }

    @Test
    void createRule_lambdaEnumAndSnsEncodingPaths_matchWireCasing() {
        service.createReceiptRuleSet("rules-a", REGION);
        AwsException e = assertThrows(AwsException.class,
                () -> service.createReceiptRule("rules-a",
                        rule("r1", action("SNSAction", "Encoding", "Bogus")),
                        null, REGION, ANY_SENDER_VERIFIED));
        assertTrue(e.getMessage().contains("'rule.actions.1.member.sNSAction.encoding'"));
        assertTrue(e.getMessage().contains("[Base64, UTF-8]"));
    }

    @Test
    void createRule_missingBucket_throwsInvalidS3Configuration() {
        when(s3Service.bucketExists("ghost-bucket")).thenReturn(false);
        service.createReceiptRuleSet("rules-a", REGION);
        AwsException e = assertThrows(AwsException.class,
                () -> service.createReceiptRule("rules-a",
                        rule("r1", action("S3Action", "BucketName", "ghost-bucket")),
                        null, REGION, ANY_SENDER_VERIFIED));
        assertEquals("InvalidS3Configuration", e.getErrorCode());
        assertEquals("No such bucket: ghost-bucket", e.getMessage());
    }

    @Test
    void createRule_missingTopicOnAnyAction_throwsInvalidSnsTopic() {
        String arn = "arn:aws:sns:us-east-1:000000000000:ghost";
        when(snsService.topicExists(arn, REGION)).thenReturn(false);
        service.createReceiptRuleSet("rules-a", REGION);
        AwsException e = assertThrows(AwsException.class,
                () -> service.createReceiptRule("rules-a",
                        rule("r1", action("StopAction", "Scope", "RuleSet", "TopicArn", arn)),
                        null, REGION, ANY_SENDER_VERIFIED));
        assertEquals("InvalidSnsTopic", e.getErrorCode());
        assertEquals("Could not publish to SNS topic: " + arn, e.getMessage());
    }

    @Test
    void createRule_missingLambda_throwsInvalidLambdaFunction() {
        String arn = "arn:aws:lambda:us-east-1:000000000000:function:ghost";
        when(lambdaService.functionExists(REGION, arn)).thenReturn(false);
        service.createReceiptRuleSet("rules-a", REGION);
        AwsException e = assertThrows(AwsException.class,
                () -> service.createReceiptRule("rules-a",
                        rule("r1", action("LambdaAction", "FunctionArn", arn)),
                        null, REGION, ANY_SENDER_VERIFIED));
        assertEquals("InvalidLambdaFunction", e.getErrorCode());
        assertEquals("Could not invoke Lambda function: " + arn, e.getMessage());
    }

    @Test
    void createRule_unverifiedBounceSender_throwsInvalidParameterValue() {
        service.createReceiptRuleSet("rules-a", REGION);
        AwsException e = assertThrows(AwsException.class,
                () -> service.createReceiptRule("rules-a",
                        rule("r1", action("BounceAction", "SmtpReplyCode", "550",
                                "Message", "m", "Sender", "no-reply@example.com")),
                        null, REGION, sender -> false));
        assertEquals("InvalidParameterValue", e.getErrorCode());
        assertEquals("Identity is not verified: no-reply@example.com", e.getMessage());
    }

    @Test
    void createRule_invalidHeaderName_throwsInvalidParameterValue() {
        service.createReceiptRuleSet("rules-a", REGION);
        AwsException e = assertThrows(AwsException.class,
                () -> service.createReceiptRule("rules-a",
                        rule("r1", action("AddHeaderAction", "HeaderName", "bad header",
                                "HeaderValue", "v")),
                        null, REGION, ANY_SENDER_VERIFIED));
        assertEquals("InvalidParameterValue", e.getErrorCode());
        assertEquals("Invalid header name: bad header", e.getMessage());
    }

    @Test
    void createRule_snsActionWithTopic_getsUtf8EncodingDefault() {
        service.createReceiptRuleSet("rules-a", REGION);
        String arn = "arn:aws:sns:us-east-1:000000000000:topic";
        service.createReceiptRule("rules-a",
                rule("r1", action("SNSAction", "TopicArn", arn)), null, REGION, ANY_SENDER_VERIFIED);
        assertEquals("UTF-8",
                service.describeReceiptRule("rules-a", "r1", REGION).getActions().get(0).property("Encoding"));
    }

    @Test
    void updateRule_replacesWholeRuleAndKeepsPosition() {
        service.createReceiptRuleSet("rules-a", REGION);
        ReceiptRule original = rule("r1", action("StopAction", "Scope", "RuleSet"));
        original.setEnabled(true);
        original.setRecipients(new ArrayList<>(List.of("probe@example.com")));
        service.createReceiptRule("rules-a", original, null, REGION, ANY_SENDER_VERIFIED);
        service.createReceiptRule("rules-a", rule("r0"), null, REGION, ANY_SENDER_VERIFIED);

        service.updateReceiptRule("rules-a", rule("r1"), REGION, ANY_SENDER_VERIFIED);

        ReceiptRule updated = service.describeReceiptRule("rules-a", "r1", REGION);
        assertFalse(updated.isEnabled());
        assertTrue(updated.getActions().isEmpty());
        assertTrue(updated.getRecipients().isEmpty());
        assertEquals(List.of("r0", "r1"), ruleNames("rules-a"));
    }

    @Test
    void updateRule_unknownName_throwsRuleDoesNotExist() {
        service.createReceiptRuleSet("rules-a", REGION);
        AwsException e = assertThrows(AwsException.class,
                () -> service.updateReceiptRule("rules-a", rule("ghost"), REGION, ANY_SENDER_VERIFIED));
        assertEquals("RuleDoesNotExist", e.getErrorCode());
    }

    @Test
    void deleteRule_isIdempotentButRequiresRuleSet() {
        service.createReceiptRuleSet("rules-a", REGION);
        service.deleteReceiptRule("rules-a", "ghost", REGION);

        AwsException e = assertThrows(AwsException.class,
                () -> service.deleteReceiptRule("ghost-set", "ghost", REGION));
        assertEquals("RuleSetDoesNotExist", e.getErrorCode());
    }

    @Test
    void setRulePosition_movesToFrontWithoutAfter_andAfterSelfIsNoOp() {
        service.createReceiptRuleSet("rules-a", REGION);
        service.createReceiptRule("rules-a", rule("r1"), null, REGION, ANY_SENDER_VERIFIED);
        service.createReceiptRule("rules-a", rule("r2"), null, REGION, ANY_SENDER_VERIFIED);
        service.createReceiptRule("rules-a", rule("r3"), null, REGION, ANY_SENDER_VERIFIED);
        assertEquals(List.of("r3", "r2", "r1"), ruleNames("rules-a"));

        service.setReceiptRulePosition("rules-a", "r1", null, REGION);
        assertEquals(List.of("r1", "r3", "r2"), ruleNames("rules-a"));

        service.setReceiptRulePosition("rules-a", "r3", "r2", REGION);
        assertEquals(List.of("r1", "r2", "r3"), ruleNames("rules-a"));

        service.setReceiptRulePosition("rules-a", "r2", "r2", REGION);
        assertEquals(List.of("r1", "r2", "r3"), ruleNames("rules-a"));
    }

    @Test
    void setRulePosition_missingAfter_throwsRuleDoesNotExist() {
        service.createReceiptRuleSet("rules-a", REGION);
        service.createReceiptRule("rules-a", rule("r1"), null, REGION, ANY_SENDER_VERIFIED);
        AwsException e = assertThrows(AwsException.class,
                () -> service.setReceiptRulePosition("rules-a", "r1", "ghost", REGION));
        assertEquals("RuleDoesNotExist", e.getErrorCode());
    }

    @Test
    void emptyActionMembers_getServiceLevelMessages() {
        service.createReceiptRuleSet("rules-a", REGION);

        AwsException topic = assertThrows(AwsException.class, () -> service.createReceiptRule(
                "rules-a", rule("r1", action("SNSAction", "TopicArn", "")), null, REGION,
                ANY_SENDER_VERIFIED));
        assertEquals("InvalidSnsTopic", topic.getErrorCode());
        assertEquals("Invalid SNS topic: ", topic.getMessage());

        AwsException bucket = assertThrows(AwsException.class, () -> service.createReceiptRule(
                "rules-a", rule("r1", action("S3Action", "BucketName", "")), null, REGION,
                ANY_SENDER_VERIFIED));
        assertEquals("InvalidParameterValue", bucket.getErrorCode());
        assertEquals("Bucket name must not be empty", bucket.getMessage());

        AwsException lambda = assertThrows(AwsException.class, () -> service.createReceiptRule(
                "rules-a", rule("r1", action("LambdaAction", "FunctionArn", "")), null, REGION,
                ANY_SENDER_VERIFIED));
        assertEquals("InvalidParameterValue", lambda.getErrorCode());
        assertEquals("Lambda function ARN must not be empty", lambda.getMessage());

        // The empty-message check runs before sender verification (probed: an unverified
        // sender with an empty Message still reports the message error).
        AwsException bounce = assertThrows(AwsException.class, () -> service.createReceiptRule(
                "rules-a", rule("r1", action("BounceAction", "SmtpReplyCode", "550",
                        "Message", "", "Sender", "x@example.com")), null, REGION, s -> false));
        assertEquals("Invalid SMTP response message: ", bounce.getMessage());

        AwsException replyCode = assertThrows(AwsException.class, () -> service.createReceiptRule(
                "rules-a", rule("r1", action("BounceAction", "SmtpReplyCode", "",
                        "Message", "m", "Sender", "x@example.com")), null, REGION, s -> false));
        assertEquals("Invalid SMTP reply code: ", replyCode.getMessage());
    }

    @Test
    void emptyHeaderValue_isAcceptedAndStored() {
        service.createReceiptRuleSet("rules-a", REGION);
        service.createReceiptRule("rules-a",
                rule("r1", action("AddHeaderAction", "HeaderName", "X-A", "HeaderValue", "")),
                null, REGION, ANY_SENDER_VERIFIED);
        assertEquals("", service.describeReceiptRule("rules-a", "r1", REGION)
                .getActions().get(0).property("HeaderValue"));
    }

    @Test
    void ruleNameParams_getTwoLayerValidation() {
        service.createReceiptRuleSet("rules-a", REGION);

        AwsException pattern = assertThrows(AwsException.class,
                () -> service.describeReceiptRule("rules-a", "bad name", REGION));
        assertEquals("ValidationError", pattern.getErrorCode());
        assertTrue(pattern.getMessage().contains("'ruleName'"));

        AwsException boundary = assertThrows(AwsException.class,
                () -> service.deleteReceiptRule("rules-a", "a".repeat(65), REGION));
        assertEquals("InvalidParameterValue", boundary.getErrorCode());
        assertEquals("Not a valid ruleName: " + "a".repeat(65), boundary.getMessage());

        // A SUPPLIED empty value takes the probed Smithy violations, not "RuleName is required.".
        AwsException empty = assertThrows(AwsException.class,
                () -> service.describeReceiptRule("rules-a", "", REGION));
        assertEquals("ValidationError", empty.getErrorCode());
        assertTrue(empty.getMessage().startsWith("2 validation errors detected: "));
        assertTrue(empty.getMessage().contains("'ruleName'"));
    }

    @Test
    void afterParam_getsSmithyValidation() {
        service.createReceiptRuleSet("rules-a", REGION);
        service.createReceiptRule("rules-a", rule("r1"), null, REGION, ANY_SENDER_VERIFIED);

        AwsException setPos = assertThrows(AwsException.class,
                () -> service.setReceiptRulePosition("rules-a", "r1", "bad name", REGION));
        assertEquals("ValidationError", setPos.getErrorCode());
        assertTrue(setPos.getMessage().contains("'after'"));

        AwsException emptyAfter = assertThrows(AwsException.class,
                () -> service.createReceiptRule("rules-a", rule("r2"), "", REGION, ANY_SENDER_VERIFIED));
        assertEquals("ValidationError", emptyAfter.getErrorCode());
        assertTrue(emptyAfter.getMessage().startsWith("2 validation errors detected: "));
        assertTrue(emptyAfter.getMessage().contains("'after'"));
    }

    @Test
    void describedRulesList_isNotMutatedByLaterWrites() {
        service.createReceiptRuleSet("rules-a", REGION);
        service.createReceiptRule("rules-a", rule("r1"), null, REGION, ANY_SENDER_VERIFIED);

        List<ReceiptRule> snapshot = service.describeReceiptRuleSet("rules-a", REGION).getRules();
        service.createReceiptRule("rules-a", rule("r2"), null, REGION, ANY_SENDER_VERIFIED);
        service.deleteReceiptRule("rules-a", "r1", REGION);

        assertEquals(List.of("r1"), snapshot.stream().map(ReceiptRule::getName).toList());
        assertEquals(List.of("r2"), ruleNames("rules-a"));
    }

    @Test
    void nonArnTargets_getInvalidShapeMessages_andRecipientsHaveNoLimit() {
        service.createReceiptRuleSet("rules-a", REGION);

        AwsException lambda = assertThrows(AwsException.class, () -> service.createReceiptRule(
                "rules-a", rule("r1", action("LambdaAction", "FunctionArn", "bare-name")),
                null, REGION, ANY_SENDER_VERIFIED));
        assertEquals("InvalidLambdaFunction", lambda.getErrorCode());
        assertEquals("Invalid Lambda function: bare-name", lambda.getMessage());

        AwsException topic = assertThrows(AwsException.class, () -> service.createReceiptRule(
                "rules-a", rule("r1", action("SNSAction", "TopicArn", "bare-name")),
                null, REGION, ANY_SENDER_VERIFIED));
        assertEquals("InvalidSnsTopic", topic.getErrorCode());
        assertEquals("Invalid SNS topic: bare-name", topic.getMessage());

        // No recipient-count limit exists (101 recipients are accepted by real AWS).
        ReceiptRule wide = rule("r1");
        for (int i = 0; i < 101; i++) {
            wide.getRecipients().add("r" + i + "@example.com");
        }
        service.createReceiptRule("rules-a", wide, null, REGION, ANY_SENDER_VERIFIED);
        assertEquals(101, service.describeReceiptRule("rules-a", "r1", REGION).getRecipients().size());
    }

    @Test
    void createRule_with11Actions_throwsLimitExceeded() {
        service.createReceiptRuleSet("rules-a", REGION);
        ReceiptRule rule = rule("r1");
        for (int i = 0; i < 11; i++) {
            rule.getActions().add(action("AddHeaderAction", "HeaderName", "X-H" + i, "HeaderValue", "v"));
        }
        AwsException e = assertThrows(AwsException.class,
                () -> service.createReceiptRule("rules-a", rule, null, REGION, ANY_SENDER_VERIFIED));
        assertEquals("LimitExceeded", e.getErrorCode());
        assertEquals("Too many actions", e.getMessage());
    }

    @Test
    void createRule_beyond200_throwsLimitExceeded() {
        service.createReceiptRuleSet("rules-a", REGION);
        for (int i = 1; i <= 200; i++) {
            service.createReceiptRule("rules-a", rule("r" + i), null, REGION, ANY_SENDER_VERIFIED);
        }
        AwsException e = assertThrows(AwsException.class,
                () -> service.createReceiptRule("rules-a", rule("r201"), null, REGION, ANY_SENDER_VERIFIED));
        assertEquals("LimitExceeded", e.getErrorCode());
        assertEquals("Too many rules", e.getMessage());
    }

    @Test
    void deleteRuleSet_cascadesRules() {
        service.createReceiptRuleSet("rules-a", REGION);
        service.createReceiptRule("rules-a", rule("r1"), null, REGION, ANY_SENDER_VERIFIED);
        service.deleteReceiptRuleSet("rules-a", REGION);
        AwsException e = assertThrows(AwsException.class,
                () -> service.describeReceiptRuleSet("rules-a", REGION));
        assertEquals("RuleSetDoesNotExist", e.getErrorCode());
    }

    // ---- reorder / clone ----

    private void createThreeRuleSet(String name) {
        service.createReceiptRuleSet(name, REGION);
        service.createReceiptRule(name, rule("r1"), null, REGION, ANY_SENDER_VERIFIED);
        service.createReceiptRule(name, rule("r2"), "r1", REGION, ANY_SENDER_VERIFIED);
        service.createReceiptRule(name, rule("r3"), "r2", REGION, ANY_SENDER_VERIFIED);
    }

    @Test
    void reorder_appliesFullPermutation() {
        createThreeRuleSet("rules-a");
        service.reorderReceiptRuleSet("rules-a", List.of("r3", "r1", "r2"), REGION);
        assertEquals(List.of("r3", "r1", "r2"), ruleNames("rules-a"));
    }

    @Test
    void reorder_identityOrder_isNoOp() {
        createThreeRuleSet("rules-a");
        service.reorderReceiptRuleSet("rules-a", List.of("r1", "r2", "r3"), REGION);
        assertEquals(List.of("r1", "r2", "r3"), ruleNames("rules-a"));
    }

    @Test
    void reorder_unknownSet_throwsBeforeRuleNameProblems() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.reorderReceiptRuleSet("ghost", List.of("rX", "rX"), REGION));
        assertEquals("RuleSetDoesNotExist", e.getErrorCode());
    }

    @Test
    void reorder_duplicateName_winsOverMissingAndUnknown() {
        createThreeRuleSet("rules-a");

        // Duplicate of an existing name, with the set only partially covered.
        AwsException dup = assertThrows(AwsException.class,
                () -> service.reorderReceiptRuleSet("rules-a", List.of("r1", "r1"), REGION));
        assertEquals("InvalidParameterValue", dup.getErrorCode());
        assertEquals("Multiple positions found for rule: r1", dup.getMessage());

        // Duplicate of a name that is not in the set at all still takes the duplicate error.
        AwsException dupUnknown = assertThrows(AwsException.class,
                () -> service.reorderReceiptRuleSet("rules-a",
                        List.of("r1", "r2", "r3", "rX", "rX"), REGION));
        assertEquals("Multiple positions found for rule: rX", dupUnknown.getMessage());
    }

    @Test
    void reorder_missingRules_listedInSetOrder_beforeUnknownNames() {
        createThreeRuleSet("rules-a");
        service.reorderReceiptRuleSet("rules-a", List.of("r3", "r1", "r2"), REGION);

        // Unknown rX does not surface while set rules are missing; the list follows set order.
        AwsException e = assertThrows(AwsException.class,
                () -> service.reorderReceiptRuleSet("rules-a", List.of("r2", "rX"), REGION));
        assertEquals("InvalidParameterValue", e.getErrorCode());
        assertEquals("Positions for rules not found: r3, r1", e.getMessage());

        // An empty RuleNames list is the same error naming every rule.
        AwsException empty = assertThrows(AwsException.class,
                () -> service.reorderReceiptRuleSet("rules-a", List.of(), REGION));
        assertEquals("Positions for rules not found: r3, r1, r2", empty.getMessage());
    }

    @Test
    void reorder_unknownName_reportsFirstInRequestOrder_andLeavesOrderUnchanged() {
        createThreeRuleSet("rules-a");
        AwsException e = assertThrows(AwsException.class,
                () -> service.reorderReceiptRuleSet("rules-a",
                        List.of("r1", "r2", "r3", "rX", "rY"), REGION));
        assertEquals("RuleDoesNotExist", e.getErrorCode());
        assertEquals("Rule does not exist: rX", e.getMessage());
        assertEquals(List.of("r1", "r2", "r3"), ruleNames("rules-a"));
    }

    @Test
    void reorder_ruleNamesMembers_getNestedSmithyViolation_beforeSetExistence() {
        AwsException badChar = assertThrows(AwsException.class,
                () -> service.reorderReceiptRuleSet("ghost", List.of("bad name!"), REGION));
        assertEquals("ValidationError", badChar.getErrorCode());
        assertEquals("1 validation error detected: Value at 'ruleNames' failed to satisfy "
                + "constraint: Member must satisfy constraint: "
                + "[Member must have length less than or equal to 100, "
                + "Member must have length greater than or equal to 1, "
                + "Member must satisfy regular expression pattern: ^[a-zA-Z0-9_.-]+$]",
                badChar.getMessage());

        // Empty and >100 take the same violation; a 65-char name passes (the cap here is 100,
        // not the 64 of the service-level name checks) and lands on RuleDoesNotExist instead.
        createThreeRuleSet("rules-a");
        AwsException empty = assertThrows(AwsException.class,
                () -> service.reorderReceiptRuleSet("rules-a", List.of(""), REGION));
        assertEquals("ValidationError", empty.getErrorCode());
        AwsException tooLong = assertThrows(AwsException.class,
                () -> service.reorderReceiptRuleSet("rules-a", List.of("a".repeat(101)), REGION));
        assertEquals("ValidationError", tooLong.getErrorCode());
        AwsException longButLegal = assertThrows(AwsException.class,
                () -> service.reorderReceiptRuleSet("rules-a",
                        List.of("r1", "r2", "r3", "a".repeat(65)), REGION));
        assertEquals("RuleDoesNotExist", longButLegal.getErrorCode());

        // Several malformed members still produce the single index-less violation (probed).
        AwsException manyBad = assertThrows(AwsException.class,
                () -> service.reorderReceiptRuleSet("rules-a",
                        List.of("bad name!", "", "a".repeat(101)), REGION));
        assertTrue(manyBad.getMessage().startsWith("1 validation error detected: "));
    }

    @Test
    void reorder_ruleSetName_getsSmithyThenServiceValidation() {
        AwsException empty = assertThrows(AwsException.class,
                () -> service.reorderReceiptRuleSet("", List.of("r1"), REGION));
        assertEquals("ValidationError", empty.getErrorCode());
        assertTrue(empty.getMessage().startsWith("2 validation errors detected: "));
        assertTrue(empty.getMessage().contains("'ruleSetName'"));

        AwsException tooLong = assertThrows(AwsException.class,
                () -> service.reorderReceiptRuleSet("a".repeat(65), List.of("r1"), REGION));
        assertEquals("InvalidParameterValue", tooLong.getErrorCode());
        assertEquals("Not a valid ruleSetName: " + "a".repeat(65), tooLong.getMessage());
    }

    @Test
    void clone_deepCopiesRulesWithNewTimestampAndInactive() {
        createThreeRuleSet("rules-src");
        service.createReceiptRule("rules-src",
                rule("r0", action("S3Action", "BucketName", "inbox", "ObjectKeyPrefix", "mail/")),
                null, REGION, ANY_SENDER_VERIFIED);
        service.setActiveReceiptRuleSet("rules-src", REGION);
        ReceiptRuleSet original = service.describeReceiptRuleSet("rules-src", REGION);

        ReceiptRuleSet clone = service.cloneReceiptRuleSet("rules-copy", "rules-src", REGION);

        assertEquals("rules-copy", clone.getName());
        assertEquals(List.of("r0", "r1", "r2", "r3"), ruleNames("rules-copy"));
        assertEquals("inbox", service.describeReceiptRule("rules-copy", "r0", REGION)
                .getActions().get(0).property("BucketName"));
        assertNotNull(clone.getCreatedTimestamp());
        assertFalse(clone.getCreatedTimestamp().isBefore(original.getCreatedTimestamp()));
        // The active flag stays with the original.
        assertFalse(clone.isActive());
        assertEquals("rules-src", service.describeActiveReceiptRuleSet(REGION).getName());
    }

    @Test
    void clone_isIndependentOfLaterSourceMutations() {
        createThreeRuleSet("rules-src");
        service.cloneReceiptRuleSet("rules-copy", "rules-src", REGION);

        service.deleteReceiptRule("rules-src", "r1", REGION);
        service.describeReceiptRule("rules-src", "r2", REGION)
                .getActions().add(action("StopAction", "Scope", "RuleSet"));

        assertEquals(List.of("r1", "r2", "r3"), ruleNames("rules-copy"));
        assertTrue(service.describeReceiptRule("rules-copy", "r2", REGION).getActions().isEmpty());
    }

    @Test
    void clone_emptySet_succeeds() {
        service.createReceiptRuleSet("rules-empty", REGION);
        service.cloneReceiptRuleSet("rules-copy", "rules-empty", REGION);
        assertEquals(List.of(), ruleNames("rules-copy"));
    }

    @Test
    void clone_missingSource_throwsRuleSetDoesNotExist() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.cloneReceiptRuleSet("rules-copy", "ghost", REGION));
        assertEquals("RuleSetDoesNotExist", e.getErrorCode());
        assertEquals("Rule set does not exist: ghost", e.getMessage());
    }

    @Test
    void clone_existingTarget_winsOverMissingSource() {
        service.createReceiptRuleSet("rules-a", REGION);
        AwsException e = assertThrows(AwsException.class,
                () -> service.cloneReceiptRuleSet("rules-a", "ghost", REGION));
        assertEquals("AlreadyExists", e.getErrorCode());
        assertEquals("Rule set already exists: rules-a", e.getMessage());
    }

    @Test
    void clone_nameValidation_isTwoLayer_targetBeforeSource() {
        // Smithy pattern layer combines both members, original first.
        AwsException bothBad = assertThrows(AwsException.class,
                () -> service.cloneReceiptRuleSet("bad target!", "bad source!", REGION));
        assertEquals("ValidationError", bothBad.getErrorCode());
        assertTrue(bothBad.getMessage().startsWith("2 validation errors detected: "));
        assertTrue(bothBad.getMessage().indexOf("'originalRuleSetName'")
                < bothBad.getMessage().indexOf("'ruleSetName'"));

        // The service-level length check reports the target before the source and does not
        // name the member.
        AwsException bothLong = assertThrows(AwsException.class,
                () -> service.cloneReceiptRuleSet("a".repeat(65), "b".repeat(65), REGION));
        assertEquals("InvalidParameterValue", bothLong.getErrorCode());
        assertEquals("Not a valid ruleSetName: " + "a".repeat(65), bothLong.getMessage());

        AwsException sourceLong = assertThrows(AwsException.class,
                () -> service.cloneReceiptRuleSet("rules-copy", "b".repeat(65), REGION));
        assertEquals("Not a valid ruleSetName: " + "b".repeat(65), sourceLong.getMessage());
    }

    // ---- receipt filters ----

    @Test
    void createFilter_thenList_roundTripsSortedByName() {
        service.createReceiptFilter(filter("zulu", "Block", "10.0.0.0/24"), REGION);
        service.createReceiptFilter(filter("alpha", "Allow", "192.0.2.10"), REGION);

        List<ReceiptFilter> listed = service.listReceiptFilters(REGION);
        assertEquals(List.of("alpha", "zulu"), listed.stream().map(ReceiptFilter::getName).toList());
        assertEquals("Allow", listed.get(0).getPolicy());
        assertEquals("192.0.2.10", listed.get(0).getCidr());
    }

    @Test
    void createFilter_duplicate_throwsAlreadyExists() {
        service.createReceiptFilter(filter("f1", "Block", "10.0.0.0/24"), REGION);
        AwsException e = assertThrows(AwsException.class,
                () -> service.createReceiptFilter(filter("f1", "Allow", "10.9.9.9"), REGION));
        assertEquals("AlreadyExists", e.getErrorCode());
        assertEquals("Filter already exists: f1", e.getMessage());
        assertEquals("Block", service.listReceiptFilters(REGION).get(0).getPolicy());
    }

    @Test
    void filterName_isSingleLayerServiceValidation() {
        // Unlike rule and rule-set names there is no Smithy pattern layer (probed): bad chars,
        // overlength, and bad boundaries all share one message, and empty has its own.
        for (String bad : new String[] {"bad name", "a".repeat(65), "-abc"}) {
            AwsException e = assertThrows(AwsException.class,
                    () -> service.createReceiptFilter(filter(bad, "Block", "10.0.0.0/24"), REGION));
            assertEquals("InvalidParameterValue", e.getErrorCode());
            assertEquals("Not a valid filterName: " + bad, e.getMessage());
        }
        AwsException empty = assertThrows(AwsException.class,
                () -> service.createReceiptFilter(filter("", "Block", "10.0.0.0/24"), REGION));
        assertEquals("filterName must not be empty", empty.getMessage());
    }

    @Test
    void filterShape_missingMembers_areSmithyViolations() {
        AwsException noIpFilter = assertThrows(AwsException.class,
                () -> service.createReceiptFilter(filter("f1", null, null), REGION));
        assertEquals("ValidationError", noIpFilter.getErrorCode());
        assertTrue(noIpFilter.getMessage().contains("'filter.ipFilter'"));

        AwsException noCidr = assertThrows(AwsException.class,
                () -> service.createReceiptFilter(filter("f1", "Block", null), REGION));
        assertTrue(noCidr.getMessage().contains("'filter.ipFilter.cidr'"));

        AwsException badPolicy = assertThrows(AwsException.class,
                () -> service.createReceiptFilter(filter("f1", "Bogus", "10.0.0.0/24"), REGION));
        assertTrue(badPolicy.getMessage().contains("'filter.ipFilter.policy'"));
        assertTrue(badPolicy.getMessage().contains("[Allow, Block]"));
    }

    @Test
    void cidr_validation_matchesProbedAcceptance() {
        service.createReceiptFilter(filter("single-ip", "Allow", "192.0.2.10"), REGION);
        service.createReceiptFilter(filter("ipv6", "Block", "2001:db8::/32"), REGION);

        // Probed: bracketed, zone-scoped, and IPv4-mapped IPv6 spellings are rejected too,
        // so the lenient InetAddress parser must not be used for validation.
        for (String bad : new String[] {"not-a-cidr", "", "10.0.0.0/33", "10.0.0/24", "10.0.0.0/",
                "[2001:db8::1]", "fe80::1%eth0", "::ffff:192.0.2.1", "2001:db8::/129",
                "1:2:3:4:5:6:7:8:9", "2001:db8:::1", "10.0.0.0/２４", "０:0:0:0:0:0:0:1"}) {
            AwsException e = assertThrows(AwsException.class,
                    () -> service.createReceiptFilter(filter("f-bad", "Block", bad), REGION));
            assertEquals("InvalidParameterValue", e.getErrorCode());
            assertEquals("Invalid CIDR block: " + bad, e.getMessage());
        }
    }

    @Test
    void deleteFilter_isIdempotent_butRequiresName() {
        service.createReceiptFilter(filter("f1", "Block", "10.0.0.0/24"), REGION);
        service.deleteReceiptFilter("f1", REGION);
        service.deleteReceiptFilter("f1", REGION);
        assertTrue(service.listReceiptFilters(REGION).isEmpty());

        AwsException e = assertThrows(AwsException.class,
                () -> service.deleteReceiptFilter(null, REGION));
        assertEquals("ValidationError", e.getErrorCode());
        assertTrue(e.getMessage().contains("'filterName'"));
    }

    @Test
    void createFilter_beyond100_throwsLimitExceeded() {
        for (int i = 1; i <= 100; i++) {
            service.createReceiptFilter(filter("f" + i, "Block", "10.0.0.0/24"), REGION);
        }
        AwsException e = assertThrows(AwsException.class,
                () -> service.createReceiptFilter(filter("f101", "Block", "10.0.0.0/24"), REGION));
        assertEquals("LimitExceeded", e.getErrorCode());
        assertEquals("Too many filters", e.getMessage());
    }
}
