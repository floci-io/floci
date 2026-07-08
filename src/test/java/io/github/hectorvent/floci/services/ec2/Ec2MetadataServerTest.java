package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.Placement;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.InstanceProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Ec2MetadataServerTest {

    @Test
    void instanceMetadataListsTagKeys() {
        Instance instance = new Instance();
        instance.setTags(List.of(
                new Tag("Environment", "dev"),
                new Tag("Service", "orders")));

        assertEquals("Environment\nService", Ec2MetadataServer.instanceTagKeys(instance));
    }

    @Test
    void instanceMetadataReturnsTagValue() {
        Instance instance = new Instance();
        instance.setTags(List.of(
                new Tag("Environment", "dev"),
                new Tag("Service", "orders")));

        assertEquals("orders", Ec2MetadataServer.instanceTagValue(instance, "Service").orElseThrow());
    }

    @Test
    void instanceMetadataReturnsEmptyValueForEmptyTag() {
        Instance instance = new Instance();
        instance.setTags(List.of(new Tag("Owner", null)));

        assertTrue(Ec2MetadataServer.instanceTagValue(instance, "Owner").isPresent());
        assertEquals("", Ec2MetadataServer.instanceTagValue(instance, "Owner").orElseThrow());
    }

    @Test
    void instanceMetadataReturnsMissingForUnknownTag() {
        Instance instance = new Instance();
        instance.setTags(List.of(new Tag("Environment", "dev")));

        assertTrue(Ec2MetadataServer.instanceTagValue(instance, "Missing").isEmpty());
    }

    @Test
    void identityDocumentUsesInstanceArchitectureWithX8664Fallback() {
        Instance instance = new Instance();
        instance.setInstanceId("i-arm");
        instance.setArchitecture("arm64");
        instance.setImageId("ami-arm");
        instance.setInstanceType("t4g.medium");
        instance.setPlacement(new Placement("us-west-2a"));
        instance.setPrivateIpAddress("10.0.0.10");
        instance.setRegion("us-west-2");

        assertTrue(Ec2MetadataServer.instanceIdentityDocument(instance, "000000000000")
                .contains("\"architecture\":\"arm64\""));

        instance.setArchitecture(null);
        assertTrue(Ec2MetadataServer.instanceIdentityDocument(instance, "000000000000")
                .contains("\"architecture\":\"x86_64\""));
    }

    @Test
    void staleContainerUnregisterDoesNotRemoveCurrentRegistration() {
        Ec2MetadataServer server = new Ec2MetadataServer(null, null, null);
        Instance oldInstance = new Instance();
        oldInstance.setInstanceId("i-old");
        Instance currentInstance = new Instance();
        currentInstance.setInstanceId("i-current");

        server.registerContainer("192.168.215.7", oldInstance.getInstanceId(), oldInstance);
        server.registerContainer("192.168.215.7", currentInstance.getInstanceId(), currentInstance);
        server.unregisterContainer("192.168.215.7", oldInstance);

        assertEquals(
                currentInstance,
                server.registeredContainer("192.168.215.7").orElseThrow());
    }

    @Test
    void iamCredentialRoleNameComesFromInstanceProfileRole() {
        IamService iamService = mock(IamService.class);
        InstanceProfile profile = new InstanceProfile();
        profile.setInstanceProfileName("sample-profile");
        profile.setRoleNames(List.of("sample-role"));
        when(iamService.getInstanceProfile("sample-profile")).thenReturn(profile);

        Ec2MetadataServer server = new Ec2MetadataServer(null, null, iamService);

        assertEquals("sample-role", server.resolveRoleName(
                "arn:aws:iam::000000000000:instance-profile/sample-profile"));
    }
}
