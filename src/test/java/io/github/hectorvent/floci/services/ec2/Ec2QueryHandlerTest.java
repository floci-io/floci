package io.github.hectorvent.floci.services.ec2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

import java.util.List;

class Ec2QueryHandlerTest {

    @Test
    void normalizesValuelessCreateSubnetTagsBeforeMutation() {
        Ec2Service service = mock(Ec2Service.class);
        Subnet subnet = new Subnet();
        subnet.setSubnetId("subnet-test");
        when(service.createSubnet("us-east-1", "vpc-test", "10.38.1.0/24", null))
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

    private Ec2QueryHandler handler(Ec2Service service) {
        return new Ec2QueryHandler(
                service, mock(EmulatorConfig.class), mock(FlowLogService.class));
    }

    private MultivaluedMap<String, String> createSubnetParams(String cidrBlock) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("VpcId", "vpc-test");
        params.putSingle("CidrBlock", cidrBlock);
        return params;
    }
}
