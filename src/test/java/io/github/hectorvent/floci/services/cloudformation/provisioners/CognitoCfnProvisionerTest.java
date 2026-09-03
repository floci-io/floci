package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cognito.CognitoService;
import io.github.hectorvent.floci.services.cognito.model.UserPoolDomain;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Cognito CFN provisioner in isolation: one mocked service. Every case asserts the exact
 * physical id and the exact {@code Fn::GetAtt} attribute keys, since an unmapped type still
 * reports CREATE_COMPLETE through the dispatcher's stub arm.
 */
class CognitoCfnProvisionerTest {

    private static final String TYPE = "AWS::Cognito::UserPoolDomain";
    private static final String REGION = "us-east-1";
    private static final String POOL_ID = "us-east-1_AbCdEfGhI";
    private static final String DOMAIN = "auth.example.com";
    private static final String CERTIFICATE_ARN =
            "arn:aws:acm:us-east-1:000000000000:certificate/11111111-2222-3333-4444-555555555555";
    private static final String RENEWED_CERTIFICATE_ARN =
            "arn:aws:acm:us-east-1:000000000000:certificate/99999999-2222-3333-4444-555555555555";
    private static final String CLOUDFRONT = "d1234567890abc.cloudfront.net";

    private final CognitoService cognito = mock(CognitoService.class);
    private final CognitoCfnProvisioner provisioner = new CognitoCfnProvisioner(cognito);
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

    private static StackResource resource(String physicalId, Map<String, String> attributes) {
        StackResource r = resource();
        r.setPhysicalId(physicalId);
        r.setAttributes(new HashMap<>(attributes));
        return r;
    }

    private static UserPoolDomain domain(String domain, String userPoolId, String cloudFront) {
        UserPoolDomain d = new UserPoolDomain();
        d.setDomain(domain);
        d.setUserPoolId(userPoolId);
        d.setCloudFrontDistribution(cloudFront);
        return d;
    }

    private ObjectNode customDomainProps(String certificateArn) {
        ObjectNode props = mapper.createObjectNode()
                .put("Domain", DOMAIN)
                .put("UserPoolId", POOL_ID)
                .put("ManagedLoginVersion", 2);
        props.putObject("CustomDomainConfig").put("CertificateArn", certificateArn);
        return props;
    }

    @Test
    void customDomainSetsDomainAsPhysicalIdAndCloudFrontAttribute() {
        when(cognito.createUserPoolDomain(DOMAIN, POOL_ID, Map.of("CertificateArn", CERTIFICATE_ARN), 2))
                .thenReturn(domain(DOMAIN, POOL_ID, CLOUDFRONT));
        StackResource r = resource();

        provisioner.provision(r, customDomainProps(CERTIFICATE_ARN), ctx());

        assertEquals(DOMAIN, r.getPhysicalId());
        assertEquals(Map.of("UserPoolId", POOL_ID, "CloudFrontDistribution", CLOUDFRONT), r.getAttributes());
    }

    @Test
    void prefixDomainHasAnEmptyCloudFrontDistribution() {
        when(cognito.createUserPoolDomain(eq("my-prefix"), eq(POOL_ID), isNull(), isNull()))
                .thenReturn(domain("my-prefix", POOL_ID, null));
        StackResource r = resource();

        provisioner.provision(r, mapper.createObjectNode().put("Domain", "my-prefix").put("UserPoolId", POOL_ID), ctx());

        assertEquals("my-prefix", r.getPhysicalId());
        assertEquals(Map.of("UserPoolId", POOL_ID, "CloudFrontDistribution", ""), r.getAttributes());
    }

    @Test
    void customDomainConfigPassesEveryFieldThrough() {
        when(cognito.createUserPoolDomain(any(), any(), any(), any())).thenReturn(domain(DOMAIN, POOL_ID, CLOUDFRONT));
        ObjectNode props = mapper.createObjectNode().put("Domain", DOMAIN).put("UserPoolId", POOL_ID);
        props.putObject("CustomDomainConfig")
                .put("CertificateArn", CERTIFICATE_ARN)
                .put("SecurityPolicy", "TLS_V1_2_2021");

        provisioner.provision(resource(), props, ctx());

        verify(cognito).createUserPoolDomain(DOMAIN, POOL_ID,
                Map.of("CertificateArn", CERTIFICATE_ARN, "SecurityPolicy", "TLS_V1_2_2021"), null);
    }

    @Test
    void customDomainConfigSkipsNullValues() {
        // A JSON null must not reach the service as the text "null", where it would pass for an ARN.
        when(cognito.createUserPoolDomain(any(), any(), any(), any())).thenReturn(domain(DOMAIN, POOL_ID, CLOUDFRONT));
        ObjectNode props = mapper.createObjectNode().put("Domain", DOMAIN).put("UserPoolId", POOL_ID);
        props.putObject("CustomDomainConfig")
                .putNull("CertificateArn")
                .put("SecurityPolicy", "TLS_V1_2_2021");

        provisioner.provision(resource(), props, ctx());

        verify(cognito).createUserPoolDomain(DOMAIN, POOL_ID, Map.of("SecurityPolicy", "TLS_V1_2_2021"), null);
    }

    @Test
    void requiresDomainAndUserPoolId() {
        assertThrows(IllegalArgumentException.class, () -> provisioner.provision(
                resource(), mapper.createObjectNode().put("UserPoolId", POOL_ID), ctx()));
        assertThrows(IllegalArgumentException.class, () -> provisioner.provision(
                resource(), mapper.createObjectNode().put("Domain", DOMAIN), ctx()));

        verify(cognito, never()).createUserPoolDomain(any(), any(), any(), any());
    }

    @Test
    void rejectsANonIntegerManagedLoginVersion() {
        ObjectNode props = customDomainProps(CERTIFICATE_ARN).put("ManagedLoginVersion", "two");

        assertThrows(IllegalArgumentException.class, () -> provisioner.provision(resource(), props, ctx()));

        verify(cognito, never()).createUserPoolDomain(any(), any(), any(), any());
    }

    @Test
    void updateWithUnchangedDomainAndPoolUpdatesInPlace() {
        when(cognito.describeUserPoolDomain(DOMAIN)).thenReturn(domain(DOMAIN, POOL_ID, CLOUDFRONT));
        when(cognito.updateUserPoolDomain(DOMAIN, POOL_ID, Map.of("CertificateArn", RENEWED_CERTIFICATE_ARN), 2))
                .thenReturn(domain(DOMAIN, POOL_ID, CLOUDFRONT));
        StackResource r = resource(DOMAIN, Map.of("UserPoolId", POOL_ID, "CloudFrontDistribution", CLOUDFRONT));

        provisioner.provision(r, customDomainProps(RENEWED_CERTIFICATE_ARN), ctx(DOMAIN));

        verify(cognito, never()).createUserPoolDomain(any(), any(), any(), any());
        verify(cognito, never()).deleteUserPoolDomain(any(), any());
        assertEquals(DOMAIN, r.getPhysicalId());
        assertEquals(Map.of("UserPoolId", POOL_ID, "CloudFrontDistribution", CLOUDFRONT), r.getAttributes());
    }

    @Test
    void updateWithChangedDomainReplacesTheDomain() {
        when(cognito.describeUserPoolDomain("old.example.com"))
                .thenReturn(domain("old.example.com", POOL_ID, "dold.cloudfront.net"));
        when(cognito.createUserPoolDomain(DOMAIN, POOL_ID, Map.of("CertificateArn", CERTIFICATE_ARN), 2))
                .thenReturn(domain(DOMAIN, POOL_ID, CLOUDFRONT));
        StackResource r = resource("old.example.com",
                Map.of("UserPoolId", POOL_ID, "CloudFrontDistribution", "dold.cloudfront.net"));

        provisioner.provision(r, customDomainProps(CERTIFICATE_ARN), ctx("old.example.com"));

        verify(cognito, never()).updateUserPoolDomain(any(), any(), any(), any());
        verify(cognito).deleteUserPoolDomain("old.example.com", POOL_ID);
        assertEquals(DOMAIN, r.getPhysicalId());
        assertEquals(Map.of("UserPoolId", POOL_ID, "CloudFrontDistribution", CLOUDFRONT), r.getAttributes());
    }

    @Test
    void updateWithChangedUserPoolFailsWhileTheDomainNameIsTaken() {
        // CloudFormation creates the replacement before deleting the original, and domain names are
        // unique across pools, so moving an unchanged domain to another pool fails on AWS too. The
        // original is left untouched for the rollback.
        String otherPool = "us-east-1_ZzZzZzZzZ";
        when(cognito.describeUserPoolDomain(DOMAIN)).thenReturn(domain(DOMAIN, otherPool, CLOUDFRONT));
        when(cognito.createUserPoolDomain(DOMAIN, POOL_ID, Map.of("CertificateArn", CERTIFICATE_ARN), 2))
                .thenThrow(new AwsException("InvalidParameterException",
                        "Domain " + DOMAIN + " already associated with another user pool", 400));
        StackResource r = resource(DOMAIN, Map.of("UserPoolId", otherPool, "CloudFrontDistribution", CLOUDFRONT));

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(r, customDomainProps(CERTIFICATE_ARN), ctx(DOMAIN)));

        assertEquals("InvalidParameterException", failure.getErrorCode());
        verify(cognito, never()).updateUserPoolDomain(any(), any(), any(), any());
        verify(cognito, never()).deleteUserPoolDomain(any(), any());
    }

    @Test
    void updateWhosePriorDomainIsGoneCreatesItAgain() {
        when(cognito.describeUserPoolDomain(DOMAIN))
                .thenThrow(new AwsException("ResourceNotFoundException", "Domain does not exist", 404));
        when(cognito.createUserPoolDomain(DOMAIN, POOL_ID, Map.of("CertificateArn", CERTIFICATE_ARN), 2))
                .thenReturn(domain(DOMAIN, POOL_ID, CLOUDFRONT));
        StackResource r = resource(DOMAIN, Map.of("UserPoolId", POOL_ID, "CloudFrontDistribution", CLOUDFRONT));

        provisioner.provision(r, customDomainProps(CERTIFICATE_ARN), ctx(DOMAIN));

        verify(cognito, never()).updateUserPoolDomain(any(), any(), any(), any());
        verify(cognito, never()).deleteUserPoolDomain(any(), any());
        assertEquals(DOMAIN, r.getPhysicalId());
    }

    @Test
    void replacementToleratesAPriorDomainThatIsAlreadyGone() {
        when(cognito.describeUserPoolDomain("old.example.com"))
                .thenReturn(domain("old.example.com", POOL_ID, "dold.cloudfront.net"));
        when(cognito.createUserPoolDomain(any(), any(), any(), any())).thenReturn(domain(DOMAIN, POOL_ID, CLOUDFRONT));
        doThrow(new AwsException("ResourceNotFoundException", "Domain does not exist", 404))
                .when(cognito).deleteUserPoolDomain("old.example.com", POOL_ID);
        StackResource r = resource("old.example.com", Map.of("UserPoolId", POOL_ID));

        assertDoesNotThrow(() -> provisioner.provision(r, customDomainProps(CERTIFICATE_ARN), ctx("old.example.com")));
        assertEquals(DOMAIN, r.getPhysicalId());
    }

    @Test
    void deleteUsesTheRecordedUserPoolId() {
        provisioner.delete(resource(DOMAIN, Map.of("UserPoolId", POOL_ID, "CloudFrontDistribution", CLOUDFRONT)), REGION);

        verify(cognito).deleteUserPoolDomain(DOMAIN, POOL_ID);
        verify(cognito, never()).describeUserPoolDomain(any());
    }

    @Test
    void deleteToleratesAnAlreadyDeletedDomain() {
        doThrow(new AwsException("ResourceNotFoundException", "Domain does not exist", 404))
                .when(cognito).deleteUserPoolDomain(DOMAIN, POOL_ID);

        assertDoesNotThrow(() -> provisioner.delete(resource(DOMAIN, Map.of("UserPoolId", POOL_ID)), REGION));
    }

    @Test
    void deletePropagatesOtherFailures() {
        doThrow(new AwsException("InvalidParameterException", "Domain is required", 400))
                .when(cognito).deleteUserPoolDomain(DOMAIN, POOL_ID);

        assertThrows(AwsException.class,
                () -> provisioner.delete(resource(DOMAIN, Map.of("UserPoolId", POOL_ID)), REGION));
    }

    @Test
    void deleteWithoutARecordedUserPoolIdLooksTheDomainUp() {
        when(cognito.describeUserPoolDomain(DOMAIN)).thenReturn(domain(DOMAIN, POOL_ID, CLOUDFRONT));

        provisioner.delete(resource(DOMAIN, Map.of()), REGION);

        verify(cognito).deleteUserPoolDomain(DOMAIN, POOL_ID);
    }

    @Test
    void deleteWithoutARecordedUserPoolIdOfAMissingDomainIsANoOp() {
        when(cognito.describeUserPoolDomain(DOMAIN))
                .thenThrow(new AwsException("ResourceNotFoundException", "Domain does not exist", 404));

        assertDoesNotThrow(() -> provisioner.delete(resource(DOMAIN, Map.of()), REGION));
        verify(cognito, never()).deleteUserPoolDomain(any(), any());
    }

    @Test
    void deleteByIdAloneLooksTheDomainUp() {
        when(cognito.describeUserPoolDomain(DOMAIN)).thenReturn(domain(DOMAIN, POOL_ID, CLOUDFRONT));

        provisioner.delete(TYPE, DOMAIN, REGION);

        verify(cognito).deleteUserPoolDomain(DOMAIN, POOL_ID);
    }
}
