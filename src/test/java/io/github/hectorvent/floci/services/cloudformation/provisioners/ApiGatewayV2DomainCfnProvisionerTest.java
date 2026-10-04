package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayService;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayService.StoredMapping;
import io.github.hectorvent.floci.services.apigateway.model.BasePathMapping;
import io.github.hectorvent.floci.services.apigateway.model.CustomDomain;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The API Gateway v2 custom domain provisioner in isolation, over a mocked service. The validation
 * is the service's and is tested there; here every case asserts the exact physical id and the exact
 * {@code Fn::GetAtt} attribute keys, since an unmapped type still reports CREATE_COMPLETE through
 * the dispatcher's stub arm, and how a replacement and a rollback reach the service. The replacement
 * lifecycle is {@link ReplacementCleanup}'s own, not a stand-in.
 */
class ApiGatewayV2DomainCfnProvisionerTest {

    private static final String DOMAIN_TYPE = "AWS::ApiGatewayV2::DomainName";
    private static final String MAPPING_TYPE = "AWS::ApiGatewayV2::ApiMapping";
    private static final String REGION = "us-east-1";
    private static final String DOMAIN = "api.example.com";
    private static final String OLD_DOMAIN = "old.example.com";
    private static final String DOMAIN_ARN = "arn:aws:apigateway:us-east-1::/domainnames/api.example.com";
    private static final String CERTIFICATE_ARN =
            "arn:aws:acm:us-east-1:000000000000:certificate/11111111-2222-3333-4444-555555555555";
    private static final String RENEWED_CERTIFICATE_ARN =
            "arn:aws:acm:us-east-1:000000000000:certificate/99999999-2222-3333-4444-555555555555";
    private static final String API_ID = "abc123def4";
    private static final String OTHER_API_ID = "zyx987wvu6";
    private static final String DOMAIN_ATTR = "__FlociApiMappingDomainName";
    private static final String DOMAIN_SNAPSHOT_ATTR = CfnRollback.API_GATEWAY_V2_DOMAIN_UPDATE_SNAPSHOT_ATTR;
    private static final String MAPPING_SNAPSHOT_ATTR = CfnRollback.API_MAPPING_UPDATE_SNAPSHOT_ATTR;
    private static final String V1_MAPPING_ID = ApiGatewayService.apiMappingId("v1");
    private static final String MOVED_MAPPING_ID = V1_MAPPING_ID + "-2";
    private static final AwsException DOMAIN_NOT_FOUND =
            new AwsException("NotFoundException", "Invalid domain name identifier specified", 404);
    private static final AwsException MAPPING_NOT_FOUND =
            new AwsException("NotFoundException", "Unable to find ApiMapping with ID " + V1_MAPPING_ID, 404);

    private final ApiGatewayService apiGateway = mock(ApiGatewayService.class);
    private final ApiGatewayV2DomainCfnProvisioner provisioner = new ApiGatewayV2DomainCfnProvisioner(apiGateway);
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

    private static StackResource resource(String type) {
        StackResource r = new StackResource();
        r.setLogicalId("Res");
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private static StackResource resource(String type, String physicalId) {
        StackResource r = resource(type);
        r.setPhysicalId(physicalId);
        return r;
    }

    /** A domain resource as an earlier provision left it. */
    private static StackResource provisionedDomain(String domainName) {
        StackResource r = resource(DOMAIN_TYPE, domainName);
        r.getAttributes().put("DomainNameArn", "arn:aws:apigateway:us-east-1::/domainnames/" + domainName);
        r.getAttributes().put("RegionalDomainName", domainName + ".regional.local");
        r.getAttributes().put("RegionalHostedZoneId", "Z2FDTNDATAQYL2");
        return r;
    }

    /** A mapping resource as an earlier provision left it: its id and the domain it was created on. */
    private static StackResource provisionedMapping(String apiMappingId, String domainName) {
        StackResource r = resource(MAPPING_TYPE, apiMappingId);
        r.getAttributes().put("ApiMappingId", apiMappingId);
        r.getAttributes().put(DOMAIN_ATTR, domainName);
        return r;
    }

    private static CustomDomain regionalDomain(String name) {
        CustomDomain d = new CustomDomain();
        d.setDomainName(name);
        d.setEndpointConfigurationType("REGIONAL");
        d.setCertificateArn(CERTIFICATE_ARN);
        d.setSecurityPolicy("TLS_1_2");
        d.setRegionalDomainName(name + ".regional.local");
        d.setRegionalHostedZoneId("Z2FDTNDATAQYL2");
        d.setTags(new LinkedHashMap<>(Map.of("stack", "my-stack")));
        return d;
    }

    private static StoredMapping storedMapping(String apiMappingId, String storedPath, String apiId, String stage) {
        BasePathMapping mapping = new BasePathMapping(storedPath, apiId, stage);
        mapping.setApiMappingId(apiMappingId);
        return new StoredMapping(storedPath, mapping);
    }

    private ObjectNode domainProps(String certificateArn, String securityPolicy) {
        ObjectNode props = mapper.createObjectNode().put("DomainName", DOMAIN);
        props.putArray("DomainNameConfigurations").addObject()
                .put("CertificateArn", certificateArn)
                .put("EndpointType", "REGIONAL")
                .put("SecurityPolicy", securityPolicy);
        props.putObject("Tags").put("stack", "my-stack");
        return props;
    }

    private static Map<String, Object> domainRequest(String certificateArn, String securityPolicy) {
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("certificateArn", certificateArn);
        configuration.put("endpointType", "REGIONAL");
        configuration.put("securityPolicy", securityPolicy);
        return Map.of("domainName", DOMAIN, "domainNameConfigurations", List.of(configuration),
                "tags", Map.of("stack", "my-stack"));
    }

    private ObjectNode mappingProps(String domainName, String apiMappingKey, String apiId, String stage) {
        ObjectNode props = mapper.createObjectNode()
                .put("DomainName", domainName)
                .put("ApiId", apiId)
                .put("Stage", stage);
        if (apiMappingKey != null) {
            props.put("ApiMappingKey", apiMappingKey);
        }
        return props;
    }

    @Test
    void servesBothTypes() {
        assertEquals(Set.of(DOMAIN_TYPE, MAPPING_TYPE), provisioner.resourceTypes());
    }

    @Test
    void domainSetsItsNameAsPhysicalIdAndTheThreeSchemaAttributes() {
        when(apiGateway.createV2DomainName(eq(REGION), anyMap())).thenReturn(regionalDomain(DOMAIN));
        StackResource r = resource(DOMAIN_TYPE);

        provisioner.provision(r, domainProps(CERTIFICATE_ARN, "TLS_1_2"), ctx());

        verify(apiGateway).createV2DomainName(REGION, domainRequest(CERTIFICATE_ARN, "TLS_1_2"));
        assertEquals(DOMAIN, r.getPhysicalId());
        assertEquals(Map.of(
                "DomainNameArn", DOMAIN_ARN,
                "RegionalDomainName", "api.example.com.regional.local",
                "RegionalHostedZoneId", "Z2FDTNDATAQYL2"), r.getAttributes());
    }

    @Test
    void domainHandsEveryInputToTheServiceToJudge() {
        // The service refuses what floci does not emulate, so the template's values must reach it.
        when(apiGateway.createV2DomainName(eq(REGION), anyMap())).thenReturn(regionalDomain(DOMAIN));
        ObjectNode props = domainProps(CERTIFICATE_ARN, "TLS_1_2").put("RoutingMode", "API_MAPPING_ONLY");
        ((ObjectNode) props.withArray("DomainNameConfigurations").get(0))
                .put("IpAddressType", "dualstack")
                .put("OwnershipVerificationCertificateArn", RENEWED_CERTIFICATE_ARN);
        props.putObject("MutualTlsAuthentication").put("TruststoreUri", "s3://bucket/truststore.pem");

        provisioner.provision(resource(DOMAIN_TYPE), props, ctx());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(apiGateway).createV2DomainName(eq(REGION), request.capture());
        assertEquals("API_MAPPING_ONLY", request.getValue().get("routingMode"));
        assertEquals(Map.of("truststoreUri", "s3://bucket/truststore.pem"),
                request.getValue().get("mutualTlsAuthentication"));
        @SuppressWarnings("unchecked")
        Map<String, Object> configuration =
                ((List<Map<String, Object>>) request.getValue().get("domainNameConfigurations")).getFirst();
        assertEquals("dualstack", configuration.get("ipAddressType"));
        assertEquals(RENEWED_CERTIFICATE_ARN, configuration.get("ownershipVerificationCertificateArn"));
    }

    @Test
    void resourcesWithoutPropertiesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> provisioner.provision(resource(DOMAIN_TYPE), null, ctx()));
        assertThrows(IllegalArgumentException.class, () -> provisioner.provision(resource(MAPPING_TYPE), null, ctx()));

        verify(apiGateway, never()).createV2DomainName(any(), anyMap());
        verify(apiGateway, never()).createApiMapping(any(), any(), any(), any(), any(), any());
    }

    @Test
    void domainUpdateWithUnchangedNameReplacesItsConfigurationInPlace() {
        when(apiGateway.getDomainName(REGION, DOMAIN)).thenReturn(regionalDomain(DOMAIN));
        CustomDomain updated = regionalDomain(DOMAIN);
        updated.setCertificateArn(RENEWED_CERTIFICATE_ARN);
        when(apiGateway.replaceV2DomainConfiguration(eq(REGION), eq(DOMAIN), anyMap())).thenReturn(updated);
        StackResource r = provisionedDomain(DOMAIN);

        provisioner.provision(r, domainProps(RENEWED_CERTIFICATE_ARN, "TLS_1_2"), ctx(DOMAIN));

        verify(apiGateway).replaceV2DomainConfiguration(REGION, DOMAIN, domainRequest(RENEWED_CERTIFICATE_ARN, "TLS_1_2"));
        verify(apiGateway, never()).createV2DomainName(any(), anyMap());
        verify(apiGateway, never()).deleteDomainName(any(), any());
        assertEquals(DOMAIN, r.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(r));
        assertTrue(r.getAttributes().containsKey(DOMAIN_SNAPSHOT_ATTR), "the prior configuration is kept for a rollback");
    }

    @Test
    void domainUpdateDrivesTagsToTheTemplate() {
        CustomDomain existing = regionalDomain(DOMAIN);
        existing.setTags(new LinkedHashMap<>(Map.of("stack", "my-stack", "owner", "old")));
        when(apiGateway.getDomainName(REGION, DOMAIN)).thenReturn(existing);
        when(apiGateway.replaceV2DomainConfiguration(eq(REGION), eq(DOMAIN), anyMap())).thenReturn(existing);
        ObjectNode props = domainProps(CERTIFICATE_ARN, "TLS_1_2");
        ((ObjectNode) props.get("Tags")).put("team", "api");

        provisioner.provision(provisionedDomain(DOMAIN), props, ctx(DOMAIN));

        verify(apiGateway).untagDomainName(REGION, DOMAIN, List.of("owner"));
        verify(apiGateway).tagDomainName(REGION, DOMAIN, Map.of("stack", "my-stack", "team", "api"));
    }

    @Test
    void domainUpdateTheServiceRefusesLeavesTheTagsAlone() {
        when(apiGateway.getDomainName(REGION, DOMAIN)).thenReturn(regionalDomain(DOMAIN));
        when(apiGateway.replaceV2DomainConfiguration(eq(REGION), eq(DOMAIN), anyMap()))
                .thenThrow(new AwsException("BadRequestException", "Mutual TLS authentication is not supported", 400));
        ObjectNode props = domainProps(CERTIFICATE_ARN, "TLS_1_2");
        ((ObjectNode) props.get("Tags")).put("team", "api");

        assertThrows(AwsException.class, () -> provisioner.provision(provisionedDomain(DOMAIN), props, ctx(DOMAIN)));

        verify(apiGateway, never()).tagDomainName(any(), any(), anyMap());
    }

    @Test
    void domainRenameCreatesTheNewDomainAndLeavesTheOldOneToTheCleanup() {
        when(apiGateway.getDomainName(REGION, OLD_DOMAIN)).thenReturn(regionalDomain(OLD_DOMAIN));
        when(apiGateway.createV2DomainName(eq(REGION), anyMap())).thenReturn(regionalDomain(DOMAIN));
        StackResource r = provisionedDomain(OLD_DOMAIN);

        provisioner.provision(r, domainProps(CERTIFICATE_ARN, "TLS_1_2"), ctx(OLD_DOMAIN));

        verify(apiGateway).createV2DomainName(eq(REGION), anyMap());
        verify(apiGateway, never()).deleteDomainName(any(), any());
        assertEquals(DOMAIN, r.getPhysicalId());
        assertEquals("api.example.com.regional.local", r.getAttributes().get("RegionalDomainName"));
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals(OLD_DOMAIN, provisioner.updateCleanupPhysicalId(r));

        UpdateCleanupResult cleanup = provisioner.completeUpdate(r);

        assertTrue(cleanup.complete());
        verify(apiGateway).deleteDomainName(REGION, OLD_DOMAIN);
    }

    @Test
    void domainRenameUnderRetainKeepsTheOldDomain() {
        when(apiGateway.getDomainName(REGION, OLD_DOMAIN)).thenReturn(regionalDomain(OLD_DOMAIN));
        when(apiGateway.createV2DomainName(eq(REGION), anyMap())).thenReturn(regionalDomain(DOMAIN));
        StackResource r = provisionedDomain(OLD_DOMAIN);
        r.setUpdateReplacePolicy("Retain");

        provisioner.provision(r, domainProps(CERTIFICATE_ARN, "TLS_1_2"), ctx(OLD_DOMAIN));
        provisioner.completeUpdate(r);

        verify(apiGateway, never()).deleteDomainName(any(), any());
    }

    @Test
    void domainRenameRollbackPutsTheOldDomainBackAndDeletesTheNewOne() {
        when(apiGateway.getDomainName(REGION, OLD_DOMAIN)).thenReturn(regionalDomain(OLD_DOMAIN));
        when(apiGateway.createV2DomainName(eq(REGION), anyMap())).thenReturn(regionalDomain(DOMAIN));
        StackResource r = provisionedDomain(OLD_DOMAIN);
        provisioner.provision(r, domainProps(CERTIFICATE_ARN, "TLS_1_2"), ctx(OLD_DOMAIN));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(apiGateway).deleteDomainName(REGION, DOMAIN);
        verify(apiGateway, never()).deleteDomainName(REGION, OLD_DOMAIN);
        assertEquals(OLD_DOMAIN, r.getPhysicalId());
        assertEquals("old.example.com.regional.local", r.getAttributes().get("RegionalDomainName"));
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void domainInPlaceRollbackRestoresTheConfigurationAndTagsItHadBefore() {
        CustomDomain existing = regionalDomain(DOMAIN);
        existing.setCertificateName("before");
        when(apiGateway.getDomainName(REGION, DOMAIN)).thenReturn(existing);
        CustomDomain updated = regionalDomain(DOMAIN);
        updated.setTags(new LinkedHashMap<>(Map.of("stack", "my-stack", "team", "api")));
        when(apiGateway.replaceV2DomainConfiguration(eq(REGION), eq(DOMAIN), anyMap())).thenReturn(updated);
        ObjectNode props = domainProps(RENEWED_CERTIFICATE_ARN, "TLS_1_2");
        ((ObjectNode) props.get("Tags")).put("team", "api");
        StackResource r = provisionedDomain(DOMAIN);
        provisioner.provision(r, props, ctx(DOMAIN));

        assertTrue(provisioner.rollbackUpdate(r));

        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("certificateArn", CERTIFICATE_ARN);
        configuration.put("certificateName", "before");
        configuration.put("endpointType", "REGIONAL");
        configuration.put("securityPolicy", "TLS_1_2");
        verify(apiGateway).replaceV2DomainConfiguration(REGION, DOMAIN,
                Map.of("domainName", DOMAIN, "domainNameConfigurations", List.of(configuration)));
        verify(apiGateway).untagDomainName(REGION, DOMAIN, List.of("team"));
        assertFalse(r.getAttributes().containsKey(DOMAIN_SNAPSHOT_ATTR));
    }

    @Test
    void domainUpdateWhosePriorDomainIsGoneCreatesItAgain() {
        when(apiGateway.getDomainName(REGION, DOMAIN)).thenThrow(DOMAIN_NOT_FOUND);
        when(apiGateway.createV2DomainName(eq(REGION), anyMap())).thenReturn(regionalDomain(DOMAIN));
        StackResource r = provisionedDomain(DOMAIN);

        provisioner.provision(r, domainProps(CERTIFICATE_ARN, "TLS_1_2"), ctx(DOMAIN));

        verify(apiGateway).createV2DomainName(eq(REGION), anyMap());
        assertEquals(DOMAIN, r.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(r), "the same name is the same domain, nothing to clean up");
    }

    @Test
    void domainDeleteToleratesAnAlreadyDeletedDomain() {
        doThrow(DOMAIN_NOT_FOUND).when(apiGateway).deleteDomainName(REGION, DOMAIN);

        assertDoesNotThrow(() -> provisioner.delete(resource(DOMAIN_TYPE, DOMAIN), REGION));
    }

    @Test
    void domainDeletePropagatesOtherFailures() {
        doThrow(new AwsException("BadRequestException", "Domain is in use", 400))
                .when(apiGateway).deleteDomainName(REGION, DOMAIN);

        assertThrows(AwsException.class, () -> provisioner.delete(resource(DOMAIN_TYPE, DOMAIN), REGION));
    }

    @Test
    void mappingPhysicalIdIsTheIdTheServiceGaveIt() {
        when(apiGateway.createApiMapping(REGION, DOMAIN, "v1", API_ID, "$default", Set.of()))
                .thenReturn(storedMapping(V1_MAPPING_ID, "v1", API_ID, "$default"));
        StackResource r = resource(MAPPING_TYPE);

        provisioner.provision(r, mappingProps(DOMAIN, "v1", API_ID, "$default"), ctx());

        assertEquals(V1_MAPPING_ID, r.getPhysicalId());
        assertEquals(Map.of("ApiMappingId", V1_MAPPING_ID, DOMAIN_ATTR, DOMAIN), r.getAttributes());
    }

    @Test
    void mappingWithoutAKeyIsTheRootMapping() {
        String rootId = ApiGatewayService.apiMappingId("(none)");
        when(apiGateway.createApiMapping(REGION, DOMAIN, null, API_ID, "$default", Set.of()))
                .thenReturn(storedMapping(rootId, "(none)", API_ID, "$default"));
        StackResource r = resource(MAPPING_TYPE);

        provisioner.provision(r, mappingProps(DOMAIN, null, API_ID, "$default"), ctx());

        assertEquals(rootId, r.getPhysicalId());
    }

    @Test
    void mappingRequiresDomainNameApiIdAndStage() {
        for (String missing : List.of("DomainName", "ApiId", "Stage")) {
            ObjectNode props = mappingProps(DOMAIN, "v1", API_ID, "$default");
            props.remove(missing);
            assertThrows(IllegalArgumentException.class,
                    () -> provisioner.provision(resource(MAPPING_TYPE), props, ctx()), missing);
        }
        verify(apiGateway, never()).createApiMapping(any(), any(), any(), any(), any(), any());
    }

    @Test
    void mappingUpdateOnTheSameDomainIsInPlaceAndKeepsTheIdThroughAKeyChange() {
        when(apiGateway.getApiMapping(REGION, DOMAIN, V1_MAPPING_ID))
                .thenReturn(storedMapping(V1_MAPPING_ID, "v1", API_ID, "$default"));
        when(apiGateway.updateApiMapping(REGION, DOMAIN, V1_MAPPING_ID, "v2", OTHER_API_ID, "prod"))
                .thenReturn(storedMapping(V1_MAPPING_ID, "v2", OTHER_API_ID, "prod"));
        StackResource r = provisionedMapping(V1_MAPPING_ID, DOMAIN);

        provisioner.provision(r, mappingProps(DOMAIN, "v2", OTHER_API_ID, "prod"), ctx(V1_MAPPING_ID));

        verify(apiGateway).updateApiMapping(REGION, DOMAIN, V1_MAPPING_ID, "v2", OTHER_API_ID, "prod");
        verify(apiGateway, never()).createApiMapping(any(), any(), any(), any(), any(), any());
        verify(apiGateway, never()).deleteApiMapping(any(), any(), any());
        assertEquals(V1_MAPPING_ID, r.getPhysicalId());
        assertEquals(V1_MAPPING_ID, r.getAttributes().get("ApiMappingId"));
        assertFalse(provisioner.hasReplacementUpdate(r));
        assertTrue(r.getAttributes().containsKey(MAPPING_SNAPSHOT_ATTR));
    }

    @Test
    void mappingUpdateTheServiceRefusesCreatesNothing() {
        when(apiGateway.getApiMapping(REGION, DOMAIN, V1_MAPPING_ID))
                .thenReturn(storedMapping(V1_MAPPING_ID, "v1", API_ID, "$default"));
        when(apiGateway.updateApiMapping(REGION, DOMAIN, V1_MAPPING_ID, "v1", API_ID, "nosuchstage"))
                .thenThrow(new AwsException("BadRequestException", "Invalid stage identifier specified", 400));

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(
                provisionedMapping(V1_MAPPING_ID, DOMAIN), mappingProps(DOMAIN, "v1", API_ID, "nosuchstage"),
                ctx(V1_MAPPING_ID)));

        assertEquals("BadRequestException", failure.getErrorCode());
        verify(apiGateway, never()).createApiMapping(any(), any(), any(), any(), any(), any());
        verify(apiGateway, never()).deleteApiMapping(any(), any(), any());
    }

    @Test
    void mappingInPlaceRollbackPutsTheKeyApiAndStageBack() {
        when(apiGateway.getApiMapping(REGION, DOMAIN, V1_MAPPING_ID))
                .thenReturn(storedMapping(V1_MAPPING_ID, "v1", API_ID, "$default"));
        when(apiGateway.updateApiMapping(REGION, DOMAIN, V1_MAPPING_ID, "v2", OTHER_API_ID, "prod"))
                .thenReturn(storedMapping(V1_MAPPING_ID, "v2", OTHER_API_ID, "prod"));
        StackResource r = provisionedMapping(V1_MAPPING_ID, DOMAIN);
        provisioner.provision(r, mappingProps(DOMAIN, "v2", OTHER_API_ID, "prod"), ctx(V1_MAPPING_ID));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(apiGateway).updateApiMapping(REGION, DOMAIN, V1_MAPPING_ID, "v1", API_ID, "$default");
        assertFalse(r.getAttributes().containsKey(MAPPING_SNAPSHOT_ATTR));
    }

    @Test
    void mappingInPlaceRollbackOfTheRootMappingRestoresTheRoot() {
        String rootId = ApiGatewayService.apiMappingId("(none)");
        when(apiGateway.getApiMapping(REGION, DOMAIN, rootId))
                .thenReturn(storedMapping(rootId, "(none)", API_ID, "$default"));
        when(apiGateway.updateApiMapping(REGION, DOMAIN, rootId, "v1", API_ID, "$default"))
                .thenReturn(storedMapping(rootId, "v1", API_ID, "$default"));
        StackResource r = provisionedMapping(rootId, DOMAIN);
        provisioner.provision(r, mappingProps(DOMAIN, "v1", API_ID, "$default"), ctx(rootId));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(apiGateway).updateApiMapping(REGION, DOMAIN, rootId, null, API_ID, "$default");
    }

    @Test
    void mappingMovedToAnotherDomainGetsANewIdAndTheOldOneWaitsForTheCleanup() {
        when(apiGateway.createApiMapping(REGION, DOMAIN, "v1", API_ID, "$default", Set.of(V1_MAPPING_ID)))
                .thenReturn(storedMapping(MOVED_MAPPING_ID, "v1", API_ID, "$default"));
        StackResource r = provisionedMapping(V1_MAPPING_ID, OLD_DOMAIN);

        provisioner.provision(r, mappingProps(DOMAIN, "v1", API_ID, "$default"), ctx(V1_MAPPING_ID));

        verify(apiGateway, never()).getApiMapping(any(), any(), any());
        verify(apiGateway, never()).deleteApiMapping(any(), any(), any());
        assertEquals(MOVED_MAPPING_ID, r.getPhysicalId());
        assertEquals(DOMAIN, r.getAttributes().get(DOMAIN_ATTR));
        assertTrue(provisioner.hasReplacementUpdate(r));
        assertEquals(V1_MAPPING_ID, provisioner.updateCleanupPhysicalId(r));

        assertTrue(provisioner.completeUpdate(r).complete());

        verify(apiGateway).deleteApiMapping(REGION, OLD_DOMAIN, V1_MAPPING_ID);
        verify(apiGateway, never()).deleteApiMapping(REGION, DOMAIN, MOVED_MAPPING_ID);
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void mappingMoveRollbackPutsTheOldMappingBackAndDeletesTheNewOne() {
        when(apiGateway.createApiMapping(REGION, DOMAIN, "v1", API_ID, "$default", Set.of(V1_MAPPING_ID)))
                .thenReturn(storedMapping(MOVED_MAPPING_ID, "v1", API_ID, "$default"));
        StackResource r = provisionedMapping(V1_MAPPING_ID, OLD_DOMAIN);
        provisioner.provision(r, mappingProps(DOMAIN, "v1", API_ID, "$default"), ctx(V1_MAPPING_ID));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(apiGateway).deleteApiMapping(REGION, DOMAIN, MOVED_MAPPING_ID);
        verify(apiGateway, never()).deleteApiMapping(REGION, OLD_DOMAIN, V1_MAPPING_ID);
        assertEquals(V1_MAPPING_ID, r.getPhysicalId());
        assertEquals(V1_MAPPING_ID, r.getAttributes().get("ApiMappingId"));
        assertEquals(OLD_DOMAIN, r.getAttributes().get(DOMAIN_ATTR));
    }

    @Test
    void mappingMoveRollbackWhoseDeleteFailsStillNamesTheOldDomain() {
        when(apiGateway.createApiMapping(REGION, DOMAIN, "v1", API_ID, "$default", Set.of(V1_MAPPING_ID)))
                .thenReturn(storedMapping(MOVED_MAPPING_ID, "v1", API_ID, "$default"));
        doThrow(new AwsException("BadRequestException", "Invalid request", 400))
                .when(apiGateway).deleteApiMapping(REGION, DOMAIN, MOVED_MAPPING_ID);
        StackResource r = provisionedMapping(V1_MAPPING_ID, OLD_DOMAIN);
        provisioner.provision(r, mappingProps(DOMAIN, "v1", API_ID, "$default"), ctx(V1_MAPPING_ID));

        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(r));

        assertEquals(V1_MAPPING_ID, r.getPhysicalId());
        assertEquals(OLD_DOMAIN, r.getAttributes().get(DOMAIN_ATTR));
        // The replacement stays owed, and the next cleanup reaches it through the domain it is on.
        assertTrue(provisioner.hasReplacementUpdate(r));
        doThrow(MAPPING_NOT_FOUND).when(apiGateway).deleteApiMapping(REGION, DOMAIN, MOVED_MAPPING_ID);
        assertTrue(provisioner.completeUpdate(r).complete());
        verify(apiGateway, never()).deleteApiMapping(REGION, OLD_DOMAIN, MOVED_MAPPING_ID);
    }

    @Test
    void mappingUpdateWhosePriorMappingIsGoneCreatesItAgain() {
        when(apiGateway.getApiMapping(REGION, DOMAIN, V1_MAPPING_ID)).thenThrow(MAPPING_NOT_FOUND);
        when(apiGateway.createApiMapping(REGION, DOMAIN, "v1", API_ID, "$default", Set.of()))
                .thenReturn(storedMapping(V1_MAPPING_ID, "v1", API_ID, "$default"));
        StackResource r = provisionedMapping(V1_MAPPING_ID, DOMAIN);

        provisioner.provision(r, mappingProps(DOMAIN, "v1", API_ID, "$default"), ctx(V1_MAPPING_ID));

        verify(apiGateway, never()).deleteApiMapping(any(), any(), any());
        assertEquals(V1_MAPPING_ID, r.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void mappingUpdateFromAStubbedResourceCreatesTheMappingAndDeletesNothingForTheStub() {
        // A stack persisted before this provisioner existed carries the stub arm's id and no domain.
        when(apiGateway.createApiMapping(REGION, DOMAIN, "v1", API_ID, "$default", Set.of()))
                .thenReturn(storedMapping(V1_MAPPING_ID, "v1", API_ID, "$default"));
        StackResource r = resource(MAPPING_TYPE, "Mapping-1a2b3c4d");

        provisioner.provision(r, mappingProps(DOMAIN, "v1", API_ID, "$default"), ctx("Mapping-1a2b3c4d"));

        verify(apiGateway, never()).getApiMapping(any(), any(), any());
        assertEquals(V1_MAPPING_ID, r.getPhysicalId());
        assertTrue(provisioner.completeUpdate(r).complete());
        verify(apiGateway, never()).deleteApiMapping(any(), any(), any());
    }

    @Test
    void mappingRollbackFromAStubbedResourceLeavesNoDomainBehind() {
        when(apiGateway.createApiMapping(REGION, DOMAIN, "v1", API_ID, "$default", Set.of()))
                .thenReturn(storedMapping(V1_MAPPING_ID, "v1", API_ID, "$default"));
        StackResource r = resource(MAPPING_TYPE, "Mapping-1a2b3c4d");
        provisioner.provision(r, mappingProps(DOMAIN, "v1", API_ID, "$default"), ctx("Mapping-1a2b3c4d"));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(apiGateway).deleteApiMapping(REGION, DOMAIN, V1_MAPPING_ID);
        assertEquals("Mapping-1a2b3c4d", r.getPhysicalId());
        assertNull(r.getAttributes().get(DOMAIN_ATTR));
    }

    @Test
    void mappingDeleteAddressesTheRecordedDomain() {
        provisioner.delete(provisionedMapping(V1_MAPPING_ID, DOMAIN), REGION);

        verify(apiGateway).deleteApiMapping(REGION, DOMAIN, V1_MAPPING_ID);
    }

    @Test
    void mappingDeleteToleratesAMappingAlreadyGone() {
        doThrow(MAPPING_NOT_FOUND).when(apiGateway).deleteApiMapping(REGION, DOMAIN, V1_MAPPING_ID);

        assertDoesNotThrow(() -> provisioner.delete(provisionedMapping(V1_MAPPING_ID, DOMAIN), REGION));
    }

    @Test
    void mappingDeleteToleratesAMappingRemovedWithItsDomain() {
        doThrow(DOMAIN_NOT_FOUND).when(apiGateway).deleteApiMapping(REGION, DOMAIN, V1_MAPPING_ID);

        assertDoesNotThrow(() -> provisioner.delete(provisionedMapping(V1_MAPPING_ID, DOMAIN), REGION));
    }

    @Test
    void mappingDeletePropagatesOtherFailures() {
        doThrow(new AwsException("BadRequestException", "Invalid request", 400))
                .when(apiGateway).deleteApiMapping(REGION, DOMAIN, V1_MAPPING_ID);

        assertThrows(AwsException.class,
                () -> provisioner.delete(provisionedMapping(V1_MAPPING_ID, DOMAIN), REGION));
    }

    @Test
    void mappingDeleteByThePrimaryIdentifierAddressesItsDomain() {
        // Cloud Control names a mapping by the schema's primary identifier, <ApiMappingId>|<DomainName>.
        provisioner.delete(MAPPING_TYPE, V1_MAPPING_ID + "|" + DOMAIN, REGION);

        verify(apiGateway).deleteApiMapping(REGION, DOMAIN, V1_MAPPING_ID);
    }

    @Test
    void mappingDeleteByThePrimaryIdentifierWithItsRecordedDomainAddressesTheMapping() {
        // Cloud Control keys what it created by the primary identifier, and deletes it with the
        // attributes the create recorded, the domain among them.
        provisioner.delete(provisionedMapping(V1_MAPPING_ID + "|" + DOMAIN, DOMAIN), REGION);

        verify(apiGateway).deleteApiMapping(REGION, DOMAIN, V1_MAPPING_ID);
    }

    @Test
    void mappingDeleteByABareIdAnswersNotFound() {
        // A bare id names no domain to look in; the stack path treats NotFoundException as already
        // deleted, and Cloud Control reports it instead of claiming a delete that did not happen.
        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.delete(resource(MAPPING_TYPE, "Mapping-1a2b3c4d"), REGION));

        assertEquals("NotFoundException", failure.getErrorCode());
        verify(apiGateway, never()).deleteApiMapping(any(), any(), any());
    }

    @Test
    void clearUpdateDropsTheSnapshots() {
        StackResource r = provisionedMapping(V1_MAPPING_ID, DOMAIN);
        r.getAttributes().put(MAPPING_SNAPSHOT_ATTR, "{}");
        r.getAttributes().put(DOMAIN_SNAPSHOT_ATTR, "{}");

        provisioner.clearUpdate(r);

        assertEquals(Map.of("ApiMappingId", V1_MAPPING_ID, DOMAIN_ATTR, DOMAIN), r.getAttributes());
    }
}
