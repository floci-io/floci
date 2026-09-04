package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.iot.IotDomainConfigurationService;
import io.github.hectorvent.floci.services.iot.model.IotDomainConfiguration;
import io.github.hectorvent.floci.services.iot.model.IotDomainConfiguration.AuthorizerConfig;
import io.github.hectorvent.floci.services.iot.model.IotDomainConfiguration.ServerCertificateSummary;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The IoT domain configuration CFN provisioner in isolation: one mocked service. Every case
 * asserts the exact physical id and the exact {@code Fn::GetAtt} attribute keys, because an
 * unmapped type still reports CREATE_COMPLETE through the dispatcher's stub arm.
 */
class IotDomainConfigurationCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String TYPE = "AWS::IoT::DomainConfiguration";
    private static final String NAME = "iot-domain";
    private static final String CERTIFICATE_ARN =
            "arn:aws:acm:us-east-1:000000000000:certificate/11111111-1111-1111-1111-111111111111";
    private static final String ARN = "arn:aws:iot:us-east-1:000000000000:domainconfiguration/iot-domain/abcde";
    private static final String SERVER_CERTIFICATES_JSON =
            "[{\"ServerCertificateArn\":\"" + CERTIFICATE_ARN + "\",\"ServerCertificateStatus\":\"VALID\"}]";

    private final IotDomainConfigurationService service = mock(IotDomainConfigurationService.class);
    private final IotDomainConfigurationCfnProvisioner provisioner = new IotDomainConfigurationCfnProvisioner(service);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        return ctx(null);
    }

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, REGION, "000000000000", "my-stack", priorPhysicalId);
    }

    private static StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("Domain");
        r.setResourceType(TYPE);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private ObjectNode customDomainProps(String name) {
        ObjectNode props = mapper.createObjectNode().put("DomainName", "iot.example.com");
        if (name != null) {
            props.put("DomainConfigurationName", name);
        }
        props.putArray("ServerCertificateArns").add(CERTIFICATE_ARN);
        return props;
    }

    private static IotDomainConfiguration stored(String name, String arn, String domainName, String status) {
        IotDomainConfiguration configuration = new IotDomainConfiguration();
        configuration.setDomainConfigurationName(name);
        configuration.setDomainConfigurationArn(arn);
        configuration.setDomainName(domainName);
        configuration.setServiceType("DATA");
        configuration.setDomainConfigurationStatus(status);
        configuration.setDomainType(domainName == null ? "ENDPOINT" : "CUSTOMER_MANAGED");
        configuration.setServerCertificates(domainName == null
                ? List.of()
                : List.of(new ServerCertificateSummary(CERTIFICATE_ARN, "VALID", null)));
        return configuration;
    }

    private JsonNode capturedCreateBody(String name) {
        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).createDomainConfiguration(eq(name), captor.capture(), eq(REGION));
        return captor.getValue();
    }

    private JsonNode capturedUpdateBody(String name) {
        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(service).updateDomainConfiguration(eq(name), captor.capture(), eq(REGION));
        return captor.getValue();
    }

    @Test
    void createSetsTheNameAsPhysicalIdAndTheThreeSchemaAttributes() {
        when(service.createDomainConfiguration(eq(NAME), any(), eq(REGION)))
                .thenReturn(stored(NAME, ARN, "iot.example.com", "ENABLED"));
        StackResource r = resource();

        provisioner.provision(r, customDomainProps(NAME), ctx());

        assertEquals(NAME, r.getPhysicalId());
        assertEquals(Map.of("Arn", ARN, "DomainType", "CUSTOMER_MANAGED", "ServerCertificates", SERVER_CERTIFICATES_JSON),
                r.getAttributes());
        verify(service, never()).updateDomainConfiguration(any(), any(), any());
    }

    @Test
    void createPassesTemplatePropertiesToTheServiceInApiShape() {
        when(service.createDomainConfiguration(eq(NAME), any(), eq(REGION)))
                .thenReturn(stored(NAME, ARN, "iot.example.com", "ENABLED"));
        ObjectNode props = customDomainProps(NAME)
                .put("ServiceType", "JOBS")
                .put("ValidationCertificateArn", CERTIFICATE_ARN)
                .put("AuthenticationType", "CUSTOM_AUTH")
                .put("ApplicationProtocol", "MQTT_WSS");
        props.putObject("AuthorizerConfig").put("DefaultAuthorizerName", "my-authorizer").put("AllowAuthorizerOverride", true);
        props.putObject("TlsConfig").put("SecurityPolicy", "IoTSecurityPolicy_TLS12_1_2_2022_10");
        props.putObject("ServerCertificateConfig").put("EnableOCSPCheck", true)
                .put("OcspLambdaArn", "arn:aws:lambda:us-east-1:000000000000:function:ocsp");
        props.putObject("ClientCertificateConfig")
                .put("ClientCertificateCallbackArn", "arn:aws:lambda:us-east-1:000000000000:function:callback");
        props.putArray("Tags").addObject().put("Key", "env").put("Value", "test");

        provisioner.provision(resource(), props, ctx());

        JsonNode body = capturedCreateBody(NAME);
        assertEquals("iot.example.com", body.path("domainName").asText());
        assertEquals("JOBS", body.path("serviceType").asText());
        assertEquals(CERTIFICATE_ARN, body.path("serverCertificateArns").get(0).asText());
        assertEquals(CERTIFICATE_ARN, body.path("validationCertificateArn").asText());
        assertEquals("my-authorizer", body.path("authorizerConfig").path("defaultAuthorizerName").asText());
        assertTrue(body.path("authorizerConfig").path("allowAuthorizerOverride").isBoolean());
        assertTrue(body.path("authorizerConfig").path("allowAuthorizerOverride").asBoolean());
        assertEquals("IoTSecurityPolicy_TLS12_1_2_2022_10", body.path("tlsConfig").path("securityPolicy").asText());
        assertTrue(body.path("serverCertificateConfig").path("enableOCSPCheck").asBoolean());
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:ocsp",
                body.path("serverCertificateConfig").path("ocspLambdaArn").asText());
        assertEquals("CUSTOM_AUTH", body.path("authenticationType").asText());
        assertEquals("MQTT_WSS", body.path("applicationProtocol").asText());
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:callback",
                body.path("clientCertificateConfig").path("clientCertificateCallbackArn").asText());
        assertEquals("env", body.path("tags").get(0).path("Key").asText());
        assertEquals("test", body.path("tags").get(0).path("Value").asText());
        assertFalse(body.has("domainConfigurationName"));
        assertFalse(body.has("domainConfigurationStatus"));
        assertFalse(body.has("Tags"));
    }

    @Test
    void endpointConfigurationWithoutADomainNameReportsItsTypeAndAnEmptyCertificateList() {
        when(service.createDomainConfiguration(eq(NAME), any(), eq(REGION)))
                .thenReturn(stored(NAME, ARN, null, "ENABLED"));
        StackResource r = resource();

        provisioner.provision(r, mapper.createObjectNode().put("DomainConfigurationName", NAME), ctx());

        assertEquals(Map.of("Arn", ARN, "DomainType", "ENDPOINT", "ServerCertificates", "[]"), r.getAttributes());
    }

    @Test
    void disabledStatusIsAppliedWithAFollowUpUpdateLikeTheCloudFormationHandler() {
        when(service.createDomainConfiguration(eq(NAME), any(), eq(REGION)))
                .thenReturn(stored(NAME, ARN, "iot.example.com", "ENABLED"));
        when(service.updateDomainConfiguration(eq(NAME), any(), eq(REGION)))
                .thenReturn(stored(NAME, ARN, "iot.example.com", "DISABLED"));
        StackResource r = resource();

        provisioner.provision(r, customDomainProps(NAME).put("DomainConfigurationStatus", "DISABLED"), ctx());

        InOrder order = inOrder(service);
        order.verify(service).createDomainConfiguration(eq(NAME), any(), eq(REGION));
        order.verify(service).updateDomainConfiguration(eq(NAME), any(), eq(REGION));
        JsonNode update = capturedUpdateBody(NAME);
        assertEquals("DISABLED", update.path("domainConfigurationStatus").asText());
        assertEquals(1, update.size());
        assertEquals(NAME, r.getPhysicalId());
        assertEquals(ARN, r.getAttributes().get("Arn"));
    }

    @Test
    void enabledStatusNeedsNoFollowUpUpdate() {
        when(service.createDomainConfiguration(eq(NAME), any(), eq(REGION)))
                .thenReturn(stored(NAME, ARN, "iot.example.com", "ENABLED"));

        provisioner.provision(resource(), customDomainProps(NAME).put("DomainConfigurationStatus", "ENABLED"), ctx());

        verify(service, never()).updateDomainConfiguration(any(), any(), any());
    }

    @Test
    void nameIsGeneratedWhenTheTemplateGivesNone() {
        when(service.createDomainConfiguration(anyString(), any(), eq(REGION)))
                .thenAnswer(inv -> stored(inv.getArgument(0), ARN, "iot.example.com", "ENABLED"));
        StackResource r = resource();

        provisioner.provision(r, customDomainProps(null), ctx());

        assertTrue(r.getPhysicalId().matches("my-stack-Domain-[0-9a-f]{12}"), r.getPhysicalId());
        verify(service).createDomainConfiguration(eq(r.getPhysicalId()), any(), eq(REGION));
    }

    @Test
    void updateWithUnchangedCreateOnlyPropertiesUpdatesInPlaceAndReconcilesTags() {
        IotDomainConfiguration existing = stored(NAME, ARN, "iot.example.com", "ENABLED");
        existing.setAuthorizerConfig(new AuthorizerConfig("old-authorizer", true));
        existing.setTags(Map.of("stale", "x", "env", "old"));
        when(service.describeDomainConfiguration(NAME, REGION)).thenReturn(existing);
        when(service.updateDomainConfiguration(eq(NAME), any(), eq(REGION)))
                .thenReturn(stored(NAME, ARN, "iot.example.com", "DISABLED"));
        ObjectNode props = customDomainProps(NAME).put("DomainConfigurationStatus", "DISABLED");
        props.putObject("TlsConfig").put("SecurityPolicy", "IoTSecurityPolicy_TLS12_1_2_2022_10");
        props.putArray("Tags").addObject().put("Key", "env").put("Value", "test");
        StackResource r = resource();

        provisioner.provision(r, props, ctx(NAME));

        verify(service, never()).createDomainConfiguration(any(), any(), any());
        verify(service, never()).deleteDomainConfiguration(any(), any());
        JsonNode update = capturedUpdateBody(NAME);
        assertEquals("DISABLED", update.path("domainConfigurationStatus").asText());
        assertEquals("IoTSecurityPolicy_TLS12_1_2_2022_10", update.path("tlsConfig").path("securityPolicy").asText());
        assertTrue(update.path("removeAuthorizerConfig").asBoolean(),
                "the template dropped AuthorizerConfig, so the update must remove it");
        assertFalse(update.has("authorizerConfig"));
        assertFalse(update.has("domainName"));
        assertFalse(update.has("serverCertificateArns"));
        verify(service).untagResource(ARN, List.of("stale"));
        verify(service).tagResource(ARN, Map.of("env", "test"));
        assertEquals(NAME, r.getPhysicalId());
        assertEquals(Map.of("Arn", ARN, "DomainType", "CUSTOMER_MANAGED", "ServerCertificates", SERVER_CERTIFICATES_JSON),
                r.getAttributes());
    }

    @Test
    void updateKeepingTheAuthorizerSendsItWithoutTheRemoveFlag() {
        IotDomainConfiguration existing = stored(NAME, ARN, "iot.example.com", "ENABLED");
        existing.setAuthorizerConfig(new AuthorizerConfig("old-authorizer", true));
        when(service.describeDomainConfiguration(NAME, REGION)).thenReturn(existing);
        when(service.updateDomainConfiguration(eq(NAME), any(), eq(REGION))).thenReturn(existing);
        ObjectNode props = customDomainProps(NAME);
        props.putObject("AuthorizerConfig").put("DefaultAuthorizerName", "new-authorizer");

        provisioner.provision(resource(), props, ctx(NAME));

        JsonNode update = capturedUpdateBody(NAME);
        assertEquals("new-authorizer", update.path("authorizerConfig").path("defaultAuthorizerName").asText());
        assertFalse(update.has("removeAuthorizerConfig"));
        // No DomainConfigurationStatus in the template leaves the status alone, as the AWS handler does.
        assertFalse(update.has("domainConfigurationStatus"));
    }

    @Test
    void updateWithoutANameKeepsTheGeneratedNameAndUpdatesInPlace() {
        String generated = "my-stack-Domain-aaaaaaaaaaaa";
        when(service.describeDomainConfiguration(generated, REGION))
                .thenReturn(stored(generated, ARN, "iot.example.com", "ENABLED"));
        when(service.updateDomainConfiguration(eq(generated), any(), eq(REGION)))
                .thenReturn(stored(generated, ARN, "iot.example.com", "ENABLED"));
        StackResource r = resource();

        provisioner.provision(r, customDomainProps(null), ctx(generated));

        verify(service, never()).createDomainConfiguration(any(), any(), any());
        verify(service, never()).deleteDomainConfiguration(any(), any());
        verify(service).updateDomainConfiguration(eq(generated), any(), eq(REGION));
        assertEquals(generated, r.getPhysicalId());
    }

    @Test
    void updateRenamingTheConfigurationCreatesTheNewOneAndRemovesTheOld() {
        // DomainConfigurationName is createOnly: a new name is a replacement, and the previous
        // configuration must not outlive the stack that created it.
        String oldArn = "arn:aws:iot:us-east-1:000000000000:domainconfiguration/old-name/aaaaa";
        when(service.describeDomainConfiguration("old-name", REGION))
                .thenReturn(stored("old-name", oldArn, "iot.example.com", "ENABLED"));
        when(service.createDomainConfiguration(eq(NAME), any(), eq(REGION)))
                .thenReturn(stored(NAME, ARN, "iot.example.com", "ENABLED"));
        StackResource r = resource();

        provisioner.provision(r, customDomainProps(NAME), ctx("old-name"));

        InOrder order = inOrder(service);
        order.verify(service).createDomainConfiguration(eq(NAME), any(), eq(REGION));
        order.verify(service).updateDomainConfiguration(eq("old-name"),
                argThat(body -> "DISABLED".equals(body.path("domainConfigurationStatus").asText())), eq(REGION));
        order.verify(service).deleteDomainConfiguration("old-name", REGION);
        assertEquals(NAME, r.getPhysicalId());
        assertEquals(ARN, r.getAttributes().get("Arn"));
    }

    @Test
    void updateChangingACreateOnlyPropertyUnderAnExplicitNameFailsOnTheCreateAsOnAws() {
        // DomainName is createOnly. CloudFormation creates the replacement before deleting the
        // original, and the explicit name is already taken, so the update fails like it does on AWS.
        when(service.describeDomainConfiguration(NAME, REGION)).thenReturn(stored(NAME, ARN, "old.example.com", "ENABLED"));
        when(service.createDomainConfiguration(eq(NAME), any(), eq(REGION)))
                .thenThrow(new AwsException("ResourceAlreadyExistsException", "exists", 409));

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(), customDomainProps(NAME), ctx(NAME)));

        assertEquals("ResourceAlreadyExistsException", failure.getErrorCode());
        verify(service, never()).updateDomainConfiguration(any(), any(), any());
        verify(service, never()).deleteDomainConfiguration(any(), any());
    }

    @Test
    void updateChangingACreateOnlyPropertyUnderAGeneratedNameReplacesTheConfiguration() {
        String priorName = "my-stack-Domain-aaaaaaaaaaaa";
        String priorArn = "arn:aws:iot:us-east-1:000000000000:domainconfiguration/" + priorName + "/aaaaa";
        when(service.describeDomainConfiguration(priorName, REGION)).thenReturn(stored(priorName, priorArn, "old.example.com", "ENABLED"));
        when(service.createDomainConfiguration(anyString(), any(), eq(REGION)))
                .thenAnswer(inv -> stored(inv.getArgument(0), ARN, "iot.example.com", "ENABLED"));
        StackResource r = resource();

        provisioner.provision(r, customDomainProps(null), ctx(priorName));

        assertNotEquals(priorName, r.getPhysicalId());
        assertTrue(r.getPhysicalId().matches("my-stack-Domain-[0-9a-f]{12}"), r.getPhysicalId());
        verify(service).createDomainConfiguration(eq(r.getPhysicalId()), any(), eq(REGION));
        InOrder order = inOrder(service);
        order.verify(service).updateDomainConfiguration(eq(priorName),
                argThat(body -> "DISABLED".equals(body.path("domainConfigurationStatus").asText())), eq(REGION));
        order.verify(service).deleteDomainConfiguration(priorName, REGION);
        assertEquals(ARN, r.getAttributes().get("Arn"));
    }

    @Test
    void createWhoseFollowUpDisableFailsLeavesAnOwnedResourceForTheRollback() {
        // The create rollback deletes only resources that have a physical id and carry the owned
        // marker, so both must be in place before the follow-up status update can fail.
        when(service.createDomainConfiguration(eq(NAME), any(), eq(REGION)))
                .thenReturn(stored(NAME, ARN, "iot.example.com", "ENABLED"));
        when(service.updateDomainConfiguration(eq(NAME), any(), eq(REGION)))
                .thenThrow(new AwsException("InternalFailureException", "boom", 500));
        StackResource r = resource();

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(
                r, customDomainProps(NAME).put("DomainConfigurationStatus", "DISABLED"), ctx()));

        assertEquals("InternalFailureException", failure.getErrorCode());
        assertEquals(NAME, r.getPhysicalId());
        assertEquals(ARN, r.getAttributes().get("Arn"));
        assertEquals("true", r.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
    }

    @Test
    void replacementWhoseFollowUpDisableFailsRemovesTheNewConfigurationAndKeepsThePrior() {
        // CloudFormationService restores the previous StackResource on a failed update and never
        // learns the new name, so the configuration created here must not be left behind.
        String oldArn = "arn:aws:iot:us-east-1:000000000000:domainconfiguration/old-name/aaaaa";
        when(service.describeDomainConfiguration("old-name", REGION))
                .thenReturn(stored("old-name", oldArn, "iot.example.com", "ENABLED"));
        when(service.createDomainConfiguration(eq(NAME), any(), eq(REGION)))
                .thenReturn(stored(NAME, ARN, "iot.example.com", "ENABLED"));
        when(service.describeDomainConfiguration(NAME, REGION)).thenReturn(stored(NAME, ARN, "iot.example.com", "ENABLED"));
        when(service.updateDomainConfiguration(eq(NAME), any(), eq(REGION)))
                .thenThrow(new AwsException("InternalFailureException", "boom", 500))
                .thenReturn(stored(NAME, ARN, "iot.example.com", "DISABLED"));
        StackResource r = resource();

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(
                r, customDomainProps(NAME).put("DomainConfigurationStatus", "DISABLED"), ctx("old-name")));

        assertEquals("InternalFailureException", failure.getErrorCode());
        verify(service).deleteDomainConfiguration(NAME, REGION);
        verify(service, never()).deleteDomainConfiguration("old-name", REGION);
        verify(service, never()).updateDomainConfiguration(eq("old-name"), any(), eq(REGION));
        assertEquals("true", r.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR));
        // The resource describes the configuration that is still there, not the removed one.
        assertEquals("old-name", r.getPhysicalId());
        assertEquals(oldArn, r.getAttributes().get("Arn"));
        assertNull(r.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
    }

    @Test
    void replacementWhosePriorCannotBeDeletedRemovesTheNewConfigurationAndReEnablesThePrior() {
        String oldArn = "arn:aws:iot:us-east-1:000000000000:domainconfiguration/old-name/aaaaa";
        // The store hands out its live object, so the disable that precedes the delete changes the
        // very instance the provisioner captured as the prior. The status it saw at the start is
        // what the unwind must go back to.
        IotDomainConfiguration old = stored("old-name", oldArn, "iot.example.com", "ENABLED");
        when(service.describeDomainConfiguration("old-name", REGION)).thenReturn(old);
        when(service.updateDomainConfiguration(eq("old-name"), any(), eq(REGION))).thenAnswer(inv -> {
            JsonNode body = inv.getArgument(1);
            old.setDomainConfigurationStatus(body.path("domainConfigurationStatus").asText());
            return old;
        });
        when(service.createDomainConfiguration(eq(NAME), any(), eq(REGION)))
                .thenReturn(stored(NAME, ARN, "iot.example.com", "ENABLED"));
        when(service.describeDomainConfiguration(NAME, REGION)).thenReturn(stored(NAME, ARN, "iot.example.com", "ENABLED"));
        doThrow(new AwsException("InternalFailureException", "boom", 500))
                .when(service).deleteDomainConfiguration("old-name", REGION);
        StackResource r = resource();

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(r, customDomainProps(NAME), ctx("old-name")));

        assertEquals("InternalFailureException", failure.getErrorCode());
        InOrder order = inOrder(service);
        order.verify(service).updateDomainConfiguration(eq(NAME),
                argThat(body -> "DISABLED".equals(body.path("domainConfigurationStatus").asText())), eq(REGION));
        order.verify(service).deleteDomainConfiguration(NAME, REGION);
        order.verify(service).updateDomainConfiguration(eq("old-name"),
                argThat(body -> "ENABLED".equals(body.path("domainConfigurationStatus").asText())), eq(REGION));
        assertEquals("ENABLED", old.getDomainConfigurationStatus());
        assertEquals("true", r.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR));
        assertEquals("old-name", r.getPhysicalId());
        assertEquals(oldArn, r.getAttributes().get("Arn"));
        assertNull(r.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
    }

    @Test
    void replacementWhoseCleanupAlsoFailsReportsTheRollbackFailureInsteadOfClaimingARestore() {
        // If the new configuration cannot be removed, the engine must not report a clean rollback:
        // the failure marker makes it end in UPDATE_ROLLBACK_FAILED with the reason.
        String oldArn = "arn:aws:iot:us-east-1:000000000000:domainconfiguration/old-name/aaaaa";
        when(service.describeDomainConfiguration("old-name", REGION))
                .thenReturn(stored("old-name", oldArn, "iot.example.com", "ENABLED"));
        when(service.createDomainConfiguration(eq(NAME), any(), eq(REGION)))
                .thenReturn(stored(NAME, ARN, "iot.example.com", "ENABLED"));
        when(service.describeDomainConfiguration(NAME, REGION)).thenReturn(stored(NAME, ARN, "iot.example.com", "ENABLED"));
        // A fresh exception per call, as a real service raises: the follow-up disable fails, and so
        // does the disable the cleanup attempts.
        when(service.updateDomainConfiguration(eq(NAME), any(), eq(REGION)))
                .thenAnswer(inv -> {
                    throw new AwsException("InternalFailureException", "boom", 500);
                });
        StackResource r = resource();

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(
                r, customDomainProps(NAME).put("DomainConfigurationStatus", "DISABLED"), ctx("old-name")));

        assertEquals("InternalFailureException", failure.getErrorCode());
        assertEquals(1, failure.getSuppressed().length);
        String reason = r.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR);
        assertNotNull(reason);
        assertTrue(reason.contains(NAME), reason);
        assertNull(r.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR));
        verify(service, never()).deleteDomainConfiguration(any(), any());
    }

    @Test
    void explicitNameReplacementWhoseCreateUnexpectedlySucceedsKeepsTheNewConfiguration() {
        // Under an explicit name the create normally fails with ResourceAlreadyExistsException. If
        // the prior vanished in between and the create went through, the configuration just
        // created must not be removed as if it were the prior.
        when(service.describeDomainConfiguration(NAME, REGION)).thenReturn(stored(NAME, ARN, "old.example.com", "ENABLED"));
        when(service.createDomainConfiguration(eq(NAME), any(), eq(REGION)))
                .thenReturn(stored(NAME, ARN, "iot.example.com", "ENABLED"));
        StackResource r = resource();

        provisioner.provision(r, customDomainProps(NAME), ctx(NAME));

        verify(service, never()).deleteDomainConfiguration(any(), any());
        verify(service, never()).updateDomainConfiguration(any(), any(), any());
        assertEquals(NAME, r.getPhysicalId());
        assertEquals(Map.of("Arn", ARN, "DomainType", "CUSTOMER_MANAGED", "ServerCertificates", SERVER_CERTIFICATES_JSON),
                r.getAttributes());
    }

    @Test
    void updateWhosePriorConfigurationIsGoneCreatesAFreshOne() {
        when(service.describeDomainConfiguration(NAME, REGION))
                .thenThrow(new AwsException("ResourceNotFoundException", "gone", 404));
        when(service.createDomainConfiguration(eq(NAME), any(), eq(REGION)))
                .thenReturn(stored(NAME, ARN, "iot.example.com", "ENABLED"));
        StackResource r = resource();

        provisioner.provision(r, customDomainProps(NAME), ctx(NAME));

        verify(service, never()).deleteDomainConfiguration(any(), any());
        verify(service, never()).updateDomainConfiguration(any(), any(), any());
        assertEquals(NAME, r.getPhysicalId());
    }

    @Test
    void deleteDisablesAnEnabledConfigurationBeforeDeletingIt() {
        when(service.describeDomainConfiguration(NAME, REGION)).thenReturn(stored(NAME, ARN, "iot.example.com", "ENABLED"));

        provisioner.delete(TYPE, NAME, REGION);

        InOrder order = inOrder(service);
        order.verify(service).updateDomainConfiguration(eq(NAME),
                argThat(body -> "DISABLED".equals(body.path("domainConfigurationStatus").asText())), eq(REGION));
        order.verify(service).deleteDomainConfiguration(NAME, REGION);
    }

    @Test
    void deleteOfADisabledConfigurationSkipsTheStatusUpdate() {
        when(service.describeDomainConfiguration(NAME, REGION)).thenReturn(stored(NAME, ARN, "iot.example.com", "DISABLED"));

        provisioner.delete(TYPE, NAME, REGION);

        verify(service, never()).updateDomainConfiguration(any(), any(), any());
        verify(service).deleteDomainConfiguration(NAME, REGION);
    }

    @Test
    void deleteToleratesAnAlreadyDeletedConfiguration() {
        when(service.describeDomainConfiguration(NAME, REGION))
                .thenThrow(new AwsException("ResourceNotFoundException", "gone", 404));

        assertDoesNotThrow(() -> provisioner.delete(TYPE, NAME, REGION));

        verify(service, never()).deleteDomainConfiguration(any(), any());
    }

    @Test
    void deleteToleratesAConfigurationThatVanishesWhileBeingDisabled() {
        // Seen ENABLED, then gone by the time the disable runs: already deleted is the outcome the
        // stack wants, on the disable as much as on the delete itself.
        when(service.describeDomainConfiguration(NAME, REGION)).thenReturn(stored(NAME, ARN, "iot.example.com", "ENABLED"));
        when(service.updateDomainConfiguration(eq(NAME), any(), eq(REGION)))
                .thenThrow(new AwsException("ResourceNotFoundException", "gone", 404));
        doThrow(new AwsException("ResourceNotFoundException", "gone", 404))
                .when(service).deleteDomainConfiguration(NAME, REGION);

        assertDoesNotThrow(() -> provisioner.delete(TYPE, NAME, REGION));
    }

    @Test
    void deletePropagatesAFailureThatIsNotAlreadyGone() {
        when(service.describeDomainConfiguration(NAME, REGION)).thenReturn(stored(NAME, ARN, "iot.example.com", "DISABLED"));
        doThrow(new AwsException("InvalidRequestException", "refused", 400))
                .when(service).deleteDomainConfiguration(NAME, REGION);

        assertThrows(AwsException.class, () -> provisioner.delete(TYPE, NAME, REGION));
    }
}
