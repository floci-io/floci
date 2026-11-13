package io.floci.aws.services.ssoadmin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.ssoadmin.model.PermissionSet;
import io.floci.aws.services.ssoadmin.model.PermissionSetProvisioning;
import io.floci.aws.services.ssoadmin.model.SsoInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class SsoPermissionSetRoleProvisionerTest {

    private static final String ACCOUNT = "123456789012";
    private static final String STATUS = "LATEST_PERMISSION_SET_PROVISIONED";
    private static final String PATH = "/aws-reserved/sso.amazonaws.com/";
    private static final String NAME = "AWSReservedSSO_ReadOnly_0123456789abcdef";
    private static final String ARN = "arn:aws:iam::" + ACCOUNT + ":role" + PATH + NAME;

    private final IamService iam = mock(IamService.class);
    private final SsoPermissionSetRoleProvisioner provisioner = new SsoPermissionSetRoleProvisioner(iam);

    @ParameterizedTest
    @CsvSource({"us-east-1, aws, /aws-reserved/sso.amazonaws.com/",
            "eu-west-1, aws, /aws-reserved/sso.amazonaws.com/eu-west-1/",
            "cn-north-1, aws-cn, /aws-reserved/sso.amazonaws.com/cn-north-1/"})
    void provisionUsesTheInstanceRegionPathAndRetainsTheIamPartition(String region, String partition, String path) {
        stubCreate(partition);

        IamRole role = provisioner.provision(permissionSet(), instance(region), ACCOUNT, null);

        assertEquals(path, role.getPath());
        assertEquals("arn:" + partition + ":iam::" + ACCOUNT + ":role" + path + role.getRoleName(), role.getArn());
        PermissionSetProvisioning mapping = new PermissionSetProvisioning("permission-set", ACCOUNT, STATUS,
                role.getArn(), role.getRoleId());
        when(iam.findRole(ACCOUNT, role.getRoleName())).thenReturn(Optional.of(role));
        assertSame(role, provisioner.forPortal(mapping, region));
    }

    @Test
    void legacyMappingRequiresExplicitReprovisionBeforePortalCredentials() {
        PermissionSetProvisioning legacy = new PermissionSetProvisioning("permission-set", ACCOUNT, STATUS);
        AwsException missing = assertThrows(AwsException.class, () -> provisioner.forPortal(legacy, "us-east-1"));
        assertEquals("ResourceNotFoundException", missing.getErrorCode());
        verifyNoInteractions(iam);
        stubCreate("aws");

        IamRole role = provisioner.provision(permissionSet(), instance("us-east-1"), ACCOUNT, legacy);
        PermissionSetProvisioning migrated = new PermissionSetProvisioning("permission-set", ACCOUNT, STATUS,
                role.getArn(), role.getRoleId());
        when(iam.findRole(ACCOUNT, role.getRoleName())).thenReturn(Optional.of(role));

        assertSame(role, provisioner.forPortal(migrated, "us-east-1"));
    }

    @Test
    void aRecreatedRoleWithTheSameArnCannotBeUsedModifiedOrDeletedThroughTheOldMapping() {
        PermissionSetProvisioning original = new PermissionSetProvisioning("permission-set", ACCOUNT, STATUS,
                ARN, "original-role-id");
        IamRole replacement = new IamRole("replacement-role-id", NAME, PATH, ARN, "{}");
        when(iam.findRole(ACCOUNT, NAME)).thenReturn(Optional.of(replacement));

        assertEquals("ConflictException", assertThrows(AwsException.class,
                () -> provisioner.provision(permissionSet(), instance("us-east-1"), ACCOUNT, original)).getErrorCode());
        assertEquals("ConflictException", assertThrows(AwsException.class,
                () -> provisioner.forPortal(original, "us-east-1")).getErrorCode());
        assertDoesNotThrow(() -> provisioner.remove(original, "us-east-1"));
        verify(iam, times(3)).findRole(ACCOUNT, NAME);
        verifyNoMoreInteractions(iam);
    }

    @Test
    void cleanupFailureDoesNotReplaceTheOriginalProvisioningFailure() {
        stubCreate("aws");
        AwsException policyFailure = new AwsException("MalformedPolicyDocument", "Invalid policy", 400);
        AwsException cleanupFailure = new AwsException("DeleteConflict", "Cannot remove role", 409);
        doThrow(policyFailure).when(iam).replaceRolePolicies(anyString(), anyString(), any(), any(), isNull(), anyInt());
        doThrow(cleanupFailure).when(iam).deleteRole(anyString(), anyString());

        AwsException thrown = assertThrows(AwsException.class,
                () -> provisioner.provision(permissionSet(), instance("us-east-1"), ACCOUNT, null));

        assertSame(policyFailure, thrown);
        assertEquals(List.of(cleanupFailure), List.of(thrown.getSuppressed()));
    }

    @Test
    void provisioningJsonPreservesRoleIdentityAndAcceptsLegacyRecords() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        PermissionSetProvisioning original = new PermissionSetProvisioning("permission-set", ACCOUNT, STATUS,
                ARN, "original-role-id");

        assertEquals(original, mapper.readValue(mapper.writeValueAsString(original), PermissionSetProvisioning.class));
        PermissionSetProvisioning legacy = mapper.readValue("""
                {"permissionSetArn":"permission-set","accountId":"123456789012",
                "status":"LATEST_PERMISSION_SET_PROVISIONED"}
                """, PermissionSetProvisioning.class);
        assertEquals("permission-set", legacy.permissionSetArn());
        assertEquals(ACCOUNT, legacy.accountId());
        assertEquals(STATUS, legacy.status());
        assertNull(legacy.roleArn());
        assertNull(legacy.roleId());
    }

    private void stubCreate(String partition) {
        when(iam.createRole(anyString(), anyString(), anyString(), isNull(), eq(0), isNull()))
                .thenAnswer(invocation -> {
                    String name = invocation.getArgument(0);
                    String path = invocation.getArgument(1);
                    return new IamRole("created-role-id", name, path,
                            "arn:" + partition + ":iam::" + ACCOUNT + ":role" + path + name, "{}");
                });
    }

    private PermissionSet permissionSet() {
        return new PermissionSet("permission-set", "ReadOnly", null, "PT1H", Map.of(), Map.of(),
                null, null, Map.of(), 0L);
    }

    private SsoInstance instance(String region) {
        String instanceArn = "arn:" + AwsRegions.partitionFor(region) + ":sso:::instance/ssoins-7223b02a5d9f7c8e";
        return new SsoInstance(instanceArn, "identity-store", "Instance", ACCOUNT, region, 0L,
                "ACTIVE", null, false, Map.of());
    }
}
