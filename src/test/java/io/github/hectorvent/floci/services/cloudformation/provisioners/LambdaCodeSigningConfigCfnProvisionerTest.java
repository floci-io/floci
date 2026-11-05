package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.lambda.LambdaCodeSigningConfigService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.CodeSigningConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LambdaCodeSigningConfigCfnProvisionerTest {

    private static final String REGION = "eu-west-1";
    private static final String ACCOUNT = "000000000000";
    private static final String CSC_ARN = "arn:aws:lambda:eu-west-1:000000000000:code-signing-config:csc-0123456789abcdef0";
    private static final String PROFILE_VERSION_ARN =
            "arn:aws:signer:eu-west-1:000000000000:/signing-profiles/my_profile/AbCdEfGhIj";

    private final LambdaCodeSigningConfigService configs = mock(LambdaCodeSigningConfigService.class);
    private final LambdaService lambda = mock(LambdaService.class);
    private final LambdaCodeSigningConfigCfnProvisioner provisioner =
            new LambdaCodeSigningConfigCfnProvisioner(configs, lambda);
    private final ObjectMapper mapper = new ObjectMapper();

    LambdaCodeSigningConfigCfnProvisionerTest() {
        when(configs.withConfigLock(anyString(), any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
    }

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null || node.isMissingNode() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        when(engine.resolveStringList(any())).thenAnswer(inv -> {
            List<String> values = new ArrayList<>();
            ((JsonNode) inv.getArgument(0)).forEach(node -> values.add(node.asText()));
            return values;
        });
        return new ProvisionContext(engine, REGION, ACCOUNT, "my-stack", priorPhysicalId);
    }

    private static StackResource config() {
        StackResource r = new StackResource();
        r.setLogicalId("Csc");
        r.setResourceType("AWS::Lambda::CodeSigningConfig");
        r.setAttributes(new HashMap<>());
        return r;
    }

    private ObjectNode props() {
        ObjectNode props = mapper.createObjectNode();
        props.putObject("AllowedPublishers").putArray("SigningProfileVersionArns").add(PROFILE_VERSION_ARN);
        return props;
    }

    private static CodeSigningConfig stored() {
        CodeSigningConfig config = new CodeSigningConfig();
        config.setCodeSigningConfigArn(CSC_ARN);
        config.setCodeSigningConfigId("csc-0123456789abcdef0");
        return config;
    }

    @Test
    void createRefsTheArnAndExposesBothAttributes() {
        when(configs.create(eq(REGION), eq(ACCOUNT), any(), any(), any())).thenReturn(stored());
        StackResource r = config();

        provisioner.provision(r, props(), ctx(null));

        assertEquals(CSC_ARN, r.getPhysicalId());
        assertEquals(CSC_ARN, r.getAttributes().get("CodeSigningConfigArn"));
        assertEquals("csc-0123456789abcdef0", r.getAttributes().get("CodeSigningConfigId"));
    }

    @Test
    void createPassesThePublishersAndTheDefaults() {
        when(configs.create(eq(REGION), eq(ACCOUNT), any(), any(), any())).thenReturn(stored());
        ArgumentCaptor<CodeSigningConfig.AllowedPublishers> publishers =
                ArgumentCaptor.forClass(CodeSigningConfig.AllowedPublishers.class);
        ArgumentCaptor<CodeSigningConfig.CodeSigningPolicies> policies =
                ArgumentCaptor.forClass(CodeSigningConfig.CodeSigningPolicies.class);

        provisioner.provision(config(), props(), ctx(null));

        verify(configs).create(eq(REGION), eq(ACCOUNT), eq(""), publishers.capture(), policies.capture());
        assertEquals(List.of(PROFILE_VERSION_ARN), publishers.getValue().getSigningProfileVersionArns());
        assertEquals("Warn", policies.getValue().getUntrustedArtifactOnDeployment());
    }

    @Test
    void absentPropertiesLeaveTheMissingPublishersForLambdaToReject() {
        when(configs.create(eq(REGION), eq(ACCOUNT), any(), any(), any())).thenReturn(stored());
        ArgumentCaptor<CodeSigningConfig.AllowedPublishers> publishers =
                ArgumentCaptor.forClass(CodeSigningConfig.AllowedPublishers.class);

        provisioner.provision(config(), null, ctx(null));

        verify(configs).create(eq(REGION), eq(ACCOUNT), eq(""), publishers.capture(), any());
        assertEquals(List.of(), publishers.getValue().getSigningProfileVersionArns());
    }

    @Test
    void updateAppliesTheTemplateInPlace() {
        when(configs.get(REGION, CSC_ARN)).thenReturn(stored());
        when(configs.update(eq(REGION), eq(CSC_ARN), any(), any(), any())).thenReturn(stored());
        ObjectNode props = props().put("Description", "signed");
        props.putObject("CodeSigningPolicies").put("UntrustedArtifactOnDeployment", "Enforce");
        ArgumentCaptor<CodeSigningConfig.CodeSigningPolicies> policies =
                ArgumentCaptor.forClass(CodeSigningConfig.CodeSigningPolicies.class);
        StackResource r = config();
        r.setPhysicalId(CSC_ARN);

        provisioner.provision(r, props, ctx(CSC_ARN));

        verify(configs).withConfigLock(eq(CSC_ARN), any());
        verify(configs).update(eq(REGION), eq(CSC_ARN), eq("signed"), any(), policies.capture());
        verify(configs, never()).create(any(), any(), any(), any(), any());
        assertEquals("Enforce", policies.getValue().getUntrustedArtifactOnDeployment());
        assertEquals(CSC_ARN, r.getPhysicalId());
    }

    @Test
    void rollbackRestoresWhatTheUpdateChanged() {
        CodeSigningConfig before = stored();
        before.setDescription("before");
        CodeSigningConfig.AllowedPublishers publishers = new CodeSigningConfig.AllowedPublishers();
        publishers.setSigningProfileVersionArns(List.of(PROFILE_VERSION_ARN));
        before.setAllowedPublishers(publishers);
        CodeSigningConfig.CodeSigningPolicies policies = new CodeSigningConfig.CodeSigningPolicies();
        policies.setUntrustedArtifactOnDeployment("Warn");
        before.setCodeSigningPolicies(policies);
        when(configs.get(REGION, CSC_ARN)).thenReturn(before);
        when(configs.update(eq(REGION), eq(CSC_ARN), any(), any(), any())).thenReturn(stored());
        StackResource r = config();
        r.setPhysicalId(CSC_ARN);
        provisioner.provision(r, props().put("Description", "signed"), ctx(CSC_ARN));
        ArgumentCaptor<CodeSigningConfig.AllowedPublishers> restoredPublishers =
                ArgumentCaptor.forClass(CodeSigningConfig.AllowedPublishers.class);
        ArgumentCaptor<CodeSigningConfig.CodeSigningPolicies> restoredPolicies =
                ArgumentCaptor.forClass(CodeSigningConfig.CodeSigningPolicies.class);

        assertTrue(provisioner.rollbackUpdate(r));

        verify(configs).update(eq(REGION), eq(CSC_ARN), eq("before"), restoredPublishers.capture(),
                restoredPolicies.capture());
        assertEquals(List.of(PROFILE_VERSION_ARN), restoredPublishers.getValue().getSigningProfileVersionArns());
        assertEquals("Warn", restoredPolicies.getValue().getUntrustedArtifactOnDeployment());
        assertEquals(CSC_ARN, r.getPhysicalId());
    }

    @Test
    void rollbackOfAStubMigrationDeletesTheCreatedConfig() {
        when(configs.create(eq(REGION), eq(ACCOUNT), any(), any(), any())).thenReturn(stored());
        StackResource r = config();
        r.setPhysicalId("Csc-0a1b2c3d");
        r.getAttributes().put("Arn", "arn:aws:stub:::Csc");
        provisioner.provision(r, props(), ctx("Csc-0a1b2c3d"));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(lambda).deleteCodeSigningConfig(REGION, ACCOUNT, CSC_ARN);
        assertEquals("Csc-0a1b2c3d", r.getPhysicalId());
    }

    @Test
    void configARollbackCouldNotDeleteIsDeletedByTheNextCleanup() {
        when(configs.create(eq(REGION), eq(ACCOUNT), any(), any(), any())).thenReturn(stored());
        doThrow(new AwsException("ResourceConflictException", "attached", 409)).doNothing()
                .when(lambda).deleteCodeSigningConfig(REGION, ACCOUNT, CSC_ARN);
        StackResource r = config();
        r.setPhysicalId("Csc-0a1b2c3d");
        r.getAttributes().put("Arn", "arn:aws:stub:::Csc");
        provisioner.provision(r, props(), ctx("Csc-0a1b2c3d"));
        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));

        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals(CSC_ARN, provisioner.updateCleanupPhysicalId(r));
        assertTrue(provisioner.completeUpdate(r).complete());
        verify(lambda, times(2)).deleteCodeSigningConfig(REGION, ACCOUNT, CSC_ARN);
    }

    @Test
    void whitespaceDescriptionIsKept() {
        when(configs.create(eq(REGION), eq(ACCOUNT), any(), any(), any())).thenReturn(stored());

        provisioner.provision(config(), props().put("Description", "   "), ctx(null));

        verify(configs).create(eq(REGION), eq(ACCOUNT), eq("   "), any(), any());
    }

    @Test
    void blankPolicyIsLeftForLambdaToReject() {
        when(configs.create(eq(REGION), eq(ACCOUNT), any(), any(), any())).thenReturn(stored());
        ObjectNode props = props();
        props.putObject("CodeSigningPolicies").put("UntrustedArtifactOnDeployment", " ");
        ArgumentCaptor<CodeSigningConfig.CodeSigningPolicies> policies =
                ArgumentCaptor.forClass(CodeSigningConfig.CodeSigningPolicies.class);

        provisioner.provision(config(), props, ctx(null));

        verify(configs).create(eq(REGION), eq(ACCOUNT), eq(""), any(), policies.capture());
        assertEquals(" ", policies.getValue().getUntrustedArtifactOnDeployment());
    }

    @Test
    void updateOfAStubFromBeforeThisProvisionerCreatesTheConfig() {
        when(configs.create(eq(REGION), eq(ACCOUNT), any(), any(), any())).thenReturn(stored());
        StackResource r = config();
        r.setPhysicalId("Csc-0a1b2c3d");

        provisioner.provision(r, props(), ctx("Csc-0a1b2c3d"));

        verify(configs, never()).update(any(), any(), any(), any(), any());
        assertEquals(CSC_ARN, r.getPhysicalId());
    }

    @Test
    void deleteOfAStubTouchesNothing() {
        provisioner.delete("AWS::Lambda::CodeSigningConfig", "Csc-0a1b2c3d", REGION);

        verify(lambda, never()).deleteCodeSigningConfig(any(), any(), any());
    }

    @Test
    void deleteRemovesTheConfigThroughLambda() {
        provisioner.delete("AWS::Lambda::CodeSigningConfig", CSC_ARN, REGION);

        verify(lambda).deleteCodeSigningConfig(REGION, ACCOUNT, CSC_ARN);
    }

    @Test
    void deleteToleratesAConfigAlreadyGone() {
        doThrow(new AwsException("ResourceNotFoundException", "gone", 404))
                .when(lambda).deleteCodeSigningConfig(REGION, ACCOUNT, CSC_ARN);

        provisioner.delete("AWS::Lambda::CodeSigningConfig", CSC_ARN, REGION);
    }

    @Test
    void deleteLooksTheConfigUpInTheArnsOwnAccount() {
        String foreign = "arn:aws:lambda:eu-west-1:111111111111:code-signing-config:csc-0123456789abcdef0";
        doThrow(new AwsException("ResourceNotFoundException", "not found", 404))
                .when(lambda).deleteCodeSigningConfig(REGION, "111111111111", foreign);

        provisioner.delete("AWS::Lambda::CodeSigningConfig", foreign, REGION);

        verify(lambda).deleteCodeSigningConfig(REGION, "111111111111", foreign);
    }

    @Test
    void deleteRefusesAConfigStillAttachedToAFunction() {
        doThrow(new AwsException("ResourceConflictException", "attached", 409))
                .when(lambda).deleteCodeSigningConfig(REGION, ACCOUNT, CSC_ARN);

        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.delete("AWS::Lambda::CodeSigningConfig", CSC_ARN, REGION));

        assertEquals("ResourceConflictException", e.getErrorCode());
    }
}
