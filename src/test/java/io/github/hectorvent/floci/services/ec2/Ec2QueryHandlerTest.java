package io.github.hectorvent.floci.services.ec2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

class Ec2QueryHandlerTest {

    @Test
    void normalizesValuelessCreateSubnetTagsBeforeMutation() {
        Ec2Service service = mock(Ec2Service.class);
        Subnet subnet = new Subnet();
        subnet.setSubnetId("subnet-test");
        when(service.createSubnet("us-east-1", "vpc-test", "10.38.1.0/24", null, null, null))
                .thenReturn(subnet);
        MultivaluedMap<String, String> params = createSubnetParams("10.38.1.0/24");
        params.putSingle("TagSpecification.1.ResourceType", "subnet");
        params.putSingle("TagSpecification.1.Tag.1.Key", "omitted-value");
        params.putSingle("TagSpecification.1.Tag.2.Key", "explicit-empty-value");
        params.putSingle("TagSpecification.1.Tag.2.Value", "");
        params.putSingle("TagSpecification.1.Tag.3.Key", "ordinary-value");
        params.putSingle("TagSpecification.1.Tag.3.Value", "present");

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
}
