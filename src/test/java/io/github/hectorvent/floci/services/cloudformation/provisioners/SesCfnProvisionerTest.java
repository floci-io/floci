package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ses.SesService;
import io.github.hectorvent.floci.services.ses.model.Identity;
import io.github.hectorvent.floci.services.ses.model.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SesCfnProvisionerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SesService ses = mock(SesService.class);
    private final SesCfnProvisioner provisioner = new SesCfnProvisioner(ses);

    @Test
    void domainCreationSetsRefAndAllDkimDnsAttributes() throws Exception {
        Identity identity = domain("example.com");
        when(ses.createEmailIdentity(eq("example.com"), isNull(), eq(List.of()), eq("us-east-1")))
                .thenReturn(identity);
        when(ses.getEmailIdentity("example.com", "us-east-1")).thenReturn(identity);
        when(ses.listResourceTags(anyString(), eq("us-east-1"))).thenReturn(List.of());
        StackResource resource = resource();

        provisioner.provision(resource, props("{\"EmailIdentity\":\"example.com\"}"), context(null));

        assertEquals("example.com", resource.getPhysicalId());
        for (int index = 1; index <= 3; index++) {
            String token = "token" + index;
            assertEquals(token + "._domainkey.example.com",
                    resource.getAttributes().get("DkimDNSTokenName" + index));
            assertEquals(token + ".dkim.amazonses.com",
                    resource.getAttributes().get("DkimDNSTokenValue" + index));
        }
    }

    @Test
    void updateReconcilesOptionsAndTagsWithoutCreatingAgain() throws Exception {
        Identity identity = domain("example.com");
        identity.setConfigurationSetName("old");
        identity.setFeedbackForwardingEnabled(true);
        when(ses.getEmailIdentity("example.com", "us-east-1")).thenReturn(identity);
        when(ses.listResourceTags(anyString(), eq("us-east-1")))
                .thenReturn(List.of(new Tag("removed", "old"), new Tag("changed", "old"),
                        new Tag("external", "keep")));
        StackResource resource = resource();
        resource.getAttributes().put("__FlociSesManagedTags", """
                [{"Key":"removed","Value":"old"},{"Key":"changed","Value":"old"}]
                """);
        JsonNode props = props("""
                {"EmailIdentity":"example.com",
                 "ConfigurationSetAttributes":{"ConfigurationSetName":"new"},
                 "DkimSigningAttributes":{"NextSigningKeyLength":"RSA_1024_BIT"},
                 "DkimAttributes":{"SigningEnabled":false},
                 "MailFromAttributes":{"MailFromDomain":"mail.example.com",
                                       "BehaviorOnMxFailure":"REJECT_MESSAGE"},
                 "FeedbackAttributes":{"EmailForwardingEnabled":false},
                 "Tags":[{"Key":"changed","Value":"new"}]}
                """);

        provisioner.provision(resource, props, context("example.com"));

        verify(ses, never()).createEmailIdentity(anyString(), any(), any(), anyString());
        verify(ses).setEmailIdentityConfigurationSet("example.com", "new", "us-east-1");
        verify(ses).setEmailIdentityDkimSigningAttributes("example.com", "RSA_1024_BIT", "us-east-1");
        verify(ses).setEmailIdentityDkimAttributes("example.com", false, "us-east-1");
        verify(ses).setEmailIdentityMailFromAttributes("example.com", "mail.example.com",
                "RejectMessage", "us-east-1");
        verify(ses).setEmailIdentityFeedbackAttributes("example.com", false, "us-east-1");
        String arn = "arn:aws:ses:us-east-1:000000000000:identity/example.com";
        verify(ses).untagResource(arn, "us-east-1", List.of("removed"));
        verify(ses).tagResource(arn, "us-east-1", List.of(new Tag("changed", "new")));
    }

    @Test
    void changedIdentityCreatesReplacementWithoutDeletingPriorEntity() throws Exception {
        Identity identity = domain("other.example.com");
        when(ses.createEmailIdentity(eq("other.example.com"), isNull(), eq(List.of()), eq("us-east-1")))
                .thenReturn(identity);
        when(ses.getEmailIdentity("other.example.com", "us-east-1")).thenReturn(identity);
        when(ses.listResourceTags(anyString(), eq("us-east-1"))).thenReturn(List.of());
        StackResource resource = resource();
        String previousTags = "[{\"Key\":\"previous\",\"Value\":\"old\"}]";
        resource.getAttributes().put("__FlociSesManagedTags", previousTags);

        provisioner.provision(resource, props("{\"EmailIdentity\":\"other.example.com\"}"),
                context("example.com"));

        assertEquals("other.example.com", resource.getPhysicalId());
        verify(ses, never()).deleteIdentity("example.com", "us-east-1");
        assertTrue(provisioner.rollbackUpdate(resource));
        assertEquals("example.com", resource.getPhysicalId());
        assertEquals(previousTags, resource.getAttributes().get("__FlociSesManagedTags"));
        verify(ses).deleteIdentity("other.example.com", "us-east-1");
    }

    @Test
    void unsupportedByodkimFailsBeforeCreatingAnIdentity() throws Exception {
        JsonNode props = props("""
                {"EmailIdentity":"example.com",
                 "DkimSigningAttributes":{"DomainSigningSelector":"selector",
                                          "DomainSigningPrivateKey":"secret"}}
                """);

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(resource(), props, context(null)));

        assertEquals("ValidationError", failure.getErrorCode());
        assertTrue(failure.getMessage().contains("BYODKIM"));
        verify(ses, never()).createEmailIdentity(anyString(), any(), any(), anyString());
    }

    @Test
    void failedPostCreateConfigurationRemovesTheNewIdentity() throws Exception {
        Identity identity = domain("example.com");
        when(ses.createEmailIdentity(eq("example.com"), isNull(), eq(List.of()), eq("us-east-1")))
                .thenReturn(identity);
        doThrow(new AwsException("BadRequestException", "MAIL FROM rejected", 400))
                .when(ses).setEmailIdentityMailFromAttributes("example.com", "mail.example.com",
                        "UseDefaultValue", "us-east-1");
        JsonNode props = props("""
                {"EmailIdentity":"example.com",
                 "MailFromAttributes":{"MailFromDomain":"mail.example.com"}}
                """);

        assertThrows(AwsException.class, () -> provisioner.provision(resource(), props, context(null)));

        verify(ses).deleteIdentity("example.com", "us-east-1");
    }

    @Test
    void failedPostCreateReadRemovesTheNewIdentity() throws Exception {
        Identity identity = domain("example.com");
        when(ses.createEmailIdentity(eq("example.com"), isNull(), eq(List.of()), eq("us-east-1")))
                .thenReturn(identity);
        when(ses.listResourceTags(anyString(), eq("us-east-1"))).thenReturn(List.of());
        when(ses.getEmailIdentity("example.com", "us-east-1"))
                .thenThrow(new AwsException("ServiceUnavailableException", "temporary", 503));
        StackResource resource = resource();

        assertThrows(AwsException.class, () -> provisioner.provision(resource,
                props("{\"EmailIdentity\":\"example.com\"}"), context(null)));

        verify(ses).deleteIdentity("example.com", "us-east-1");
        assertNull(resource.getPhysicalId());
        assertTrue(resource.getAttributes().isEmpty());
    }

    @Test
    void failedReplacementReadRestoresPriorResourceMetadata() throws Exception {
        Identity identity = domain("other.example.com");
        when(ses.createEmailIdentity(eq("other.example.com"), isNull(), eq(List.of()), eq("us-east-1")))
                .thenReturn(identity);
        when(ses.listResourceTags(anyString(), eq("us-east-1"))).thenReturn(List.of());
        when(ses.getEmailIdentity("other.example.com", "us-east-1"))
                .thenThrow(new AwsException("ServiceUnavailableException", "temporary", 503));
        StackResource resource = resource();
        resource.setPhysicalId("example.com");
        resource.getAttributes().put("__FlociSesManagedTags", "old");

        assertThrows(AwsException.class, () -> provisioner.provision(resource,
                props("{\"EmailIdentity\":\"other.example.com\"}"), context("example.com")));

        verify(ses).deleteIdentity("other.example.com", "us-east-1");
        assertEquals("example.com", resource.getPhysicalId());
        assertEquals(Map.of("__FlociSesManagedTags", "old"), resource.getAttributes());
    }

    @Test
    void failedPostCreateCleanupRetainsIdentityForStackRollback() throws Exception {
        Identity identity = domain("example.com");
        when(ses.createEmailIdentity(eq("example.com"), isNull(), eq(List.of()), eq("us-east-1")))
                .thenReturn(identity);
        doThrow(new AwsException("BadRequestException", "MAIL FROM rejected", 400))
                .when(ses).setEmailIdentityMailFromAttributes("example.com", "mail.example.com",
                        "UseDefaultValue", "us-east-1");
        doThrow(new AwsException("ServiceUnavailableException", "temporary", 503))
                .doNothing().when(ses).deleteIdentity("example.com", "us-east-1");
        StackResource resource = resource();
        JsonNode props = props("""
                {"EmailIdentity":"example.com",
                 "MailFromAttributes":{"MailFromDomain":"mail.example.com"}}
                """);

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(resource, props, context(null)));

        assertEquals(1, failure.getSuppressed().length);
        assertEquals("example.com", resource.getPhysicalId());
        provisioner.delete(resource, "us-east-1");
        verify(ses, times(2)).deleteIdentity("example.com", "us-east-1");
    }

    @Test
    void malformedOptionFailsBeforeCreatingAnIdentity() throws Exception {
        JsonNode props = props("""
                {"EmailIdentity":"example.com",
                 "FeedbackAttributes":{"EmailForwardingEnabled":"false"}}
                """);

        assertThrows(AwsException.class, () -> provisioner.provision(resource(), props, context(null)));
        verify(ses, never()).createEmailIdentity(anyString(), any(), any(), anyString());
    }

    @Test
    void deleteDelegatesToSes() {
        provisioner.delete("AWS::SES::EmailIdentity", "example.com", "us-east-1");
        verify(ses).deleteIdentity("example.com", "us-east-1");
    }

    @Test
    void failedDisplacedIdentityCleanupDoesNotReportStackDeleteSuccess() throws Exception {
        Identity replacement = domain("other.example.com");
        when(ses.createEmailIdentity(eq("other.example.com"), isNull(), eq(List.of()), eq("us-east-1")))
                .thenReturn(replacement);
        when(ses.getEmailIdentity("other.example.com", "us-east-1")).thenReturn(replacement);
        when(ses.listResourceTags(anyString(), eq("us-east-1"))).thenReturn(List.of());
        StackResource resource = resource();
        provisioner.provision(resource, props("{\"EmailIdentity\":\"other.example.com\"}"),
                context("example.com"));
        doThrow(new AwsException("ServiceUnavailableException", "temporary", 503))
                .when(ses).deleteIdentity("example.com", "us-east-1");

        assertThrows(AwsException.class, () -> provisioner.delete(resource, "us-east-1"));

        verify(ses, never()).deleteIdentity("other.example.com", "us-east-1");
    }

    private Identity domain(String name) {
        Identity identity = new Identity(name, "Domain");
        identity.setDkimEnabled(true);
        identity.setDkimTokens(List.of("token1", "token2", "token3"));
        return identity;
    }

    private StackResource resource() {
        StackResource resource = new StackResource();
        resource.setLogicalId("Identity");
        resource.setResourceType("AWS::SES::EmailIdentity");
        resource.setAttributes(new HashMap<>());
        return resource;
    }

    private ProvisionContext context(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolveNode(any())).thenAnswer(call -> call.getArgument(0));
        return new ProvisionContext(engine, "us-east-1", "000000000000", "ses-stack", priorPhysicalId);
    }

    private JsonNode props(String json) throws Exception {
        return mapper.readTree(json);
    }
}
