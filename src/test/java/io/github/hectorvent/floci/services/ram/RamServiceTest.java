package io.github.hectorvent.floci.services.ram;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ram.model.ResourceShare;
import io.github.hectorvent.floci.services.ram.model.SharedResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Resource-share semantics behind LZA's TGW share flow: the owning account creates a share
 * (via {@code AWS::RAM::ResourceShare}), and the accepting account's Custom::GetResourceShare
 * Lambda pages GetResourceShareInvitations (empty under organization sharing) then
 * GetResourceShares(resourceOwner=OTHER-ACCOUNTS) filtering by owner + name, and finally
 * Custom::GetResourceShareItem lists the shared resource ARNs via ListResources.
 */
class RamServiceTest {

    private static final String OWNER = "111111111111";
    private static final String ACCEPTER = "222222222222";
    private static final String OU_ARN = "arn:aws:organizations::000000000000:ou/o-abc123/ou-root-infra";
    private static final String TGW_ARN = "arn:aws:ec2:us-east-1:111111111111:transit-gateway/tgw-0abc";

    private RamService service;

    @BeforeEach
    void setUp() {
        service = new RamService(new SharedStorageFactory());
        service.initializeStorage();
    }

    @Test
    void deletedShareStopsExposingItsResourcesAndPrincipals() {
        // Real RAM keeps a deleted share visible (status DELETED) for a retention
        // window, but its resources and principals stop being consumable: a
        // non-owning consumer must not discover AVAILABLE resources of a deleted
        // share through ListResources or ListPrincipals.
        ResourceShare share = service.createResourceShare(
                "delete-me", List.of(OU_ARN), List.of(TGW_ARN), false, "us-east-1", OWNER);
        service.deleteResourceShare(share.getResourceShareArn(), OWNER);

        assertTrue(service.listResources(ACCEPTER, "OTHER-ACCOUNTS", List.of()).isEmpty());
        assertTrue(service.listPrincipals(ACCEPTER, "OTHER-ACCOUNTS", List.of()).isEmpty());
        assertTrue(service.listResources(OWNER, "SELF", List.of()).isEmpty());

        // The share itself remains readable with DELETED status.
        List<ResourceShare> own = service.getResourceShares(OWNER, "SELF");
        assertEquals(1, own.size());
        assertEquals("DELETED", own.get(0).getStatus());
    }

    @Test
    void createResourceShareIsVisibleToOwnerAsSelf() {
        ResourceShare share = service.createResourceShare(
                "us-east-1-tgw-share", List.of(OU_ARN), List.of(TGW_ARN), false, "us-east-1", OWNER);

        assertNotNull(share.getResourceShareArn());
        assertTrue(share.getResourceShareArn().startsWith("arn:aws:ram:us-east-1:" + OWNER + ":resource-share/"));
        assertEquals("ACTIVE", share.getStatus());

        List<ResourceShare> own = service.getResourceShares(OWNER, "SELF");
        assertEquals(1, own.size());
        assertEquals("us-east-1-tgw-share", own.get(0).getName());
        assertEquals(OWNER, own.get(0).getOwningAccountId());
    }

    @Test
    void shareWithOuPrincipalIsVisibleToOrgAccountsAsOtherAccounts() {
        service.createResourceShare(
                "us-east-1-tgw-share", List.of(OU_ARN), List.of(TGW_ARN), false, "us-east-1", OWNER);

        List<ResourceShare> visible = service.getResourceShares(ACCEPTER, "OTHER-ACCOUNTS");
        assertEquals(1, visible.size());
        assertEquals(OWNER, visible.get(0).getOwningAccountId());
    }

    @Test
    void ownShareIsNotListedUnderOtherAccounts() {
        service.createResourceShare(
                "us-east-1-tgw-share", List.of(OU_ARN), List.of(TGW_ARN), false, "us-east-1", OWNER);

        assertTrue(service.getResourceShares(OWNER, "OTHER-ACCOUNTS").isEmpty());
        assertTrue(service.getResourceShares(ACCEPTER, "SELF").isEmpty());
    }

    @Test
    void directAccountPrincipalIsVisibleToTheSharedAccount() {
        service.createResourceShare(
                "direct-share", List.of(ACCEPTER), List.of(TGW_ARN), false, "us-east-1", OWNER);

        assertEquals(1, service.getResourceShares(ACCEPTER, "OTHER-ACCOUNTS").size());
    }

    @Test
    void accountPrincipalShareIsVisibleToAnyNonOwningCaller() {
        // LZA's Custom::GetResourceShare Lambda runs on launched-container placeholder
        // credentials, so its caller resolves to the emulator default account rather than
        // the function's account. OTHER-ACCOUNTS must therefore return every non-owned
        // share and leave the narrowing to the Lambda's own owningAccountId+name filter.
        service.createResourceShare(
                "ipam-pool-share", List.of(ACCEPTER), List.of(TGW_ARN), false, "us-east-1", OWNER);

        assertEquals(1, service.getResourceShares("000000000000", "OTHER-ACCOUNTS").size());
    }

    @Test
    void invitationsAreAlwaysEmptyUnderOrganizationSharing() {
        service.createResourceShare(
                "us-east-1-tgw-share", List.of(OU_ARN), List.of(TGW_ARN), false, "us-east-1", OWNER);

        assertTrue(service.getResourceShareInvitations(ACCEPTER).isEmpty());
    }

    @Test
    void listResourcesReturnsSharedArnsWithTypeAndShareArn() {
        ResourceShare share = service.createResourceShare(
                "us-east-1-tgw-share", List.of(OU_ARN), List.of(TGW_ARN), false, "us-east-1", OWNER);

        List<SharedResource> resources =
                service.listResources(ACCEPTER, "OTHER-ACCOUNTS", List.of(share.getResourceShareArn()));
        assertEquals(1, resources.size());
        assertEquals(TGW_ARN, resources.get(0).arn());
        assertEquals("ec2:TransitGateway", resources.get(0).type());
        assertEquals(share.getResourceShareArn(), resources.get(0).resourceShareArn());
    }

    @Test
    void listResourcesFiltersByShareArn() {
        service.createResourceShare(
                "share-a", List.of(OU_ARN), List.of(TGW_ARN), false, "us-east-1", OWNER);
        ResourceShare other = service.createResourceShare(
                "share-b", List.of(OU_ARN),
                List.of("arn:aws:ec2:us-east-1:111111111111:subnet/subnet-1"), false, "us-east-1", OWNER);

        List<SharedResource> resources =
                service.listResources(ACCEPTER, "OTHER-ACCOUNTS", List.of(other.getResourceShareArn()));
        assertEquals(1, resources.size());
        assertEquals("ec2:Subnet", resources.get(0).type());
    }

    @Test
    void nonOwnerCannotMutateAnotherAccountsShare() {
        // A share is visible to every other account under OTHER-ACCOUNTS, but visibility is not
        // a licence to mutate: AWS resolves a share ARN within the caller's account, so to a
        // non-owner the share does not exist as a mutable target.
        ResourceShare share = service.createResourceShare(
                "us-east-1-tgw-share", List.of(OU_ARN), List.of(TGW_ARN), false, "us-east-1", OWNER);
        String arn = share.getResourceShareArn();

        assertUnknownResource(() -> service.deleteResourceShare(arn, ACCEPTER));
        assertUnknownResource(() -> service.updateResourceShare(arn, "hijacked", null, ACCEPTER));
        assertUnknownResource(() -> service.associateResourceShare(arn, List.of(TGW_ARN), List.of(), ACCEPTER));
        assertUnknownResource(() -> service.disassociateResourceShare(arn, List.of(TGW_ARN), List.of(), ACCEPTER));
        assertUnknownResource(() -> service.tagResource(arn, Map.of("Owner", "attacker"), ACCEPTER));
        assertUnknownResource(() -> service.untagResource(arn, List.of("Owner"), ACCEPTER));

        ResourceShare untouched = service.getResourceShares(OWNER, "SELF").get(0);
        assertEquals("us-east-1-tgw-share", untouched.getName());
        assertEquals("ACTIVE", untouched.getStatus());
        assertEquals(List.of(TGW_ARN), untouched.getResourceArns());
        assertTrue(untouched.getTags().isEmpty());
    }

    @Test
    void ownerCanMutateOwnShare() {
        ResourceShare share = service.createResourceShare(
                "us-east-1-tgw-share", List.of(OU_ARN), List.of(TGW_ARN), false, "us-east-1", OWNER);
        String arn = share.getResourceShareArn();

        assertEquals("renamed", service.updateResourceShare(arn, "renamed", null, OWNER).getName());
        service.tagResource(arn, Map.of("Owner", "network"), OWNER);
        assertEquals("network", service.getResourceShares(OWNER, "SELF").get(0).getTags().get("Owner"));
        assertEquals("DELETED", service.deleteResourceShare(arn, OWNER).getStatus());
    }

    /**
     * The model enumerates resourceOwner as SELF or OTHER-ACCOUNTS on GetResourceShares,
     * ListPrincipals and ListResources. Anything else used to fall through the "not SELF" branch
     * and silently return other accounts' shares — the opposite of what a caller who typo'd
     * "self" expected.
     */
    @Test
    void unmodelledResourceOwnerIsRejectedOnEveryReadPath() {
        service.createResourceShare(
                "us-east-1-tgw-share", List.of(OU_ARN), List.of(TGW_ARN), false, "us-east-1", OWNER);

        assertInvalidParameter(() -> service.getResourceShares(ACCEPTER, "self"));
        assertInvalidParameter(() -> service.listPrincipals(ACCEPTER, "EVERYTHING", List.of()));
        assertInvalidParameter(() -> service.listResources(ACCEPTER, "OTHER_ACCOUNTS", List.of()));

        assertEquals(1, service.getResourceShares(ACCEPTER, "OTHER-ACCOUNTS").size());
        assertEquals(0, service.getResourceShares(ACCEPTER, "SELF").size());
    }

    @Test
    void mutatingADeletedShareRaisesUnknownResource() {
        // DELETED is terminal: the share stays readable for the retention window,
        // but every mutation resolves it the same as an ARN that never existed.
        ResourceShare share = service.createResourceShare(
                "gone", List.of(OU_ARN), List.of(TGW_ARN), false, "us-east-1", OWNER);
        String arn = share.getResourceShareArn();
        service.deleteResourceShare(arn, OWNER);

        assertUnknownResource(() -> service.updateResourceShare(arn, "renamed", null, OWNER));
        assertUnknownResource(() -> service.associateResourceShare(arn, List.of(TGW_ARN), List.of(), OWNER));
        assertUnknownResource(() -> service.disassociateResourceShare(arn, List.of(TGW_ARN), List.of(), OWNER));
        assertUnknownResource(() -> service.tagResource(arn, Map.of("k", "v"), OWNER));
        assertUnknownResource(() -> service.untagResource(arn, List.of("k"), OWNER));
        assertUnknownResource(() -> service.deleteResourceShare(arn, OWNER));

        // Still readable with DELETED status — mutation rejection must not hide it from reads.
        assertEquals("DELETED", service.getResourceShares(OWNER, "SELF").get(0).getStatus());
    }

    @Test
    void getResourceSharesAppliesTheNameFilter() {
        service.createResourceShare("tgw-share", List.of(OU_ARN), List.of(TGW_ARN), false, "us-east-1", OWNER);
        service.createResourceShare("ipam-share", List.of(OU_ARN), List.of(TGW_ARN), false, "us-east-1", OWNER);

        List<ResourceShare> matched =
                service.getResourceShares(OWNER, "SELF", "ipam-share", List.of(), null);
        assertEquals(1, matched.size());
        assertEquals("ipam-share", matched.get(0).getName());

        assertTrue(service.getResourceShares(OWNER, "SELF", "no-such-share", List.of(), null).isEmpty());
    }

    @Test
    void getResourceSharesAppliesTheResourceShareArnsFilter() {
        ResourceShare wanted = service.createResourceShare(
                "tgw-share", List.of(OU_ARN), List.of(TGW_ARN), false, "us-east-1", OWNER);
        service.createResourceShare("ipam-share", List.of(OU_ARN), List.of(TGW_ARN), false, "us-east-1", OWNER);

        List<ResourceShare> matched = service.getResourceShares(
                OWNER, "SELF", null, List.of(wanted.getResourceShareArn()), null);
        assertEquals(1, matched.size());
        assertEquals(wanted.getResourceShareArn(), matched.get(0).getResourceShareArn());
    }

    @Test
    void getResourceSharesAppliesTheStatusFilter() {
        // LZA polls for ACTIVE shares; a share left in the DELETED retention window must not
        // come back from that poll, but must still be findable by an explicit DELETED filter.
        service.createResourceShare("live", List.of(OU_ARN), List.of(TGW_ARN), false, "us-east-1", OWNER);
        ResourceShare doomed = service.createResourceShare(
                "gone", List.of(OU_ARN), List.of(TGW_ARN), false, "us-east-1", OWNER);
        service.deleteResourceShare(doomed.getResourceShareArn(), OWNER);

        List<ResourceShare> active = service.getResourceShares(OWNER, "SELF", null, List.of(), "ACTIVE");
        assertEquals(1, active.size());
        assertEquals("live", active.get(0).getName());

        List<ResourceShare> deleted = service.getResourceShares(OWNER, "SELF", null, List.of(), "DELETED");
        assertEquals(1, deleted.size());
        assertEquals("gone", deleted.get(0).getName());

        // No status filter still returns both, as it does today.
        assertEquals(2, service.getResourceShares(OWNER, "SELF").size());
    }

    @Test
    void unmodelledResourceShareStatusIsRejected() {
        assertInvalidParameter(() -> service.getResourceShares(OWNER, "SELF", null, List.of(), "active"));
        assertInvalidParameter(() -> service.getResourceShares(OWNER, "SELF", null, List.of(), "GONE"));
    }

    @Test
    void lastUpdatedTimeAdvancesOnMutationButCreationTimeDoesNot() {
        ResourceShare created = service.createResourceShare(
                "tgw-share", List.of(OU_ARN), List.of(TGW_ARN), false, "us-east-1", OWNER);
        String arn = created.getResourceShareArn();
        assertFalse(created.getLastUpdatedTime().isBefore(created.getCreationTime()));

        ResourceShare renamed = service.updateResourceShare(arn, "renamed", null, OWNER);
        assertTrue(renamed.getLastUpdatedTime().isAfter(created.getLastUpdatedTime()));
        assertEquals(created.getCreationTime(), renamed.getCreationTime());

        service.tagResource(arn, Map.of("Owner", "network"), OWNER);
        ResourceShare tagged = service.getResourceShares(OWNER, "SELF").get(0);
        assertTrue(tagged.getLastUpdatedTime().isAfter(renamed.getLastUpdatedTime()));
        assertEquals(created.getCreationTime(), tagged.getCreationTime());
    }

    private static void assertInvalidParameter(Executable read) {
        AwsException error = assertThrows(AwsException.class, read);
        assertEquals("InvalidParameterException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    private static void assertUnknownResource(Executable mutation) {
        AwsException error = assertThrows(AwsException.class, mutation);
        assertEquals("UnknownResourceException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        // Same message an unknown ARN gets — a non-owner must not learn the share exists.
        assertTrue(error.getMessage().endsWith(" does not exist."));
    }

    private static final class SharedStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private SharedStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName,
                                                    String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(
                    fileName, ignored -> AccountAwareStorageBackend.inMemory("000000000000"));
        }
    }
}
