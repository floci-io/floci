package io.github.hectorvent.floci.services.ec2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

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

    @Test
    void validatesEveryCreateSubnetTagSpecificationBeforeMutation() {
        Ec2Service service = mock(Ec2Service.class);
        MultivaluedMap<String, String> params = createSubnetParams("10.39.1.0/24");
        params.putSingle("TagSpecification.1.ResourceType", "subnet");
        params.putSingle("TagSpecification.1.Tag.1.Key", "Name");
        params.putSingle("TagSpecification.1.Tag.1.Value", "valid");
        params.putSingle("TagSpecification.2.ResourceType", "vpc");
        params.putSingle("TagSpecification.2.Tag.1.Key", "Name");
        params.putSingle("TagSpecification.2.Tag.1.Value", "invalid");

        Response response = handler(service).handle("CreateSubnet", params, "us-east-1");

        assertInvalidParameterValue(response, "resource type &apos;vpc&apos;");
        verifyNoInteractions(service);
    }

    @Test
    void rejectsCreateSubnetTagSpecificationWithoutResourceTypeBeforeMutation() {
        Ec2Service service = mock(Ec2Service.class);
        MultivaluedMap<String, String> params = createSubnetParams("10.40.1.0/24");
        params.putSingle("TagSpecification.1.Tag.1.Key", "Name");
        params.putSingle("TagSpecification.1.Tag.1.Value", "invalid");

        Response response = handler(service).handle("CreateSubnet", params, "us-east-1");

        assertInvalidParameterValue(response, "resource type &apos;&apos;");
        verifyNoInteractions(service);
    }

    @Test
    void rejectsSparseCreateSubnetTagSpecificationBeforeMutation() {
        Ec2Service service = mock(Ec2Service.class);
        MultivaluedMap<String, String> params = createSubnetParams("10.41.1.0/24");
        params.putSingle("TagSpecification.2.ResourceType", "subnet");
        params.putSingle("TagSpecification.2.Tag.1.Key", "Name");
        params.putSingle("TagSpecification.2.Tag.1.Value", "invalid");

        Response response = handler(service).handle("CreateSubnet", params, "us-east-1");

        assertInvalidParameterValue(response, "member index &apos;2&apos;");
        verifyNoInteractions(service);
    }

    @Test
    void rejectsNonnumericCreateSubnetTagSpecificationBeforeMutation() {
        Ec2Service service = mock(Ec2Service.class);
        MultivaluedMap<String, String> params = createSubnetParams("10.42.1.0/24");
        params.putSingle("TagSpecification.member.ResourceType", "subnet");
        params.putSingle("TagSpecification.member.Tag.1.Key", "Name");
        params.putSingle("TagSpecification.member.Tag.1.Value", "invalid");

        Response response = handler(service).handle("CreateSubnet", params, "us-east-1");

        assertInvalidParameterValue(response, "member index &apos;member&apos;");
        verifyNoInteractions(service);
    }

    @Test
    void rejectsDuplicateCreateSubnetTagSpecificationIndexBeforeMutation() {
        Ec2Service service = mock(Ec2Service.class);
        MultivaluedMap<String, String> params = createSubnetParams("10.43.1.0/24");
        params.putSingle("TagSpecification.1.ResourceType", "subnet");
        params.putSingle("TagSpecification.01.ResourceType", "subnet");

        Response response = handler(service).handle("CreateSubnet", params, "us-east-1");

        assertInvalidParameterValue(response, "member index &apos;01&apos;");
        verifyNoInteractions(service);
    }

    @Test
    void rejectsMalformedCreateSubnetTagSpecificationIndexBeforeMutation() {
        Ec2Service service = mock(Ec2Service.class);
        MultivaluedMap<String, String> params = createSubnetParams("10.44.1.0/24");
        params.putSingle("TagSpecification.0.ResourceType", "subnet");

        Response response = handler(service).handle("CreateSubnet", params, "us-east-1");

        assertInvalidParameterValue(response, "member index &apos;0&apos;");
        verifyNoInteractions(service);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedNestedTagMembers")
    void rejectsMalformedNestedCreateSubnetTagMembersBeforeMutation(
            String description, Consumer<MultivaluedMap<String, String>> malformedParameters) {
        Ec2Service service = mock(Ec2Service.class);
        MultivaluedMap<String, String> params = createSubnetParams("10.45.1.0/24");
        params.putSingle("TagSpecification.1.ResourceType", "subnet");
        malformedParameters.accept(params);

        Response response = handler(service).handle("CreateSubnet", params, "us-east-1");

        assertInvalidParameterValue(response, "Tag");
        verifyNoInteractions(service);
    }

    private static Stream<Arguments> malformedNestedTagMembers() {
        return Stream.of(
                malformed("sparse tag index", params -> putTag(params, "2", "Name", "invalid")),
                malformed("nonnumeric tag index", params -> putTag(params, "member", "Name", "invalid")),
                malformed("zero tag index", params -> putTag(params, "0", "Name", "invalid")),
                malformed("noncanonical tag index", params -> putTag(params, "01", "Name", "invalid")),
                malformed("value without key", params ->
                        params.putSingle("TagSpecification.1.Tag.1.Value", "invalid")),
                malformed("duplicate key", params -> {
                    params.put("TagSpecification.1.Tag.1.Key", List.of("Name", "Other"));
                    params.putSingle("TagSpecification.1.Tag.1.Value", "invalid");
                }),
                malformed("duplicate value", params -> {
                    params.putSingle("TagSpecification.1.Tag.1.Key", "Name");
                    params.put("TagSpecification.1.Tag.1.Value", List.of("one", "two"));
                }),
                malformed("unknown tag field", params ->
                        params.putSingle("TagSpecification.1.Tag.1.Unknown", "invalid")),
                malformed("malformed value structure", params ->
                        params.putSingle("TagSpecification.1.Tag.1.Value.Member", "invalid")),
                malformed("incomplete tag member", params ->
                        params.putSingle("TagSpecification.1.Tag.1", "invalid")),
                malformed("unknown specification field", params ->
                        params.putSingle("TagSpecification.1.Unknown", "invalid")));
    }

    private static Arguments malformed(
            String description, Consumer<MultivaluedMap<String, String>> malformedParameters) {
        return Arguments.of(description, malformedParameters);
    }

    private static void putTag(
            MultivaluedMap<String, String> params, String index, String key, String value) {
        params.putSingle("TagSpecification.1.Tag." + index + ".Key", key);
        params.putSingle("TagSpecification.1.Tag." + index + ".Value", value);
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

    private void assertInvalidParameterValue(Response response, String messageFragment) {
        assertEquals(400, response.getStatus());
        String body = (String) response.getEntity();
        assertTrue(body.contains("<Code>InvalidParameterValue</Code>"));
        assertTrue(body.contains(messageFragment), body);
    }
}
