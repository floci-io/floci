package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayService;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The API Gateway custom domain provisioner in isolation: one mocked service. Every case asserts
 * the exact physical id and the exact {@code Fn::GetAtt} attribute keys, since an unmapped type
 * still reports CREATE_COMPLETE through the dispatcher's stub arm.
 */
class ApiGatewayDomainCfnProvisionerTest {

    private static final String DOMAIN_TYPE = "AWS::ApiGateway::DomainName";
    private static final String MAPPING_TYPE = "AWS::ApiGateway::BasePathMapping";
    private static final String REGION = "us-east-1";
    private static final String DOMAIN = "api.example.com";
    private static final String DOMAIN_ARN = "arn:aws:apigateway:us-east-1::/domainnames/api.example.com";
    private static final String CERTIFICATE_ARN =
            "arn:aws:acm:us-east-1:000000000000:certificate/11111111-2222-3333-4444-555555555555";
    private static final String RENEWED_CERTIFICATE_ARN =
            "arn:aws:acm:us-east-1:000000000000:certificate/99999999-2222-3333-4444-555555555555";
    private static final String API_ID = "abc123def4";
    private static final AwsException NOT_FOUND =
            new AwsException("NotFoundException", "Invalid domain name identifier specified", 404);

    private final ApiGatewayService apiGateway = mock(ApiGatewayService.class);
    private final ApiGatewayDomainCfnProvisioner provisioner = new ApiGatewayDomainCfnProvisioner(apiGateway);
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

    private static CustomDomain regionalDomain(String name) {
        CustomDomain d = new CustomDomain();
        d.setDomainName(name);
        d.setEndpointConfigurationType("REGIONAL");
        d.setRegionalCertificateArn(CERTIFICATE_ARN);
        d.setSecurityPolicy("TLS_1_2");
        d.setRegionalDomainName(name + ".regional.local");
        d.setRegionalHostedZoneId("Z2FDTNDATAQYL2");
        d.setTags(new LinkedHashMap<>(Map.of("stack", "my-stack")));
        return d;
    }

    private static BasePathMapping mapping(String basePath, String apiId, String stage) {
        return new BasePathMapping(basePath, apiId, stage);
    }

    private ObjectNode domainProps(String certificateArn, String securityPolicy) {
        ObjectNode props = mapper.createObjectNode()
                .put("DomainName", DOMAIN)
                .put("RegionalCertificateArn", certificateArn)
                .put("SecurityPolicy", securityPolicy);
        props.putObject("EndpointConfiguration").putArray("Types").add("REGIONAL");
        props.putArray("Tags").addObject().put("Key", "stack").put("Value", "my-stack");
        return props;
    }

    private ObjectNode mappingProps(String basePath, String apiId, String stage) {
        ObjectNode props = mapper.createObjectNode().put("DomainName", DOMAIN).put("RestApiId", apiId);
        if (basePath != null) {
            props.put("BasePath", basePath);
        }
        if (stage != null) {
            props.put("Stage", stage);
        }
        return props;
    }

    private static Map<String, String> replace(String path, String value) {
        return Map.of("op", "replace", "path", path, "value", value);
    }

    @Test
    void servesBothTypes() {
        assertEquals(Set.of(DOMAIN_TYPE, MAPPING_TYPE), provisioner.resourceTypes());
    }

    @Test
    void regionalDomainSetsDomainAsPhysicalIdAndAllFiveAttributes() {
        when(apiGateway.createDomainName(eq(REGION), anyMap())).thenReturn(regionalDomain(DOMAIN));
        StackResource r = resource(DOMAIN_TYPE);

        provisioner.provision(r, domainProps(CERTIFICATE_ARN, "TLS_1_2"), ctx());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(apiGateway).createDomainName(eq(REGION), request.capture());
        assertEquals(Map.of(
                "domainName", DOMAIN,
                "endpointConfiguration", Map.of("types", List.of("REGIONAL")),
                "regionalCertificateArn", CERTIFICATE_ARN,
                "securityPolicy", "TLS_1_2",
                "tags", Map.of("stack", "my-stack")), request.getValue());
        assertEquals(DOMAIN, r.getPhysicalId());
        assertEquals(Map.of(
                "DomainNameArn", DOMAIN_ARN,
                "RegionalDomainName", "api.example.com.regional.local",
                "RegionalHostedZoneId", "Z2FDTNDATAQYL2",
                "DistributionDomainName", "",
                "DistributionHostedZoneId", ""), r.getAttributes());
    }

    @Test
    void edgeDomainExposesItsDistribution() {
        CustomDomain edge = regionalDomain(DOMAIN);
        edge.setEndpointConfigurationType("EDGE");
        edge.setDistributionDomainName("d1234567890abc.cloudfront.net");
        edge.setDistributionHostedZoneId("Z2FDTNDATAQYW2");
        when(apiGateway.createDomainName(eq(REGION), anyMap())).thenReturn(edge);
        ObjectNode props = mapper.createObjectNode().put("DomainName", DOMAIN).put("CertificateArn", CERTIFICATE_ARN);
        props.putObject("EndpointConfiguration").putArray("Types").add("EDGE");
        StackResource r = resource(DOMAIN_TYPE);

        provisioner.provision(r, props, ctx());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        verify(apiGateway).createDomainName(eq(REGION), request.capture());
        assertEquals(Map.of(
                "domainName", DOMAIN,
                "endpointConfiguration", Map.of("types", List.of("EDGE")),
                "certificateArn", CERTIFICATE_ARN), request.getValue());
        assertEquals("d1234567890abc.cloudfront.net", r.getAttributes().get("DistributionDomainName"));
        assertEquals("Z2FDTNDATAQYW2", r.getAttributes().get("DistributionHostedZoneId"));
    }

    @Test
    void createWhenTheDomainNameIsAlreadyTakenFailsWithoutTouchingIt() {
        // Domain names are unique across regions, so a template naming one that exists outside the
        // stack fails the resource, as on AWS, and never deletes what it did not create.
        when(apiGateway.createDomainName(eq(REGION), anyMap()))
                .thenThrow(new AwsException("BadRequestException", "The domain name you provided already exists.", 400));
        StackResource r = resource(DOMAIN_TYPE);

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(r, domainProps(CERTIFICATE_ARN, "TLS_1_2"), ctx()));

        assertEquals("BadRequestException", failure.getErrorCode());
        verify(apiGateway, never()).deleteDomainName(any(), any());
        assertEquals(null, r.getPhysicalId());
    }

    @Test
    void resourcesWithoutPropertiesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> provisioner.provision(resource(DOMAIN_TYPE), null, ctx()));
        assertThrows(IllegalArgumentException.class, () -> provisioner.provision(resource(MAPPING_TYPE), null, ctx()));

        verify(apiGateway, never()).createDomainName(any(), anyMap());
        verify(apiGateway, never()).createBasePathMapping(any(), any(), anyMap());
    }

    @Test
    void domainRequiresDomainName() {
        ObjectNode props = mapper.createObjectNode().put("RegionalCertificateArn", CERTIFICATE_ARN);

        assertThrows(IllegalArgumentException.class, () -> provisioner.provision(resource(DOMAIN_TYPE), props, ctx()));

        verify(apiGateway, never()).createDomainName(any(), anyMap());
    }

    @Test
    void updateWithUnchangedDomainPatchesWhatChangedInPlace() {
        CustomDomain existing = regionalDomain(DOMAIN);
        when(apiGateway.getDomainName(REGION, DOMAIN)).thenReturn(existing);
        CustomDomain patched = regionalDomain(DOMAIN);
        patched.setRegionalCertificateArn(RENEWED_CERTIFICATE_ARN);
        patched.setSecurityPolicy("TLS_1_0");
        when(apiGateway.updateDomainName(eq(REGION), eq(DOMAIN), anyList())).thenReturn(patched);
        StackResource r = resource(DOMAIN_TYPE, DOMAIN);

        provisioner.provision(r, domainProps(RENEWED_CERTIFICATE_ARN, "TLS_1_0"), ctx(DOMAIN));

        verify(apiGateway).updateDomainName(REGION, DOMAIN, List.of(
                replace("/regionalCertificateArn", RENEWED_CERTIFICATE_ARN),
                replace("/securityPolicy", "TLS_1_0")));
        verify(apiGateway, never()).createDomainName(any(), anyMap());
        verify(apiGateway, never()).deleteDomainName(any(), any());
        assertEquals(DOMAIN, r.getPhysicalId());
        assertEquals("api.example.com.regional.local", r.getAttributes().get("RegionalDomainName"));
    }

    @Test
    void updateWithNothingChangedDoesNotPatch() {
        when(apiGateway.getDomainName(REGION, DOMAIN)).thenReturn(regionalDomain(DOMAIN));
        StackResource r = resource(DOMAIN_TYPE, DOMAIN);

        provisioner.provision(r, domainProps(CERTIFICATE_ARN, "TLS_1_2"), ctx(DOMAIN));

        verify(apiGateway, never()).updateDomainName(any(), any(), anyList());
        verify(apiGateway, never()).untagDomainName(any(), any(), anyList());
        verify(apiGateway, never()).tagDomainName(any(), any(), anyMap());
        assertEquals(Map.of(
                "DomainNameArn", DOMAIN_ARN,
                "RegionalDomainName", "api.example.com.regional.local",
                "RegionalHostedZoneId", "Z2FDTNDATAQYL2",
                "DistributionDomainName", "",
                "DistributionHostedZoneId", ""), r.getAttributes());
    }

    @Test
    void updateNamesTheCurrentEndpointTypeInThePatchPath() {
        // AWS's patch path names the type the domain has now; the value is the type it should get.
        when(apiGateway.getDomainName(REGION, DOMAIN)).thenReturn(regionalDomain(DOMAIN));
        when(apiGateway.updateDomainName(eq(REGION), eq(DOMAIN), anyList())).thenReturn(regionalDomain(DOMAIN));
        ObjectNode props = domainProps(CERTIFICATE_ARN, "TLS_1_2");
        props.putObject("EndpointConfiguration").putArray("Types").add("EDGE");

        provisioner.provision(resource(DOMAIN_TYPE, DOMAIN), props, ctx(DOMAIN));

        verify(apiGateway).updateDomainName(REGION, DOMAIN,
                List.of(replace("/endpointConfiguration/types/REGIONAL", "EDGE")));
    }

    @Test
    void updateDrivesTagsToTheTemplate() {
        CustomDomain existing = regionalDomain(DOMAIN);
        existing.setTags(new LinkedHashMap<>(Map.of("stack", "my-stack", "owner", "old")));
        when(apiGateway.getDomainName(REGION, DOMAIN)).thenReturn(existing);
        ObjectNode props = domainProps(CERTIFICATE_ARN, "TLS_1_2");
        props.putArray("Tags").addObject().put("Key", "stack").put("Value", "my-stack");
        props.withArray("Tags").addObject().put("Key", "team").put("Value", "api");

        provisioner.provision(resource(DOMAIN_TYPE, DOMAIN), props, ctx(DOMAIN));

        verify(apiGateway).untagDomainName(REGION, DOMAIN, List.of("owner"));
        verify(apiGateway).tagDomainName(REGION, DOMAIN, Map.of("stack", "my-stack", "team", "api"));
    }

    @Test
    void updateWithoutTagsRemovesTheStoredOnes() {
        when(apiGateway.getDomainName(REGION, DOMAIN)).thenReturn(regionalDomain(DOMAIN));
        ObjectNode props = domainProps(CERTIFICATE_ARN, "TLS_1_2");
        props.remove("Tags");

        provisioner.provision(resource(DOMAIN_TYPE, DOMAIN), props, ctx(DOMAIN));

        verify(apiGateway).untagDomainName(REGION, DOMAIN, List.of("stack"));
        verify(apiGateway, never()).tagDomainName(any(), any(), anyMap());
    }

    @Test
    void updateWithChangedDomainReplacesIt() {
        when(apiGateway.getDomainName(REGION, "old.example.com")).thenReturn(regionalDomain("old.example.com"));
        when(apiGateway.createDomainName(eq(REGION), anyMap())).thenReturn(regionalDomain(DOMAIN));
        StackResource r = resource(DOMAIN_TYPE, "old.example.com");

        provisioner.provision(r, domainProps(CERTIFICATE_ARN, "TLS_1_2"), ctx("old.example.com"));

        verify(apiGateway).createDomainName(eq(REGION), anyMap());
        verify(apiGateway).deleteDomainName(REGION, "old.example.com");
        verify(apiGateway, never()).updateDomainName(any(), any(), anyList());
        assertEquals(DOMAIN, r.getPhysicalId());
        assertEquals("api.example.com.regional.local", r.getAttributes().get("RegionalDomainName"));
    }

    @Test
    void updateWhosePriorDomainIsGoneCreatesItAgain() {
        when(apiGateway.getDomainName(REGION, DOMAIN)).thenThrow(NOT_FOUND);
        when(apiGateway.createDomainName(eq(REGION), anyMap())).thenReturn(regionalDomain(DOMAIN));
        StackResource r = resource(DOMAIN_TYPE, DOMAIN);

        provisioner.provision(r, domainProps(CERTIFICATE_ARN, "TLS_1_2"), ctx(DOMAIN));

        verify(apiGateway).createDomainName(eq(REGION), anyMap());
        verify(apiGateway, never()).updateDomainName(any(), any(), anyList());
        verify(apiGateway, never()).deleteDomainName(any(), any());
        assertEquals(DOMAIN, r.getPhysicalId());
    }

    @Test
    void updateWhosePriorDomainIsGoneUnderANewNameCreatesWithoutDeleting() {
        when(apiGateway.getDomainName(REGION, "old.example.com")).thenThrow(NOT_FOUND);
        when(apiGateway.createDomainName(eq(REGION), anyMap())).thenReturn(regionalDomain(DOMAIN));
        StackResource r = resource(DOMAIN_TYPE, "old.example.com");

        provisioner.provision(r, domainProps(CERTIFICATE_ARN, "TLS_1_2"), ctx("old.example.com"));

        verify(apiGateway).createDomainName(eq(REGION), anyMap());
        verify(apiGateway, never()).deleteDomainName(any(), any());
        assertEquals(DOMAIN, r.getPhysicalId());
    }

    @Test
    void updateThatFailsValidationLeavesTagsUntouched() {
        // The patch carries the validation, so it runs first: a rejected update must not have
        // already rewritten the domain's tags.
        CustomDomain existing = regionalDomain(DOMAIN);
        when(apiGateway.getDomainName(REGION, DOMAIN)).thenReturn(existing);
        when(apiGateway.updateDomainName(eq(REGION), eq(DOMAIN), anyList()))
                .thenThrow(new AwsException("BadRequestException", "Invalid value for endpoint type: PRIVATE", 400));
        ObjectNode props = domainProps(CERTIFICATE_ARN, "TLS_1_2");
        props.putObject("EndpointConfiguration").putArray("Types").add("PRIVATE");
        props.withArray("Tags").addObject().put("Key", "team").put("Value", "api");

        assertThrows(AwsException.class, () -> provisioner.provision(resource(DOMAIN_TYPE, DOMAIN), props, ctx(DOMAIN)));

        verify(apiGateway, never()).tagDomainName(any(), any(), anyMap());
        verify(apiGateway, never()).untagDomainName(any(), any(), anyList());
    }

    @Test
    void replacementToleratesAPriorDomainThatIsAlreadyGone() {
        when(apiGateway.getDomainName(REGION, "old.example.com")).thenReturn(regionalDomain("old.example.com"));
        when(apiGateway.createDomainName(eq(REGION), anyMap())).thenReturn(regionalDomain(DOMAIN));
        doThrow(NOT_FOUND).when(apiGateway).deleteDomainName(REGION, "old.example.com");
        StackResource r = resource(DOMAIN_TYPE, "old.example.com");

        assertDoesNotThrow(() -> provisioner.provision(r, domainProps(CERTIFICATE_ARN, "TLS_1_2"), ctx("old.example.com")));
        assertEquals(DOMAIN, r.getPhysicalId());
    }

    @Test
    void domainDeleteToleratesAnAlreadyDeletedDomain() {
        doThrow(NOT_FOUND).when(apiGateway).deleteDomainName(REGION, DOMAIN);

        assertDoesNotThrow(() -> provisioner.delete(DOMAIN_TYPE, DOMAIN, REGION));
    }

    @Test
    void domainDeletePropagatesOtherFailures() {
        doThrow(new AwsException("BadRequestException", "Domain is in use", 400))
                .when(apiGateway).deleteDomainName(REGION, DOMAIN);

        assertThrows(AwsException.class, () -> provisioner.delete(DOMAIN_TYPE, DOMAIN, REGION));
    }

    @Test
    void mappingPhysicalIdJoinsDomainAndBasePathWithAPipe() {
        StackResource r = resource(MAPPING_TYPE);

        provisioner.provision(r, mappingProps("v1", API_ID, "prod"), ctx());

        verify(apiGateway).createBasePathMapping(REGION, DOMAIN,
                Map.of("basePath", "v1", "restApiId", API_ID, "stage", "prod"));
        assertEquals("api.example.com|v1", r.getPhysicalId());
        assertEquals(Map.of(), r.getAttributes());
    }

    @Test
    void mappingWithoutBasePathUsesTheApiSpellingOfNone() {
        StackResource r = resource(MAPPING_TYPE);

        provisioner.provision(r, mappingProps(null, API_ID, null), ctx());

        verify(apiGateway).createBasePathMapping(REGION, DOMAIN, Map.of("basePath", "(none)", "restApiId", API_ID));
        assertEquals("api.example.com|(none)", r.getPhysicalId());
    }

    @Test
    void mappingRequiresDomainNameAndRestApiId() {
        assertThrows(IllegalArgumentException.class, () -> provisioner.provision(
                resource(MAPPING_TYPE), mapper.createObjectNode().put("RestApiId", API_ID), ctx()));
        assertThrows(IllegalArgumentException.class, () -> provisioner.provision(
                resource(MAPPING_TYPE), mapper.createObjectNode().put("DomainName", DOMAIN), ctx()));

        verify(apiGateway, never()).createBasePathMapping(any(), any(), anyMap());
    }

    @Test
    void mappingUpdateWithSameIdentityPatchesApiAndStageInPlace() {
        when(apiGateway.getBasePathMapping(REGION, DOMAIN, "v1")).thenReturn(mapping("v1", API_ID, "prod"));
        StackResource r = resource(MAPPING_TYPE, "api.example.com|v1");

        provisioner.provision(r, mappingProps("v1", "newapi1234", "dev"), ctx("api.example.com|v1"));

        verify(apiGateway).updateBasePathMapping(REGION, DOMAIN, "v1",
                List.of(replace("/restApiId", "newapi1234"), replace("/stage", "dev")));
        verify(apiGateway, never()).createBasePathMapping(any(), any(), anyMap());
        verify(apiGateway, never()).deleteBasePathMapping(any(), any(), any());
        assertEquals("api.example.com|v1", r.getPhysicalId());
    }

    @Test
    void mappingUpdateThatDropsStagePatchesOnlyWhatTheTemplateNames() {
        // The PATCH API cannot unset a stage, so an omitted Stage keeps the stored one rather than
        // sending a replace with no value, which the service rejects.
        when(apiGateway.getBasePathMapping(REGION, DOMAIN, "v1")).thenReturn(mapping("v1", API_ID, "prod"));

        provisioner.provision(resource(MAPPING_TYPE, "api.example.com|v1"),
                mappingProps("v1", "newapi1234", null), ctx("api.example.com|v1"));

        verify(apiGateway).updateBasePathMapping(REGION, DOMAIN, "v1", List.of(replace("/restApiId", "newapi1234")));
    }

    @Test
    void mappingUpdateWithNothingChangedDoesNotPatch() {
        when(apiGateway.getBasePathMapping(REGION, DOMAIN, "v1")).thenReturn(mapping("v1", API_ID, "prod"));

        provisioner.provision(resource(MAPPING_TYPE, "api.example.com|v1"),
                mappingProps("v1", API_ID, "prod"), ctx("api.example.com|v1"));

        verify(apiGateway, never()).updateBasePathMapping(any(), any(), any(), anyList());
    }

    @Test
    void mappingUpdateWithChangedBasePathReplacesIt() {
        when(apiGateway.getBasePathMapping(REGION, DOMAIN, "v1")).thenReturn(mapping("v1", API_ID, "prod"));
        StackResource r = resource(MAPPING_TYPE, "api.example.com|v1");

        provisioner.provision(r, mappingProps("v2", API_ID, "prod"), ctx("api.example.com|v1"));

        verify(apiGateway).createBasePathMapping(REGION, DOMAIN,
                Map.of("basePath", "v2", "restApiId", API_ID, "stage", "prod"));
        verify(apiGateway).deleteBasePathMapping(REGION, DOMAIN, "v1");
        verify(apiGateway, never()).updateBasePathMapping(any(), any(), any(), anyList());
        assertEquals("api.example.com|v2", r.getPhysicalId());
    }

    @Test
    void mappingUpdateWhosePriorMappingIsGoneCreatesItAgain() {
        when(apiGateway.getBasePathMapping(REGION, DOMAIN, "v1")).thenThrow(NOT_FOUND);
        StackResource r = resource(MAPPING_TYPE, "api.example.com|v1");

        provisioner.provision(r, mappingProps("v1", API_ID, "prod"), ctx("api.example.com|v1"));

        verify(apiGateway).createBasePathMapping(REGION, DOMAIN,
                Map.of("basePath", "v1", "restApiId", API_ID, "stage", "prod"));
        verify(apiGateway, never()).deleteBasePathMapping(any(), any(), any());
        assertEquals("api.example.com|v1", r.getPhysicalId());
    }

    @Test
    void mappingUpdateWhosePriorIsGoneUnderANewIdCreatesWithoutDeleting() {
        when(apiGateway.getBasePathMapping(REGION, DOMAIN, "v1")).thenThrow(NOT_FOUND);
        StackResource r = resource(MAPPING_TYPE, "api.example.com|v1");

        provisioner.provision(r, mappingProps("v2", API_ID, "prod"), ctx("api.example.com|v1"));

        verify(apiGateway).createBasePathMapping(REGION, DOMAIN,
                Map.of("basePath", "v2", "restApiId", API_ID, "stage", "prod"));
        verify(apiGateway, never()).deleteBasePathMapping(any(), any(), any());
        assertEquals("api.example.com|v2", r.getPhysicalId());
    }

    @Test
    void mappingDeleteSplitsTheCompositeId() {
        provisioner.delete(MAPPING_TYPE, "api.example.com|(none)", REGION);

        verify(apiGateway).deleteBasePathMapping(REGION, DOMAIN, "(none)");
    }

    @Test
    void mappingDeleteToleratesAnAlreadyDeletedMapping() {
        // The domain's delete removes its mappings first, so a mapping deleted after its domain is gone.
        doThrow(NOT_FOUND).when(apiGateway).deleteBasePathMapping(REGION, DOMAIN, "v1");

        assertDoesNotThrow(() -> provisioner.delete(MAPPING_TYPE, "api.example.com|v1", REGION));
    }

    @Test
    void mappingDeletePropagatesOtherFailures() {
        doThrow(new AwsException("BadRequestException", "Invalid patch", 400))
                .when(apiGateway).deleteBasePathMapping(REGION, DOMAIN, "v1");

        assertThrows(AwsException.class, () -> provisioner.delete(MAPPING_TYPE, "api.example.com|v1", REGION));
    }

    @Test
    void mappingDeleteAnswersNotFoundForAnIdWithoutTheSeparator() {
        // Nothing this provisioner creates has such an id, so there is nothing to remove: the stack
        // path treats NotFoundException as already deleted, and Cloud Control reports it as such.
        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.delete(MAPPING_TYPE, DOMAIN, REGION));

        assertEquals("NotFoundException", failure.getErrorCode());
        verify(apiGateway, never()).deleteBasePathMapping(any(), any(), any());
    }

    @Test
    void mappingUpdateWhosePriorIdIsMalformedCreatesTheMapping() {
        // A stack persisted before this provisioner existed carries the stub arm's generated id.
        StackResource r = resource(MAPPING_TYPE, "Mapping-1a2b3c4d");

        provisioner.provision(r, mappingProps("v1", API_ID, "prod"), ctx("Mapping-1a2b3c4d"));

        verify(apiGateway).createBasePathMapping(REGION, DOMAIN,
                Map.of("basePath", "v1", "restApiId", API_ID, "stage", "prod"));
        verify(apiGateway, never()).deleteBasePathMapping(any(), any(), any());
        assertEquals("api.example.com|v1", r.getPhysicalId());
    }
}
