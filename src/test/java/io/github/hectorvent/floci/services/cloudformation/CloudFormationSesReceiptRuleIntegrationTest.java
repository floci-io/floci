package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ses.SesReceiptRuleService;
import io.github.hectorvent.floci.services.ses.model.ReceiptRule;
import io.github.hectorvent.floci.services.ssm.SsmService;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Parameter;
import software.amazon.awssdk.services.cloudformation.model.Stack;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CloudFormationSesReceiptRuleIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private CloudFormationClient cfn;
    @Inject
    SesReceiptRuleService ses;
    @Inject
    CloudFormationService cloudFormation;
    @Inject
    SsmService ssm;
    private String stackName;
    private String ruleSet;

    @BeforeEach
    void setUp() {
        cfn = AwsTestClients.create(CloudFormationClient.builder(), "test", Region.US_EAST_1);
        stackName = "cfn-receipt-" + Long.toString(System.nanoTime(), 36);
        ruleSet = stackName + "-set";
        ses.createReceiptRuleSet(ruleSet, "us-east-1");
        ses.createReceiptRuleSet(ruleSet + "-next", "us-east-1");
    }

    @AfterEach
    void cleanUp() {
        try {
            cfn.deleteStack(r -> r.stackName(stackName));
            await().atMost(Duration.ofSeconds(15)).until(() -> {
                try {
                    return cfn.describeStacks(r -> r.stackName(stackName)).stacks().isEmpty();
                } catch (CloudFormationException expected) {
                    return expected.statusCode() == 400;
                }
            });
        } finally {
            ses.deleteReceiptRuleSet(ruleSet, "us-east-1");
            ses.deleteReceiptRuleSet(ruleSet + "-next", "us-east-1");
            cfn.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sameNamedRuleInAnotherSetReplacesTheOldEntity(boolean retain) throws Exception {
        ObjectNode initial = (ObjectNode) mapper.readTree(template("store", true));
        if (retain) {
            initial.withObject("/Resources/Rule").put("UpdateReplacePolicy", "Retain");
        }
        cfn.createStack(r -> r.stackName(stackName).templateBody(initial.toString()));
        awaitStatus("CREATE_COMPLETE");
        ObjectNode updated = (ObjectNode) mapper.readTree(template("store", false));
        updated.withObject("/Resources/Rule/Properties").put("RuleSetName", ruleSet + "-next");
        if (retain) {
            updated.withObject("/Resources/Rule").put("UpdateReplacePolicy", "Retain");
        }
        cfn.updateStack(r -> r.stackName(stackName).templateBody(updated.toString()));
        awaitStatus("UPDATE_COMPLETE");
        assertFalse(ses.describeReceiptRule(ruleSet + "-next", "store", "us-east-1").isEnabled());
        if (retain) {
            assertTrue(ses.describeReceiptRule(ruleSet, "store", "us-east-1").isEnabled());
        } else {
            assertEquals("RuleDoesNotExist", assertThrows(AwsException.class,
                    () -> ses.describeReceiptRule(ruleSet, "store", "us-east-1")).getErrorCode());
        }
    }

    @Test
    void failedPositionChangeRestoresRuleContentsAndOriginalPosition() throws Exception {
        ReceiptRule anchor = new ReceiptRule();
        anchor.setName("anchor");
        ses.createReceiptRule(ruleSet, anchor, null, "us-east-1", sender -> false);
        ObjectNode initial = (ObjectNode) mapper.readTree(template("store", true));
        initial.withObject("/Resources/Rule/Properties").put("After", "anchor");
        cfn.createStack(r -> r.stackName(stackName).templateBody(initial.toString()));
        awaitStatus("CREATE_COMPLETE");
        ObjectNode updated = (ObjectNode) mapper.readTree(template("store", false));
        updated.withObject("/Resources/Rule/Properties").put("After", "missing");
        cfn.updateStack(r -> r.stackName(stackName).templateBody(updated.toString()));
        awaitStatus("UPDATE_ROLLBACK_COMPLETE");
        assertTrue(ses.describeReceiptRule(ruleSet, "store", "us-east-1").isEnabled());
        assertEquals(List.of("anchor", "store"), ses.describeReceiptRuleSet(ruleSet, "us-east-1")
                .getRules().stream().map(ReceiptRule::getName).toList());
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void contentOnlyUpdatesKeepTheExistingOrderAfterLegacyMigration(boolean explicitAfter, boolean legacy) throws Exception {
        ReceiptRule anchor = new ReceiptRule();
        anchor.setName("anchor");
        ses.createReceiptRule(ruleSet, anchor, null, "us-east-1", sender -> false);
        ObjectNode template = (ObjectNode) mapper.readTree(template("first", true));
        ObjectNode second = template.withObject("/Resources").putObject("Second");
        second.put("Type", "AWS::SES::ReceiptRule").put("DependsOn", "Rule");
        ObjectNode secondProperties = second.putObject("Properties").put("RuleSetName", ruleSet);
        secondProperties.putObject("Rule").put("Name", "second");
        if (explicitAfter) {
            template.withObject("/Resources/Rule/Properties").put("After", "anchor");
            secondProperties.put("After", "anchor");
        }
        cfn.createStack(r -> r.stackName(stackName).templateBody(template.toString()));
        awaitStatus("CREATE_COMPLETE");
        List<String> expected = explicitAfter ? List.of("anchor", "second", "first")
                : List.of("second", "first", "anchor");
        assertEquals(expected, ruleNames());
        if (legacy) {
            cloudFormation.describeStacks(stackName, "us-east-1", "000000000000").getFirst()
                    .getResources().get("Rule").getAttributes().remove("__FlociSesReceiptRuleAfter");
        }
        template.withObject("/Resources/Rule/Properties/Rule").put("Enabled", false);
        cfn.updateStack(r -> r.stackName(stackName).templateBody(template.toString()));
        awaitStatus("UPDATE_COMPLETE");
        List<String> migratedOrder = explicitAfter ? List.of("anchor", "first", "second")
                : List.of("first", "second", "anchor");
        assertEquals(legacy ? migratedOrder : expected, ruleNames());
        assertFalse(ses.describeReceiptRule(ruleSet, "first", "us-east-1").isEnabled());
        template.withObject("/Resources/Rule/Properties/Rule").put("Enabled", true);
        cfn.updateStack(r -> r.stackName(stackName).templateBody(template.toString()));
        awaitStatus("UPDATE_COMPLETE");
        assertEquals(legacy ? migratedOrder : expected, ruleNames());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void parameterChangesRepositionEvenWhenTheAfterExpressionIsUnchanged(boolean legacy) throws Exception {
        for (String name : List.of("anchor", "other")) {
            ReceiptRule rule = new ReceiptRule();
            rule.setName(name);
            ses.createReceiptRule(ruleSet, rule, null, "us-east-1", sender -> false);
        }
        ObjectNode template = (ObjectNode) mapper.readTree(template("store", true));
        template.putObject("Parameters").putObject("AfterAnchor").put("Type", "String");
        template.withObject("/Resources/Rule/Properties").putObject("After").put("Ref", "AfterAnchor");
        cfn.createStack(r -> r.stackName(stackName).templateBody(template.toString()).parameters(
                Parameter.builder().parameterKey("AfterAnchor").parameterValue("anchor").build()));
        awaitStatus("CREATE_COMPLETE");
        assertEquals(List.of("other", "anchor", "store"), ruleNames());
        if (legacy) {
            cloudFormation.describeStacks(stackName, "us-east-1", "000000000000").getFirst()
                    .getResources().get("Rule").getAttributes().remove("__FlociSesReceiptRuleAfter");
        }
        cfn.updateStack(r -> r.stackName(stackName).templateBody(template.toString()).parameters(
                Parameter.builder().parameterKey("AfterAnchor").parameterValue("other").build()));
        awaitStatus("UPDATE_COMPLETE");
        assertEquals(List.of("other", "store", "anchor"), ruleNames());
    }

    @Test
    void typedGetAttKeepsThePositionOnAContentOnlyUpdate() throws Exception {
        ReceiptRule anchor = new ReceiptRule();
        anchor.setName("management");
        ses.createReceiptRule(ruleSet, anchor, null, "us-east-1", sender -> false);
        ObjectNode template = (ObjectNode) mapper.readTree(template("store", true));
        ObjectNode resources = template.withObject("/Resources");
        resources.putObject("Topic").put("Type", "AWS::SNS::Topic").putObject("Properties")
                .put("TopicName", stackName + "-events");
        ObjectNode subscription = resources.putObject("Subscription").put("Type", "AWS::Redshift::EventSubscription")
                .putObject("Properties").put("SubscriptionName", stackName + "-subscription")
                .put("SourceType", "cluster");
        subscription.putObject("SnsTopicArn").put("Ref", "Topic");
        subscription.putArray("EventCategories").add("management");
        template.withObject("/Resources/Rule/Properties").putObject("After").putArray("Fn::Select")
                .add(0).addObject().putArray("Fn::GetAtt").add("Subscription").add("EventCategoriesList");
        ObjectNode second = resources.putObject("Second").put("Type", "AWS::SES::ReceiptRule")
                .put("DependsOn", "Rule").putObject("Properties").put("RuleSetName", ruleSet)
                .put("After", "management");
        second.putObject("Rule").put("Name", "between");
        cfn.createStack(r -> r.stackName(stackName).templateBody(template.toString()));
        awaitStatus("CREATE_COMPLETE");
        assertEquals(List.of("management", "between", "store"), ruleNames());
        template.withObject("/Resources/Rule/Properties/Rule").put("Enabled", false);
        cfn.updateStack(r -> r.stackName(stackName).templateBody(template.toString()));
        awaitStatus("UPDATE_COMPLETE");
        assertEquals(List.of("management", "between", "store"), ruleNames());
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void dynamicReferenceChangesApplyTheCurrentAnchor(boolean legacy, boolean indexReference) throws Exception {
        for (String name : List.of("anchor", "other")) {
            ReceiptRule rule = new ReceiptRule();
            rule.setName(name);
            ses.createReceiptRule(ruleSet, rule, null, "us-east-1", sender -> false);
        }
        String parameter = "/" + stackName + "/anchor";
        ssm.putParameter(parameter, indexReference ? "1" : "anchor", "String", null, true, "us-east-1");
        try {
            ObjectNode template = (ObjectNode) mapper.readTree(template("store", true));
            String reference = "{{resolve:ssm:" + parameter + "}}";
            ObjectNode properties = template.withObject("/Resources/Rule/Properties");
            if (indexReference) {
                properties.putObject("After").putArray("Fn::Select").add(reference)
                        .addArray().add("other").add("anchor");
            } else {
                properties.put("After", reference);
            }
            cfn.createStack(r -> r.stackName(stackName).templateBody(template.toString()));
            awaitStatus("CREATE_COMPLETE");
            assertEquals(List.of("other", "anchor", "store"), ruleNames());
            if (legacy) {
                cloudFormation.describeStacks(stackName, "us-east-1", "000000000000").getFirst()
                        .getResources().get("Rule").getAttributes().remove("__FlociSesReceiptRuleAfter");
            }
            ssm.putParameter(parameter, indexReference ? "0" : "other", "String", null, true, "us-east-1");
            template.withObject("/Resources/Rule/Properties/Rule").put("Enabled", false);
            cfn.updateStack(r -> r.stackName(stackName).templateBody(template.toString()));
            awaitStatus("UPDATE_COMPLETE");
            assertEquals(List.of("other", "store", "anchor"), ruleNames());
            assertFalse(ses.describeReceiptRule(ruleSet, "store", "us-east-1").isEnabled());
        } finally {
            ssm.deleteParameter(parameter, "us-east-1");
        }
    }

    @Test
    void changingAndRemovingAfterStillRepositionsTheRule() throws Exception {
        ReceiptRule anchor = new ReceiptRule();
        anchor.setName("anchor");
        ses.createReceiptRule(ruleSet, anchor, null, "us-east-1", sender -> false);
        ObjectNode template = (ObjectNode) mapper.readTree(template("first", true));
        template.withObject("/Resources/Rule/Properties").put("After", "anchor");
        ObjectNode second = template.withObject("/Resources").putObject("Second");
        second.put("Type", "AWS::SES::ReceiptRule").put("DependsOn", "Rule");
        second.putObject("Properties").put("RuleSetName", ruleSet).putObject("Rule").put("Name", "second");
        cfn.createStack(r -> r.stackName(stackName).templateBody(template.toString()));
        awaitStatus("CREATE_COMPLETE");
        assertEquals(List.of("second", "anchor", "first"), ruleNames());
        template.withObject("/Resources/Rule/Properties").put("After", "second");
        cfn.updateStack(r -> r.stackName(stackName).templateBody(template.toString()));
        awaitStatus("UPDATE_COMPLETE");
        assertEquals(List.of("second", "first", "anchor"), ruleNames());
        template.withObject("/Resources/Rule/Properties").remove("After");
        cfn.updateStack(r -> r.stackName(stackName).templateBody(template.toString()));
        awaitStatus("UPDATE_COMPLETE");
        assertEquals(List.of("first", "second", "anchor"), ruleNames());
    }

    private List<String> ruleNames() {
        return ses.describeReceiptRuleSet(ruleSet, "us-east-1").getRules().stream().map(ReceiptRule::getName).toList();
    }

    @Test
    void provisionsTheIssueTemplateWithAnS3TargetResolvedFromRef() throws Exception {
        ObjectNode template = (ObjectNode) mapper.readTree(template("store", true));
        template.withObject("/Resources").putObject("Bucket").put("Type", "AWS::S3::Bucket");
        template.withObject("/Resources/Rule/Properties/Rule").putArray("Actions")
                .addObject().putObject("S3Action").putObject("BucketName").put("Ref", "Bucket");
        template.withObject("/Outputs").putObject("BucketName").putObject("Value").put("Ref", "Bucket");
        cfn.createStack(r -> r.stackName(stackName).templateBody(template.toString()));
        Stack created = awaitStatus("CREATE_COMPLETE");
        String bucket = created.outputs().stream().filter(o -> "BucketName".equals(o.outputKey()))
                .findFirst().orElseThrow().outputValue();
        ReceiptRule rule = ses.describeReceiptRule(ruleSet, "store", "us-east-1");
        assertEquals("S3Action", rule.getActions().getFirst().getType());
        assertEquals(bucket, rule.getActions().getFirst().property("BucketName"));
    }

    @Test
    void provisionsUpdatesAndDeletesRealReceiptRules() throws Exception {
        String initial = template("store", true);
        cfn.createStack(r -> r.stackName(stackName).templateBody(initial));
        Stack created = awaitStatus("CREATE_COMPLETE");
        assertTrue(ses.describeReceiptRule(ruleSet, "store", "us-east-1").isEnabled());
        assertTrue(created.outputs().stream().allMatch(o -> "store".equals(o.outputValue())));

        String updated = template("store", false);
        cfn.updateStack(r -> r.stackName(stackName).templateBody(updated));
        awaitStatus("UPDATE_COMPLETE");
        assertFalse(ses.describeReceiptRule(ruleSet, "store", "us-east-1").isEnabled());

        cfn.deleteStack(r -> r.stackName(stackName));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertEquals("RuleDoesNotExist", assertThrows(AwsException.class,
                        () -> ses.describeReceiptRule(ruleSet, "store", "us-east-1")).getErrorCode()));
    }

    private Stack awaitStatus(String expected) {
        return await().atMost(Duration.ofSeconds(15)).until(
                () -> cfn.describeStacks(r -> r.stackName(stackName)).stacks().getFirst(),
                s -> expected.equals(s.stackStatusAsString()));
    }

    private String template(String name, boolean enabled) throws Exception {
        return mapper.writeValueAsString(Map.of("Resources", Map.of("Rule", Map.of(
                "Type", "AWS::SES::ReceiptRule", "Properties", Map.of("RuleSetName", ruleSet,
                        "Rule", Map.of("Name", name, "Enabled", enabled, "Recipients", List.of("example.com"),
                                "Actions", List.of(Map.of("AddHeaderAction",
                                        Map.of("HeaderName", "X-Test", "HeaderValue", "yes"))))))),
                "Outputs", Map.of("RuleRef", Map.of("Value", Map.of("Ref", "Rule")),
                        "RuleName", Map.of("Value", Map.of("Fn::GetAtt", List.of("Rule", "RuleName"))))));
    }
}
