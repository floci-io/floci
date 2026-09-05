package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ssoadmin.SsoAdminClient;
import software.amazon.awssdk.services.ssoadmin.model.PrincipalType;
import software.amazon.awssdk.services.ssoadmin.model.TargetType;
import software.amazon.awssdk.services.ssoadmin.model.ValidationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("IAM Identity Center account assignments")
class SsoAdminAccountAssignmentTest {

    @Test
    @DisplayName("creates and describes account assignments through the AWS SDK")
    void accountAssignmentLifecycleUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Uses emulator-only account and principal identifiers");

        try (SsoAdminClient sso = TestFixtures.ssoAdminClient()) {
            String instanceArn = sso.listInstances(request -> {}).instances().get(0).instanceArn();
            String permissionSetArn = sso.createPermissionSet(request -> request
                            .instanceArn(instanceArn)
                            .name("FlociPlatformAdmins"))
                    .permissionSet()
                    .permissionSetArn();

            var created = sso.createAccountAssignment(request -> request
                    .instanceArn(instanceArn)
                    .targetId("123456789012")
                    .targetType(TargetType.AWS_ACCOUNT)
                    .permissionSetArn(permissionSetArn)
                    .principalType(PrincipalType.GROUP)
                    .principalId("11111111-2222-3333-4444-555555555555"));

            assertThat(created.accountAssignmentCreationStatus()).isNotNull();
            assertThat(created.accountAssignmentCreationStatus().requestId()).isNotBlank();

            var status = sso.describeAccountAssignmentCreationStatus(request -> request
                    .instanceArn(instanceArn)
                    .accountAssignmentCreationRequestId(created.accountAssignmentCreationStatus().requestId()));
            assertThat(status.accountAssignmentCreationStatus().statusAsString()).isEqualTo("SUCCEEDED");

            var assignments = sso.listAccountAssignments(request -> request
                    .instanceArn(instanceArn)
                    .accountId("123456789012")
                    .permissionSetArn(permissionSetArn));
            assertThat(assignments.accountAssignments())
                    .anySatisfy(assignment -> {
                        assertThat(assignment.principalType()).isEqualTo(PrincipalType.GROUP);
                        assertThat(assignment.principalId()).isEqualTo("11111111-2222-3333-4444-555555555555");
                    });

            for (String policy : new String[] {"ReadOnlyAccess", "SecurityAudit", "ViewOnlyAccess"}) {
                sso.attachManagedPolicyToPermissionSet(request -> request
                        .instanceArn(instanceArn)
                        .permissionSetArn(permissionSetArn)
                        .managedPolicyArn("arn:aws:iam::aws:policy/" + policy));
            }
            var firstPolicies = sso.listManagedPoliciesInPermissionSet(request -> request
                    .instanceArn(instanceArn)
                    .permissionSetArn(permissionSetArn)
                    .maxResults(1));
            assertThat(firstPolicies.attachedManagedPolicies()).hasSize(1);
            assertThat(firstPolicies.nextToken()).isNotBlank();
            var secondPolicies = sso.listManagedPoliciesInPermissionSet(request -> request
                    .instanceArn(instanceArn)
                    .permissionSetArn(permissionSetArn)
                    .maxResults(1)
                    .nextToken(firstPolicies.nextToken()));
            assertThat(secondPolicies.attachedManagedPolicies()).hasSize(1);
            assertThat(secondPolicies.attachedManagedPolicies().get(0).arn())
                    .isNotEqualTo(firstPolicies.attachedManagedPolicies().get(0).arn());

            assertThatThrownBy(() -> sso.listManagedPoliciesInPermissionSet(request -> request
                    .instanceArn(instanceArn)
                    .permissionSetArn(permissionSetArn)
                    .maxResults(101)))
                    .isInstanceOf(ValidationException.class);
        }
    }
}
