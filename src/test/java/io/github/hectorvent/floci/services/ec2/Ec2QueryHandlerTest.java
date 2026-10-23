package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsPartition;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ec2.model.KeyPair;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import static io.github.hectorvent.floci.core.common.SshPublicKeysTest.withTrailing;
import static io.github.hectorvent.floci.services.ec2.Ec2KeyPairFixtures.ED25519_BLOB;
import static io.github.hectorvent.floci.services.ec2.Ec2KeyPairFixtures.ED25519_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class Ec2QueryHandlerTest {

    @ParameterizedTest
    @CsvSource({
            "CreateVpc,vpc", "CreateSubnet,subnet", "CreateSecurityGroup,security-group",
            "CreateInternetGateway,internet-gateway", "CreateRouteTable,route-table",
            "CreateCapacityReservation,capacity-reservation", "AllocateHosts,dedicated-host",
            "AllocateAddress,elastic-ip", "AuthorizeSecurityGroupIngress,security-group-rule",
            "AuthorizeSecurityGroupEgress,security-group-rule", "RunInstances,instance",
            "RunInstances,network-interface", "CreateVolume,volume",
            "RequestSpotInstances,spot-instances-request", "RequestSpotInstances,instance"
    })
    void rejectsReservedCreationTagsBeforeAllocatingResources(String action, String resourceType) {
        for (String prefix : List.of("TagSpecification", "TagSpecifications")) {
            Ec2Service service = mock(Ec2Service.class);
            MultivaluedMap<String, String> params = createSubnetParams("10.38.1.0/24");
            params.putSingle("GroupName", "test");
            params.putSingle("GroupDescription", "test");
            params.putSingle("MinCount", "1");
            params.putSingle("MaxCount", "1");
            params.putSingle(prefix + ".1.ResourceType", resourceType);
            params.putSingle(prefix + ".1.Tag.1.Key", "Name");
            params.putSingle(prefix + ".1.Tag.1.Value", "valid");
            params.putSingle(prefix + ".1.Tag.2.Key", "aWs:reserved");
            params.putSingle(prefix + ".1.Tag.2.Value", "invalid");

            Response response = handler(service).handle(action, params, "us-east-1");

            assertEquals(400, response.getStatus());
            String code = List.of("vpc", "subnet", "security-group-rule", "spot-instances-request")
                    .contains(resourceType)
                    ? "InvalidParameter" : "InvalidParameterValue";
            assertTrue(response.getEntity().toString().contains("<Code>" + code + "</Code>"));
            assertTrue(response.getEntity().toString().contains("reserved for internal use"));
            verifyNoInteractions(service);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CreateLaunchTemplate", "CreateLaunchTemplateVersion"})
    void rejectsReservedNestedTemplateTagsBeforeCreatingATemplateOrVersion(String action) {
        Ec2Service service = mock(Ec2Service.class);
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("LaunchTemplateName", "test");
        params.putSingle("LaunchTemplateId", "lt-test");
        params.putSingle("LaunchTemplateData.TagSpecification.1.ResourceType", "instance");
        params.putSingle("LaunchTemplateData.TagSpecification.1.Tag.1.Key", "aws:reserved");

        Response response = handler(service).handle(action, params, "us-east-1");

        assertEquals(400, response.getStatus());
        assertTrue(response.getEntity().toString().contains("<Code>InvalidTagKey.Malformed</Code>"));
        verifyNoInteractions(service);
    }

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
        when(service.createVpc("us-east-1", "10.38.0.0/16", false, false, null)).thenReturn(vpc);
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

    @Test
    void describeRegionsListsTheDeploymentPartitionForARegionNoPartitionPublishes() {
        String xml = describeRegions(new RegionResolver("cn-north-1", "000000000000"), "polygondwanaland-west-1");

        assertTrue(xml.contains("<regionName>cn-north-1</regionName>"), xml);
        assertTrue(xml.contains("<regionEndpoint>ec2.cn-north-1.amazonaws.com.cn</regionEndpoint>"), xml);
        assertFalse(xml.contains("us-east-1"), xml);
    }

    @Test
    void describeRegionsKeepsAPublishedRegionInItsOwnPartition() {
        String xml = describeRegions(new RegionResolver("cn-north-1", "000000000000"), "us-gov-west-1");

        assertTrue(xml.contains("<regionEndpoint>ec2.us-gov-west-1.amazonaws.com</regionEndpoint>"), xml);
        assertFalse(xml.contains("cn-north-1"), xml);
    }

    /**
     * The key-pair name becomes the comment only when it can be one. A key pair stored before
     * KeyName was required has no name, which would otherwise read {@code null}, and a name with a
     * line break would split the key into lines an authorized_keys file reads as further keys (XML
     * turns a carriage return into a line feed on the way), so a name holding any control
     * character, a tab included, is left off.
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"two\nlines", "carriage\rreturn", "a\ttab"})
    void describeKeyPairsGivesTheKeyNoCommentWhenTheNameCannotBeOne(String name) {
        KeyPair keyPair = new KeyPair();
        keyPair.setKeyPairId("key-0123456789abcdef0");
        keyPair.setKeyName(name);
        keyPair.setPublicKey(ED25519_KEY + " imported-comment");

        String xml = describeKeyPairWithItsPublicKey(keyPair);

        assertTrue(xml.contains("<publicKey>" + ED25519_KEY + "</publicKey>"), xml);
    }

    /**
     * ImportKeyPair now refuses material that is not exactly one whole key, but a key pair saved by
     * an earlier version kept what it was given, so its description must still leave out a public
     * key it does not hold: an ssh-rsa type with no exponent or modulus, an ed25519 key one byte
     * short, an ed25519 blob followed by an extra field or by stray bytes, an RSA blob labelled
     * ssh-ed25519, two key lines, RFC 4716 text, and a UTF-16 file read as text.
     */
    @ParameterizedTest
    @MethodSource("storedMaterialThatIsNotOneWholeKey")
    void aKeyPairSavedWithMaterialThatIsNotOneWholeKeyIsDescribedWithoutAPublicKey(String material) {
        KeyPair keyPair = new KeyPair();
        keyPair.setKeyPairId("key-0123456789abcdef0");
        keyPair.setKeyName("saved-earlier");
        keyPair.setPublicKey(material);

        String xml = describeKeyPairWithItsPublicKey(keyPair);

        assertTrue(xml.contains("<keyName>saved-earlier</keyName>"), xml);
        assertFalse(xml.contains("<publicKey>"), xml);
    }

    static Stream<String> storedMaterialThatIsNotOneWholeKey() {
        byte[] ed25519 = Base64.getDecoder().decode(ED25519_BLOB);
        String rsaBlob = Ec2KeyMaterial.generateRsa().openSshPublicKey().split(" ")[1];
        return Stream.of(
                "ssh-rsa AAAAB3NzaC1yc2E= saved-earlier",
                "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAHwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA= saved-earlier",
                "ssh-ed25519 " + Base64.getEncoder().encodeToString(withTrailing(ed25519, new byte[] {0, 0, 0, 5, 'e', 'x', 't', 'r', 'a'})),
                "ssh-ed25519 " + Base64.getEncoder().encodeToString(withTrailing(ed25519, new byte[] {1, 2, 3})),
                "ssh-ed25519 " + rsaBlob + " saved-earlier",
                "ssh-ed25519 " + ED25519_BLOB + " first\n" + Ec2KeyMaterial.generateRsa().openSshPublicKey() + " second\n",
                "---- BEGIN SSH2 PUBLIC KEY ----\nComment: \"saved-earlier\"\n" + rsaBlob + "\n---- END SSH2 PUBLIC KEY ----\n",
                new String(("ssh-ed25519 " + ED25519_BLOB + " saved-earlier").getBytes(StandardCharsets.UTF_16LE),
                        StandardCharsets.UTF_8));
    }

    /** DescribeKeyPairs with IncludePublicKey=true over a service that holds only this key pair. */
    private String describeKeyPairWithItsPublicKey(KeyPair keyPair) {
        Ec2Service service = mock(Ec2Service.class);
        when(service.describeKeyPairs(eq("us-east-1"), anyList(), anyList(), anyList())).thenReturn(List.of(keyPair));
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("IncludePublicKey", "true");

        Response response = handler(service).handle("DescribeKeyPairs", params, "us-east-1");

        assertEquals(200, response.getStatus());
        return (String) response.getEntity();
    }

    private String describeRegions(RegionResolver regionResolver, String region) {
        Ec2Service service = mock(Ec2Service.class);
        when(service.describeRegions(any(AwsPartition.class), anyBoolean())).thenCallRealMethod();
        Response response = handler(service, regionResolver)
                .handle("DescribeRegions", new MultivaluedHashMap<>(), region);
        return (String) response.getEntity();
    }

    @Test
    void aSecurityGroupDryRunWithoutAVpcIdNeedsTheDefaultVpcToExist() {
        Ec2Service service = mock(Ec2Service.class);
        when(service.resolveDefaultVpcId("us-east-1")).thenReturn("vpc-deleted-default");
        when(service.requireVpc("us-east-1", "vpc-deleted-default")).thenThrow(new AwsException(
                "InvalidVpcID.NotFound", "The vpc ID 'vpc-deleted-default' does not exist", 400));
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle("GroupName", "test");
        params.putSingle("GroupDescription", "test");
        params.putSingle("DryRun", "true");

        Response response = handler(service).handle("CreateSecurityGroup", params, "us-east-1");

        assertEquals(400, response.getStatus());
        assertTrue(response.getEntity().toString().contains("<Code>InvalidVpcID.NotFound</Code>"));
        verify(service, never()).createSecurityGroup(any(), any(), any(), any());
    }

    private Ec2QueryHandler handler(Ec2Service service) {
        return handler(service, new RegionResolver("us-east-1", "000000000000"));
    }

    private Ec2QueryHandler handler(Ec2Service service, RegionResolver regionResolver) {
        return new Ec2QueryHandler(
                service, mock(EmulatorConfig.class), mock(FlowLogService.class),
                mock(Ec2EbsEncryptionService.class), mock(Ec2SnapshotBlockPublicAccessService.class),
                mock(Ec2IpamService.class), regionResolver);
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
