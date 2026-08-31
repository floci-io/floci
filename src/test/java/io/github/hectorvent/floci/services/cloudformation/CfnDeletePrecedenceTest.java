package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.services.cloudformation.provisioners.CloudFormationResourceRegistry;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the delete dispatch order in {@link CloudFormationResourceProvisioner}.
 *
 * <p>Deleting a resource takes one of three routes: an extracted provisioner, the attribute-aware
 * branches for {@code DELETE_NEEDS_STACK_RESOURCE}, or the generic type/physicalId switch. The
 * registry is consulted first so an exact match beats the {@code Custom::} prefix branch, which is
 * what lets {@code Custom::DynamoDBReplica} move to a provisioner at all.
 *
 * <p>The cost of registry-first is that a leftover attribute-aware branch for a migrated type
 * becomes unreachable rather than wrong. That is the safe direction, but it would let a slice quietly
 * drop delete logic that only that branch implemented, so this test makes the overlap a build
 * failure: migrating one of those types must move its logic into the provisioner's
 * {@code delete(StackResource, String)} override and drop its entry from the set.
 */
@QuarkusTest
class CfnDeletePrecedenceTest {

    @Inject
    CloudFormationResourceRegistry registry;

    @Test
    void noTypeIsServedByBothAProvisionerAndAnAttributeAwareBranch() {
        List<String> both = CloudFormationResourceProvisioner.DELETE_NEEDS_STACK_RESOURCE.stream()
                .filter(type -> registry.forType(type).isPresent())
                .sorted()
                .toList();

        assertTrue(both.isEmpty(),
                "These types have an extracted provisioner, so their attribute-aware delete branch "
                        + "is now unreachable. Move that logic into the provisioner's "
                        + "delete(StackResource, String) override and remove the entry from "
                        + "DELETE_NEEDS_STACK_RESOURCE: " + both);
    }

    /**
     * Every attribute-aware type is one Floci actually provisions. A typo in the set
     * ({@code AWS::Events::Rules}) would never match, sending the real type down the
     * type/physicalId path where its delete silently no-ops and leaves the resource live.
     */
    @Test
    void everyAttributeAwareTypeIsAProvisionedType() {
        List<String> unknown = CloudFormationResourceProvisioner.DELETE_NEEDS_STACK_RESOURCE.stream()
                .filter(type -> registry.forType(type).isEmpty())
                .filter(type -> !CloudFormationResourceProvisioner.LEGACY_SWITCH_TYPES.contains(type))
                .sorted()
                .toList();

        assertTrue(unknown.isEmpty(),
                "DELETE_NEEDS_STACK_RESOURCE names types that nothing provisions, so the entry is "
                        + "dead and the real type (if misspelled here) deletes by physical id alone: "
                        + unknown);
    }
}
