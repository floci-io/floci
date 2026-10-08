package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class Ec2QueryHandlerTest {

    @ParameterizedTest
    @ValueSource(strings = {"TagSpecification", "TagSpecifications"})
    void normalizesValuelessCreateSubnetTagsBeforeMutation(String prefix) {
        Ec2Service service = mock(Ec2Service.class);
        Subnet subnet = new Subnet();
        subnet.setSubnetId("subnet-test");
        when(service.createSubnet("us-east-1", "vpc-test", "10.38.1.0/24", null, null, null))
                .thenReturn(subnet);
        MultivaluedMap<String, String> params = createSubnetParams("10.38.1.0/24");
        params.putSingle(prefix + ".1.ResourceType", "subnet");
        params.putSingle(prefix + ".1.Tag.1.Key", "omitted-value");
        params.putSingle(prefix + ".1.Tag.2.Key", "explicit-empty-value");
        params.putSingle(prefix + ".1.Tag.2.Value", "");
        params.putSingle(prefix + ".1.Tag.3.Key", "ordinary-value");
        params.putSingle(prefix + ".1.Tag.3.Value", "present");

        Response response = handler(service).handle("CreateSubnet", params, "us-east-1");

        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Tag>> tags = ArgumentCaptor.forClass(List.class);
        verify(service).createTags(eq("us-east-1"), eq(List.of("subnet-test")), tags.capture());
        assertEquals(List.of("", "", "present"),
                tags.getValue().stream().map(Tag::getValue).toList());
    }

    @Test
    void createTagsStoresOmittedValueAsEmptyString() {
        Ec2Service service = mock(Ec2Service.class);
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ResourceId.1", "vpc-test");
        params.putSingle("Tag.1.Key", "omitted-value");
        params.putSingle("Tag.2.Key", "explicit-empty-value");
        params.putSingle("Tag.2.Value", "");
        params.putSingle("Tag.3.Key", "ordinary-value");
        params.putSingle("Tag.3.Value", "present");

        Response response = handler(service).handle("CreateTags", params, "us-east-1");

        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Tag>> tags = ArgumentCaptor.forClass(List.class);
        verify(service).createTags(eq("us-east-1"), eq(List.of("vpc-test")), tags.capture());
        assertEquals(List.of("", "", "present"),
                tags.getValue().stream().map(Tag::getValue).toList());
    }

    @Test
    void deleteTagsKeepsOmittedValueAsNullSoItMatchesAnyValue() {
        Ec2Service service = mock(Ec2Service.class);
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("ResourceId.1", "vpc-test");
        params.putSingle("Tag.1.Key", "any-value");

        Response response = handler(service).handle("DeleteTags", params, "us-east-1");

        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Tag>> tags = ArgumentCaptor.forClass(List.class);
        verify(service).deleteTags(eq("us-east-1"), eq(List.of("vpc-test")), tags.capture());
        assertNull(tags.getValue().getFirst().getValue());
    }

    @Test
    void normalizesValuelessCreateVpcTagsBeforeMutation() {
        Ec2Service service = mock(Ec2Service.class);
        Vpc vpc = new Vpc();
        vpc.setVpcId("vpc-test");
        when(service.createVpc("us-east-1", "10.38.0.0/16", false, false)).thenReturn(vpc);
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("CidrBlock", "10.38.0.0/16");
        params.putSingle("TagSpecification.1.ResourceType", "vpc");
        params.putSingle("TagSpecification.1.Tag.1.Key", "omitted-value");

        Response response = handler(service).handle("CreateVpc", params, "us-east-1");

        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Tag>> tags = ArgumentCaptor.forClass(List.class);
        verify(service).createTags(eq("us-east-1"), eq(List.of("vpc-test")), tags.capture());
        assertEquals(List.of(""), tags.getValue().stream().map(Tag::getValue).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"TagSpecification", "TagSpecifications"})
    void validatesEveryCreateSubnetTagSpecificationBeforeMutation(String prefix) {
        Ec2Service service = mock(Ec2Service.class);
        MultivaluedMap<String, String> params = createSubnetParams("10.39.1.0/24");
        params.putSingle(prefix + ".1.ResourceType", "subnet");
        params.putSingle(prefix + ".1.Tag.1.Key", "Name");
        params.putSingle(prefix + ".1.Tag.1.Value", "valid");
        params.putSingle(prefix + ".2.ResourceType", "vpc");
        params.putSingle(prefix + ".2.Tag.1.Key", "Name");

        Response response = handler(service).handle("CreateSubnet", params, "us-east-1");

        assertInvalidParameterValue(response, "resource type &apos;vpc&apos;");
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TagSpecification", "TagSpecifications"})
    void rejectsCreateSubnetTagSpecificationWithoutResourceTypeBeforeMutation(String prefix) {
        Ec2Service service = mock(Ec2Service.class);
        MultivaluedMap<String, String> params = createSubnetParams("10.40.1.0/24");
        params.putSingle(prefix + ".1.Tag.1.Key", "Name");
        params.putSingle(prefix + ".1.Tag.1.Value", "invalid");

        Response response = handler(service).handle("CreateSubnet", params, "us-east-1");

        assertInvalidParameterValue(response, "resource type &apos;&apos;");
        verifyNoInteractions(service);
    }

    @Test
    void validatesPluralSpecificationsEvenWhenSingularTagsAreValid() {
        Ec2Service service = mock(Ec2Service.class);
        MultivaluedMap<String, String> params = createSubnetParams("10.41.1.0/24");
        params.putSingle("TagSpecification.1.ResourceType", "subnet");
        params.putSingle("TagSpecification.1.Tag.1.Key", "Name");
        params.putSingle("TagSpecifications.1.ResourceType", "vpc");

        Response response = handler(service).handle("CreateSubnet", params, "us-east-1");

        assertInvalidParameterValue(response, "resource type &apos;vpc&apos;");
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletesSubnetWhenTaggingFailsAndPreservesOriginalFailure(boolean cleanupFails) {
        Ec2Service service = mock(Ec2Service.class);
        Subnet subnet = new Subnet();
        subnet.setSubnetId("subnet-test");
        when(service.createSubnet("us-east-1", "vpc-test", "10.42.1.0/24", null, null, null))
                .thenReturn(subnet);
        IllegalStateException taggingFailure = new IllegalStateException("tag storage failed");
        doThrow(taggingFailure).when(service).createTags(eq("us-east-1"), eq(List.of("subnet-test")),
                anyList());
        IllegalStateException cleanupFailure = new IllegalStateException("subnet storage failed");
        if (cleanupFails) {
            doThrow(cleanupFailure).when(service).deleteSubnet("us-east-1", "subnet-test");
        }
        MultivaluedMap<String, String> params = createSubnetParams("10.42.1.0/24");
        params.putSingle("TagSpecification.1.ResourceType", "subnet");
        params.putSingle("TagSpecification.1.Tag.1.Key", "Name");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> handler(service).handle("CreateSubnet", params, "us-east-1"));

        assertSame(taggingFailure, thrown);
        verify(service).deleteSubnet("us-east-1", "subnet-test");
        assertEquals(cleanupFails ? List.of(cleanupFailure) : List.of(), List.of(thrown.getSuppressed()));
    }

    private Ec2QueryHandler handler(Ec2Service service) {
        return new Ec2QueryHandler(
                service, mock(EmulatorConfig.class), mock(FlowLogService.class),
                mock(Ec2EbsEncryptionService.class), mock(Ec2SnapshotBlockPublicAccessService.class),
                mock(Ec2IpamService.class));
    }

    private MultivaluedMap<String, String> createSubnetParams(String cidrBlock) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("VpcId", "vpc-test");
        params.putSingle("CidrBlock", cidrBlock);
        return params;
    }

    private void assertInvalidParameterValue(Response response, String messageFragment) {
        assertEquals(400, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<Code>InvalidParameterValue</Code>"));
        assertTrue(body.contains(messageFragment), body);
    }
}
