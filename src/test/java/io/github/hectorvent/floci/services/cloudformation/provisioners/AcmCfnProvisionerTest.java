package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.acm.AcmService;
import io.github.hectorvent.floci.services.acm.model.Certificate;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.services.acm.model.ValidationMethod;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * The ACM CFN provisioner in isolation: one mocked service. Every case asserts the exact physical
 * id and the exact {@code Fn::GetAtt} attribute keys, since an unmapped type still reports
 * CREATE_COMPLETE through the dispatcher's stub arm.
 */
class AcmCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String ARN = "arn:aws:acm:us-east-1:000000000000:certificate/11111111-2222-3333-4444-555555555555";

    private final AcmService acm = mock(AcmService.class);
    private final AcmCfnProvisioner provisioner = new AcmCfnProvisioner(acm);
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

    private StackResource resource() {
        StackResource r = new StackResource();
        r.setLogicalId("Cert");
        r.setResourceType("AWS::CertificateManager::Certificate");
        r.setAttributes(new HashMap<>());
        return r;
    }

    private static Certificate certificate(String arn, String domainName) {
        Certificate cert = new Certificate();
        cert.setArn(arn);
        cert.setDomainName(domainName);
        return cert;
    }

    @Test
    void certificateSetsArnAsPhysicalIdAndCertificateArnAttribute() {
        when(acm.requestCertificate(eq("api.example.com"), any(), any(), isNull(), any(), isNull(), any(), any(), eq(REGION)))
                .thenReturn(certificate(ARN, "api.example.com"));
        StackResource r = resource();
        ObjectNode props = mapper.createObjectNode().put("DomainName", "api.example.com");

        provisioner.provision(r, props, ctx());

        assertEquals(ARN, r.getPhysicalId());
        assertEquals(Map.of("CertificateArn", ARN), r.getAttributes());
    }

    @Test
    void certificatePassesTemplatePropertiesToRequestCertificate() {
        when(acm.requestCertificate(any(), any(), any(), isNull(), any(), isNull(), any(), any(), eq(REGION)))
                .thenReturn(certificate(ARN, "api.example.com"));
        StackResource r = resource();
        ObjectNode props = mapper.createObjectNode()
                .put("DomainName", "api.example.com")
                .put("ValidationMethod", "EMAIL")
                .put("KeyAlgorithm", "EC_prime256v1");
        props.putArray("SubjectAlternativeNames").add("www.example.com");
        props.putArray("Tags").addObject().put("Key", "env").put("Value", "test");

        provisioner.provision(r, props, ctx());

        verify(acm).requestCertificate("api.example.com", List.of("www.example.com"), ValidationMethod.EMAIL,
                null, KeyAlgorithm.EC_prime256v1, null, null, Map.of("env", "test"), REGION);
    }

    @Test
    void validationMethodDefaultsToDns() {
        when(acm.requestCertificate(any(), any(), any(), isNull(), any(), isNull(), any(), any(), eq(REGION)))
                .thenReturn(certificate(ARN, "api.example.com"));

        provisioner.provision(resource(), mapper.createObjectNode().put("DomainName", "api.example.com"), ctx());

        verify(acm).requestCertificate(eq("api.example.com"), eq(List.of()), eq(ValidationMethod.DNS), isNull(),
                eq(KeyAlgorithm.RSA_2048), isNull(), isNull(), eq(Map.of()), eq(REGION));
    }

    @Test
    void updateWithUnchangedCreateOnlyPropertiesKeepsTheCertificateAndReconcilesTags() {
        Certificate stored = certificate(ARN, "api.example.com");
        stored.setSubjectAlternativeNames(List.of("api.example.com", "www.example.com"));
        stored.setKeyAlgorithm(KeyAlgorithm.RSA_2048);
        when(acm.describeCertificate(ARN, REGION)).thenReturn(stored);
        when(acm.listTagsForCertificate(ARN, REGION)).thenReturn(Map.of("stale", "x", "env", "old"));
        StackResource r = resource();
        ObjectNode props = mapper.createObjectNode().put("DomainName", "api.example.com");
        props.putArray("SubjectAlternativeNames").add("www.example.com");
        props.putArray("Tags").addObject().put("Key", "env").put("Value", "test");

        provisioner.provision(r, props, ctx(ARN));

        verify(acm, never()).requestCertificate(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(acm, never()).deleteCertificate(any(), any());
        verify(acm).removeTagsFromCertificate(ARN, List.of(Map.of("Key", "stale")), REGION);
        verify(acm).addTagsToCertificate(ARN, Map.of("env", "test"), REGION);
        assertEquals(ARN, r.getPhysicalId());
        assertEquals(Map.of("CertificateArn", ARN), r.getAttributes());
    }

    @Test
    void updateWithChangedDomainNameReplacesTheCertificate() {
        String newArn = "arn:aws:acm:us-east-1:000000000000:certificate/99999999-2222-3333-4444-555555555555";
        Certificate stored = certificate(ARN, "old.example.com");
        stored.setSubjectAlternativeNames(List.of("old.example.com"));
        stored.setKeyAlgorithm(KeyAlgorithm.RSA_2048);
        when(acm.describeCertificate(ARN, REGION)).thenReturn(stored);
        when(acm.requestCertificate(eq("new.example.com"), any(), any(), isNull(), any(), isNull(), any(), any(), eq(REGION)))
                .thenReturn(certificate(newArn, "new.example.com"));
        StackResource r = resource();

        provisioner.provision(r, mapper.createObjectNode().put("DomainName", "new.example.com"), ctx(ARN));

        verify(acm).deleteCertificate(ARN, REGION);
        assertEquals(newArn, r.getPhysicalId());
        assertEquals(Map.of("CertificateArn", newArn), r.getAttributes());
    }

    @Test
    void replacementThatCannotDeleteThePriorCertificateRemovesTheNewOneAndFails() {
        // CloudFormationService restores the previous StackResource when an update fails and never
        // learns the new ARN, so the certificate this attempt created must not be left behind.
        String newArn = "arn:aws:acm:us-east-1:000000000000:certificate/99999999-2222-3333-4444-555555555555";
        Certificate stored = certificate(ARN, "old.example.com");
        stored.setSubjectAlternativeNames(List.of("old.example.com"));
        stored.setKeyAlgorithm(KeyAlgorithm.RSA_2048);
        when(acm.describeCertificate(ARN, REGION)).thenReturn(stored);
        when(acm.requestCertificate(eq("new.example.com"), any(), any(), isNull(), any(), isNull(), any(), any(), eq(REGION)))
                .thenReturn(certificate(newArn, "new.example.com"));
        doThrow(new AwsException("ResourceInUseException", "in use", 409))
                .when(acm).deleteCertificate(ARN, REGION);
        StackResource r = resource();

        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(
                r, mapper.createObjectNode().put("DomainName", "new.example.com"), ctx(ARN)));

        assertEquals("ResourceInUseException", failure.getErrorCode());
        verify(acm).deleteCertificate(newArn, REGION);
        assertEquals("true", r.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR));
        assertNull(r.getPhysicalId());
    }

    @Test
    void updateChangingOnlyValidationMethodKeepsTheCertificate() {
        // ValidationMethod is writeOnly in the registry schema and "No interruption" in the AWS
        // docs: it matters only while a certificate is being issued, and ACM has no call to change
        // it afterwards. So, as on AWS, an issued certificate is neither replaced nor touched.
        Certificate stored = certificate(ARN, "api.example.com");
        stored.setSubjectAlternativeNames(List.of("api.example.com"));
        stored.setKeyAlgorithm(KeyAlgorithm.RSA_2048);
        stored.setValidationMethod(ValidationMethod.DNS);
        when(acm.describeCertificate(ARN, REGION)).thenReturn(stored);
        StackResource r = resource();
        ObjectNode props = mapper.createObjectNode()
                .put("DomainName", "api.example.com")
                .put("ValidationMethod", "EMAIL");

        provisioner.provision(r, props, ctx(ARN));

        verify(acm, never()).requestCertificate(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(acm, never()).deleteCertificate(any(), any());
        assertEquals(ARN, r.getPhysicalId());
    }

    @Test
    void updateWhosePriorCertificateIsGoneRequestsANewOne() {
        when(acm.describeCertificate(ARN, REGION))
                .thenThrow(new AwsException("ResourceNotFoundException", "gone", 404));
        when(acm.requestCertificate(any(), any(), any(), isNull(), any(), isNull(), any(), any(), eq(REGION)))
                .thenReturn(certificate(ARN, "api.example.com"));
        StackResource r = resource();

        provisioner.provision(r, mapper.createObjectNode().put("DomainName", "api.example.com"), ctx(ARN));

        verify(acm, never()).deleteCertificate(any(), any());
        assertEquals(ARN, r.getPhysicalId());
    }

    @Test
    void deleteDelegatesToService() {
        provisioner.delete("AWS::CertificateManager::Certificate", ARN, REGION);
        verify(acm).deleteCertificate(ARN, REGION);
    }

    @Test
    void deleteToleratesAnAlreadyDeletedCertificate() {
        doThrow(new AwsException("ResourceNotFoundException", "gone", 404))
                .when(acm).deleteCertificate(ARN, REGION);

        assertDoesNotThrow(() -> provisioner.delete("AWS::CertificateManager::Certificate", ARN, REGION));
    }

    @Test
    void deletePropagatesACertificateStillInUse() {
        doThrow(new AwsException("ResourceInUseException", "in use", 409))
                .when(acm).deleteCertificate(ARN, REGION);

        assertThrows(AwsException.class,
                () -> provisioner.delete("AWS::CertificateManager::Certificate", ARN, REGION));
    }
}
