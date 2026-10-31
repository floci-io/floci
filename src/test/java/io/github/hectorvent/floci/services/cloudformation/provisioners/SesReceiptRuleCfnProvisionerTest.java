package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceCallAuthorizer;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.ses.SesIdentityService;
import io.github.hectorvent.floci.services.ses.SesReceiptRuleService;
import io.github.hectorvent.floci.services.ses.model.ReceiptFilter;
import io.github.hectorvent.floci.services.ses.model.ReceiptRule;
import io.github.hectorvent.floci.services.ses.model.ReceiptRuleSet;
import io.github.hectorvent.floci.services.sns.SnsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SesReceiptRuleCfnProvisionerTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private final ObjectMapper mapper = new ObjectMapper();
    private SesReceiptRuleService rules;
    private SesReceiptRuleCfnProvisioner provisioner;

    @BeforeEach
    void setUp() {
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.<ReceiptRuleSet>create(eq("ses"), eq("ses-receipt-rule-sets.json"), any()))
                .thenReturn(AccountAwareStorageBackend.inMemory(ACCOUNT));
        when(factory.<ReceiptFilter>create(eq("ses"), eq("ses-receipt-filters.json"), any()))
                .thenReturn(AccountAwareStorageBackend.inMemory(ACCOUNT));
        rules = spy(new SesReceiptRuleService(factory, mock(S3Service.class), mock(SnsService.class),
                mock(LambdaService.class), mock(ServiceCallAuthorizer.class), new RegionResolver(REGION, ACCOUNT),
                Clock.systemUTC()));
        rules.createReceiptRuleSet("first", REGION);
        rules.createReceiptRuleSet("second", REGION);
        provisioner = new SesReceiptRuleCfnProvisioner(rules, mock(SesIdentityService.class));
    }

    @Test
    void generatedNameStaysStableAndExplicitNameRemovalCreatesReplacement() {
        StackResource generated = resource();
        ObjectNode props = properties("first", null);
        provisioner.provision(generated, props, context(null));
        String name = generated.getPhysicalId();
        assertTrue(name.length() <= 64);
        props.withObject("/Rule").put("Enabled", true);
        provisioner.provision(generated, props, context(name));
        assertEquals(name, generated.getPhysicalId());
        assertTrue(rules.describeReceiptRule("first", name, REGION).isEnabled());
        provisioner.clearUpdate(generated);

        StackResource named = resource();
        provisioner.provision(named, properties("second", "named"), context(null));
        provisioner.provision(named, properties("second", null), context("named"));
        assertNotEquals("named", named.getPhysicalId());
        assertTrue(provisioner.completeUpdate(named).complete());
        assertMissing("second", "named");
    }

    @Test
    void retainedSameNameReplacementKeepsBothRules() {
        StackResource resource = resource();
        provisioner.provision(resource, properties("first", "store"), context(null));
        resource.setUpdateReplacePolicy("Retain");
        provisioner.provision(resource, properties("second", "store"), context("store"));
        assertTrue(provisioner.hasReplacementUpdate(resource));
        assertNull(provisioner.updateCleanupPhysicalId(resource));
        assertTrue(provisioner.completeUpdate(resource).complete());
        provisioner.clearUpdate(resource);
        provisioner.delete(resource, REGION);
        assertEquals("store", rules.describeReceiptRule("first", "store", REGION).getName());
        assertMissing("second", "store");
    }

    @Test
    void replacementRollbackCanRetryFailedDeletionWithoutLosingEitherAddress() {
        StackResource resource = resource();
        provisioner.provision(resource, properties("first", "store"), context(null));
        provisioner.provision(resource, properties("second", "store"), context("store"));
        resource.setStatus("UPDATE_FAILED");
        doThrow(new AwsException("InternalFailure", "retry", 500)).doCallRealMethod()
                .when(rules).deleteReceiptRule("second", "store", REGION);
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(resource));
        assertTrue(provisioner.requiresUpdateRecovery(resource));
        assertEquals("store", resource.getPhysicalId());
        assertTrue(provisioner.rollbackUpdate(resource));
        assertFalse(provisioner.requiresUpdateRecovery(resource));
        assertEquals("store", rules.describeReceiptRule("first", "store", REGION).getName());
        assertMissing("second", "store");
        provisioner.delete(resource, REGION);
        assertMissing("first", "store");
    }

    @Test
    void failedStubMigrationRemovesOnlyTheNewlyCreatedRule() {
        StackResource resource = resource();
        resource.setPhysicalId("Receipt-1234abcd");
        resource.getAttributes().put("Arn", "arn:aws:stub:::Receipt");
        provisioner.provision(resource, properties("first", "store"), context("Receipt-1234abcd"));
        assertEquals("store", rules.describeReceiptRule("first", "store", REGION).getName());
        assertTrue(provisioner.rollbackUpdate(resource));
        assertEquals("Receipt-1234abcd", resource.getPhysicalId());
        assertEquals("arn:aws:stub:::Receipt", resource.getAttributes().get("Arn"));
        assertMissing("first", "store");
    }

    @Test
    void newUpdateRecoversAnUnfinishedUndoBeforeReplacingItsSnapshot() {
        StackResource resource = resource();
        ObjectNode original = properties("first", "store");
        original.withObject("/Rule").put("Enabled", true);
        provisioner.provision(resource, original, context(null));
        provisioner.provision(resource, properties("first", "store"), context("store"));
        doThrow(new AwsException("InternalFailure", "retry undo", 500)).doCallRealMethod()
                .when(rules).updateReceiptRule(eq("first"), argThat(ReceiptRule::isEnabled), eq(REGION), any());
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(resource));
        // The dispatcher passes a fresh resource object, so the previous UPDATE_FAILED status is absent.
        resource.setStatus("CREATE_IN_PROGRESS");
        provisioner.provision(resource, properties("first", "store"), context("store"));
        assertTrue(provisioner.rollbackUpdate(resource));
        assertTrue(rules.describeReceiptRule("first", "store", REGION).isEnabled());
    }

    @Test
    void cleanupFailureRemainsAddressableForRetryAndStackDelete() {
        StackResource resource = resource();
        provisioner.provision(resource, properties("first", "old"), context(null));
        provisioner.provision(resource, properties("second", "new"), context("old"));
        doThrow(new AwsException("InternalFailure", "retry", 500)).doCallRealMethod()
                .when(rules).deleteReceiptRule("first", "old", REGION);
        assertFalse(provisioner.completeUpdate(resource).complete());
        provisioner.clearUpdate(resource);
        assertEquals("old", provisioner.updateCleanupPhysicalId(resource));
        provisioner.delete(resource, REGION);
        assertMissing("first", "old");
        assertMissing("second", "new");
    }

    @Test
    void duplicateReplacementNeverAdoptsOrDeletesAnExternalRule() {
        StackResource resource = resource();
        provisioner.provision(resource, properties("first", "store"), context(null));
        ReceiptRule external = new ReceiptRule();
        external.setName("store");
        rules.createReceiptRule("second", external, null, REGION, sender -> false);
        assertEquals("AlreadyExists", assertThrows(AwsException.class, () ->
                provisioner.provision(resource, properties("second", "store"), context("store"))).getErrorCode());
        provisioner.delete(resource, REGION);
        assertEquals("store", rules.describeReceiptRule("second", "store", REGION).getName());
    }

    @Test
    void stackDeleteToleratesMissingSetButPropagatesRealFailures() {
        StackResource resource = resource();
        provisioner.provision(resource, properties("first", "store"), context(null));
        doThrow(new AwsException("InternalFailure", "unavailable", 500)).doCallRealMethod()
                .when(rules).deleteReceiptRule("first", "store", REGION);
        assertThrows(AwsException.class, () -> provisioner.delete(resource, REGION));
        rules.deleteReceiptRuleSet("first", REGION);
        assertDoesNotThrow(() -> provisioner.delete(resource, REGION));
    }

    @Test
    void acceptsEmptyActionsAsAllowedByTheCloudFormationSchema() {
        ObjectNode props = properties("first", "store");
        props.withObject("/Rule").putArray("Actions").addObject();
        provisioner.provision(resource(), props, context(null));
        assertNull(rules.describeReceiptRule("first", "store", REGION).getActions().getFirst().getType());
    }

    @Test
    void convertsNestedActionsAndResolvesIntrinsics() throws Exception {
        ObjectNode props = properties("first", "store");
        props.withObject("/Rule").set("Actions", mapper.readTree("""
                [{"AddHeaderAction":{"HeaderName":"X-Test","HeaderValue":{"Fn::Join":["",["a","b"]]}}},
                 {"StopAction":{"Scope":"RuleSet"}}]
                """));
        props.withObject("/Rule").putArray("Recipients").add("example.com");
        StackResource resource = resource();
        provisioner.provision(resource, props, context(null));
        ReceiptRule actual = rules.describeReceiptRule("first", "store", REGION);
        assertEquals(List.of("example.com"), actual.getRecipients());
        assertEquals("ab", actual.getActions().getFirst().property("HeaderValue"));
        assertEquals("StopAction", actual.getActions().get(1).getType());
        assertEquals("store", resource.getAttributes().get("RuleName"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"RuleSetName\":\"first\"}",
            "{\"RuleSetName\":\"first\",\"Rule\":{\"Name\":7}}",
            "{\"RuleSetName\":\"first\",\"Rule\":{\"Enabled\":\"yes\"}}",
            "{\"RuleSetName\":\"first\",\"Rule\":{\"Actions\":[{\"StopAction\":{},\"S3Action\":{}}]}}",
            "{\"RuleSetName\":\"first\",\"Rule\":{\"Actions\":[{\"BogusAction\":{}}]}}"})
    void rejectsInvalidPropertiesBeforeCreatingAnything(String json) throws Exception {
        assertThrows(AwsException.class, () -> provisioner.provision(resource(), mapper.readTree(json), context(null)));
        assertTrue(rules.describeReceiptRuleSet("first", REGION).getRules().isEmpty());
    }

    private void assertMissing(String set, String name) {
        assertEquals("RuleDoesNotExist", assertThrows(AwsException.class,
                () -> rules.describeReceiptRule(set, name, REGION)).getErrorCode());
    }

    private ObjectNode properties(String set, String name) {
        ObjectNode props = mapper.createObjectNode().put("RuleSetName", set);
        ObjectNode rule = props.putObject("Rule");
        if (name != null) {
            rule.put("Name", name);
        }
        return props;
    }

    private StackResource resource() {
        StackResource resource = new StackResource();
        resource.setResourceType("AWS::SES::ReceiptRule");
        resource.setLogicalId("Receipt");
        return resource;
    }

    private ProvisionContext context(String prior) {
        return new ProvisionContext(CloudFormationTemplateEngine.standalone(ACCOUNT, REGION, "stack", mapper,
                UnaryOperator.identity()), REGION, ACCOUNT, "stack", prior);
    }
}
