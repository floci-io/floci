package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.emr.EmrStudioService;
import io.floci.aws.services.emr.EmrStudioService.Identity;
import io.floci.aws.services.emr.model.EmrStudio;
import io.floci.aws.services.emr.model.EmrStudioSessionMapping;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.floci.aws.services.cloudformation.provisioners.ProvisionContexts.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** {@code AWS::EMR::Studio} and {@code AWS::EMR::StudioSessionMapping} in isolation, with the Studio service mocked. */
@ResourceLock(ParallelLocks.PORTS)
class EmrStudioCfnProvisionerTest {

    private static final String STUDIO_ID = "es-0123456789ABCDEFGHIJKLMNO";
    private static final String STUDIO_ARN = "arn:aws:elasticmapreduce:us-east-1:000000000000:studio/" + STUDIO_ID;
    private static final String POLICY = "arn:aws:iam::000000000000:policy/EMRStudioSession";
    private static final String MAPPING_ID = STUDIO_ID + "|USER|ana";

    private final EmrStudioService studios = mock(EmrStudioService.class);
    private final EmrStudioCfnProvisioner provisioner = new EmrStudioCfnProvisioner(studios);

    private static ProvisionContext ctx(String priorPhysicalId, JsonNode priorProperties) {
        return ProvisionContexts.standalone(priorPhysicalId, priorProperties);
    }

    private static StackResource resource(String type, String physicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(type.endsWith("Studio") ? "Studio" : "Mapping");
        r.setResourceType(type);
        r.setPhysicalId(physicalId);
        r.setAttributes(new HashMap<>());
        return r;
    }


    /** A Studio template using IAM authentication, named {@code name} and tagged {@code stage}. */
    private static ObjectNode studioProps(String name, String stage) {
        return (ObjectNode) json("""
                {"Name": "%s", "AuthMode": "IAM", "VpcId": "vpc-0123456789abcdef0",
                 "SubnetIds": ["subnet-0123456789abcdef0"], "ServiceRole": "arn:aws:iam::000000000000:role/EMRStudio",
                 "WorkspaceSecurityGroupId": "sg-0123456789abcdef0", "EngineSecurityGroupId": "sg-0123456789abcdef1",
                 "DefaultS3Location": "s3://studio-bucket/workspaces", "Tags": [{"Key": "stage", "Value": "%s"}]}
                """.formatted(name, stage));
    }

    private static EmrStudio studio(String name, Map<String, String> tags) {
        EmrStudio studio = new EmrStudio();
        studio.setStudioId(STUDIO_ID);
        studio.setStudioArn(STUDIO_ARN);
        studio.setUrl("http://localhost:4566/_aws/emr/studios/" + STUDIO_ID);
        studio.setName(name);
        studio.setDefaultS3Location("s3://studio-bucket/workspaces");
        studio.setTags(new LinkedHashMap<>(tags));
        return studio;
    }

    /** The Studio as the create that made it left the resource, owning the tag key {@code stage}. */
    private static StackResource provisionedStudio() {
        StackResource r = resource("AWS::EMR::Studio", STUDIO_ID);
        r.getAttributes().put(EmrStudioCfnProvisioner.TAG_KEYS_ATTR, "[\"stage\"]");
        return r;
    }

    @Test
    void createsTheStudioAndPublishesItsAttributes() {
        when(studios.createStudio(any())).thenReturn(studio("notebooks", Map.of("stage", "v1")));
        StackResource r = resource("AWS::EMR::Studio", null);

        provisioner.provision(r, studioProps("notebooks", "v1"), ctx(null, null));

        ArgumentCaptor<EmrStudio> created = ArgumentCaptor.forClass(EmrStudio.class);
        verify(studios).createStudio(created.capture());
        EmrStudio request = created.getValue();
        assertEquals("notebooks", request.getName());
        assertEquals("IAM", request.getAuthMode());
        assertEquals(List.of("subnet-0123456789abcdef0"), request.getSubnetIds());
        assertEquals("sg-0123456789abcdef1", request.getEngineSecurityGroupId());
        assertEquals(Map.of("stage", "v1"), request.getTags());
        assertEquals(STUDIO_ID, r.getPhysicalId());
        assertEquals(STUDIO_ID, r.getAttributes().get("StudioId"));
        assertEquals(STUDIO_ARN, r.getAttributes().get("Arn"));
        assertEquals("http://localhost:4566/_aws/emr/studios/" + STUDIO_ID, r.getAttributes().get("Url"));
    }

    @Test
    void aTemplateThatSetsAReadOnlyPropertyIsRefusedBeforeAnyCall() {
        ObjectNode props = studioProps("notebooks", "v1");
        props.put("Url", "https://example.com");

        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.provision(resource("AWS::EMR::Studio", null), props, ctx(null, null)));

        assertEquals("Url is a ReadOnly property which cannot be specified in the template", e.getMessage());
        verifyNoInteractions(studios);
    }

    @Test
    void anUpdateRenamesTheStudioAndChangesOnlyTheTagKeysTheStackOwns() {
        when(studios.describeStudio(STUDIO_ID))
                .thenReturn(studio("notebooks", Map.of("stage", "v1", "owner", "outside")));
        StackResource r = provisionedStudio();
        ObjectNode props = studioProps("renamed", "v2");
        props.put("IdpAuthUrl", "https://idp.example/login");

        provisioner.provision(r, props, ctx(STUDIO_ID, studioProps("notebooks", "v1")));

        verify(studios).updateStudio(STUDIO_ID, "renamed", null, null, null, null);
        verify(studios).addTags(STUDIO_ID, Map.of("stage", "v2"));
        verify(studios, never()).removeTags(any(), any());
        verify(studios, never()).createStudio(any());
        assertEquals(STUDIO_ID, r.getPhysicalId());
    }

    @Test
    void aRollbackPutsBackTheNameAndTheTags() {
        when(studios.describeStudio(STUDIO_ID)).thenReturn(studio("notebooks", Map.of("stage", "v1")));
        StackResource r = provisionedStudio();
        provisioner.provision(r, studioProps("renamed", "v2"), ctx(STUDIO_ID, studioProps("notebooks", "v1")));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(studios).updateStudio(STUDIO_ID, "notebooks", null, null, null, null);
        verify(studios).addTags(STUDIO_ID, Map.of("stage", "v1"));
        assertFalse(r.getAttributes().containsKey(EmrStudioCfnProvisioner.STUDIO_SNAPSHOT_ATTR));
    }

    @Test
    void anUpdateTheStudioRefusesChangedNothingSoNothingIsPutBack() {
        when(studios.describeStudio(STUDIO_ID)).thenReturn(studio("notebooks", Map.of("stage", "v1")));
        when(studios.updateStudio(eq(STUDIO_ID), any(), any(), any(), any(), any()))
                .thenThrow(new AwsException("InvalidRequestException", "refused", 400));
        StackResource r = provisionedStudio();

        assertThrows(AwsException.class, () -> provisioner.provision(r, studioProps("renamed", "v2"),
                ctx(STUDIO_ID, studioProps("notebooks", "v1"))));

        verify(studios, never()).addTags(any(), any());
        assertNull(r.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
    }

    @Test
    void anUpdateOfAStudioThatIsGoneFails() {
        when(studios.describeStudio(STUDIO_ID))
                .thenThrow(new AwsException("InvalidRequestException", "Studio does not exist.", 400));

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(provisionedStudio(),
                studioProps("renamed", "v2"), ctx(STUDIO_ID, studioProps("notebooks", "v1"))));

        assertEquals("Resource of type 'AWS::EMR::Studio' with identifier '" + STUDIO_ID + "' was not found.",
                e.getMessage());
    }

    @Test
    void aSessionMappingIsCreatedUnderItsPrimaryIdentifier() {
        StackResource r = resource("AWS::EMR::StudioSessionMapping", null);

        provisioner.provision(r, mappingProps("ana", POLICY), ctx(null, null));

        verify(studios).createSessionMapping(STUDIO_ID, new Identity("USER", null, "ana"), POLICY);
        assertEquals(MAPPING_ID, r.getPhysicalId());
    }

    @Test
    void aNewSessionPolicyUpdatesTheMappingInPlaceAndARollbackPutsTheOldOneBack() {
        EmrStudioSessionMapping live = new EmrStudioSessionMapping();
        live.setSessionPolicyArn(POLICY);
        when(studios.getSessionMapping(STUDIO_ID, new Identity("USER", null, "ana"))).thenReturn(live);
        StackResource r = resource("AWS::EMR::StudioSessionMapping", MAPPING_ID);
        String newer = POLICY + "V2";

        provisioner.provision(r, mappingProps("ana", newer), ctx(MAPPING_ID, mappingProps("ana", POLICY)));
        provisioner.rollbackUpdate(r);

        verify(studios).updateSessionMapping(STUDIO_ID, new Identity("USER", null, "ana"), newer);
        verify(studios).updateSessionMapping(STUDIO_ID, new Identity("USER", null, "ana"), POLICY);
        assertEquals(MAPPING_ID, r.getPhysicalId());
    }

    @Test
    void anotherIdentityReplacesTheMapping() {
        StackResource r = resource("AWS::EMR::StudioSessionMapping", MAPPING_ID);

        provisioner.provision(r, mappingProps("bea", POLICY), ctx(MAPPING_ID, mappingProps("ana", POLICY)));

        verify(studios).createSessionMapping(STUDIO_ID, new Identity("USER", null, "bea"), POLICY);
        assertEquals(STUDIO_ID + "|USER|bea", r.getPhysicalId());
        assertEquals(MAPPING_ID, provisioner.updateCleanupPhysicalId(r));
    }

    @Test
    void theDeletesTakeAStudioOrMappingThatIsGoneAsDeleted() {
        EmrStudio live = studio("notebooks", Map.of());
        EmrStudioSessionMapping mapping = new EmrStudioSessionMapping();
        mapping.setIdentityType("USER");
        mapping.setIdentityName("ana");
        live.setSessionMappings(List.of(mapping));
        when(studios.studioArn(STUDIO_ID, "us-east-1")).thenReturn(Optional.of(STUDIO_ARN));
        when(studios.studioArn("es-GONE", "us-east-1")).thenReturn(Optional.empty());
        when(studios.describeStudio(STUDIO_ID)).thenReturn(live);

        provisioner.delete("AWS::EMR::Studio", STUDIO_ID, "us-east-1");
        provisioner.delete("AWS::EMR::Studio", "es-GONE", "us-east-1");
        provisioner.delete("AWS::EMR::StudioSessionMapping", MAPPING_ID, "us-east-1");
        provisioner.delete("AWS::EMR::StudioSessionMapping", STUDIO_ID + "|USER|bea", "us-east-1");

        verify(studios).deleteStudio(STUDIO_ID);
        verify(studios, never()).deleteStudio("es-GONE");
        verify(studios).deleteSessionMapping(STUDIO_ID, new Identity("USER", null, "ana"));
        verify(studios, never()).deleteSessionMapping(STUDIO_ID, new Identity("USER", null, "bea"));
    }

    @Test
    void aDescriptionTheUpdateAddedIsPutBackEmpty() {
        when(studios.describeStudio(STUDIO_ID)).thenReturn(studio("notebooks", Map.of("stage", "v1")));
        StackResource r = provisionedStudio();
        ObjectNode props = studioProps("notebooks", "v1");
        props.put("Description", "for the ETL team");

        provisioner.provision(r, props, ctx(STUDIO_ID, studioProps("notebooks", "v1")));
        provisioner.rollbackUpdate(r);

        verify(studios).updateStudio(STUDIO_ID, null, "for the ETL team", null, null, null);
        verify(studios).updateStudio(STUDIO_ID, null, "", null, null, null);
    }

    private static JsonNode mappingProps(String identityName, String policy) {
        return json("""
                {"StudioId": "%s", "IdentityType": "USER", "IdentityName": "%s", "SessionPolicyArn": "%s"}
                """.formatted(STUDIO_ID, identityName, policy));
    }
}
