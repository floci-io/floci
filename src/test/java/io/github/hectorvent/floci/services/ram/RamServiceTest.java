package io.github.hectorvent.floci.services.ram;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import io.github.hectorvent.floci.services.organizations.model.Organization;
import io.github.hectorvent.floci.services.ram.model.ResourceShare;
import io.github.hectorvent.floci.services.ram.model.ResourceShareAssociation;
import io.github.hectorvent.floci.services.ram.model.ResourceShareInvitation;
import io.github.hectorvent.floci.services.ram.model.SharedResource;
import io.github.hectorvent.floci.testing.ThreadStates;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        OrganizationsService organizations = mock(OrganizationsService.class);
        Organization organization = new Organization();
        organization.setId("o-abc123");
        when(organizations.describeOrganization(anyString())).thenReturn(organization);
        when(organizations.organizationPath(anyString(), anyString()))
                .thenReturn("o-abc123/r-root/ou-root-infra/222222222222/");
        service = new RamService(new SharedStorageFactory(), organizations);
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
        service.deleteResourceShare(share.getResourceShareArn(), OWNER, "us-east-1");

        assertTrue(service.listResources(ACCEPTER, "OTHER-ACCOUNTS", List.of(), "us-east-1").isEmpty());
        assertTrue(service.listPrincipals(ACCEPTER, "OTHER-ACCOUNTS", List.of(), "us-east-1").isEmpty());
        assertTrue(service.listResources(OWNER, "SELF", List.of(), "us-east-1").isEmpty());
        assertTrue(associations(OWNER, "us-east-1", "RESOURCE", null,
                List.of(share.getResourceShareArn()), null, null, null, null).items().isEmpty());

        // The share itself remains readable with DELETED status.
        List<ResourceShare> own = service.getResourceShares(OWNER, "SELF", "us-east-1");
        assertEquals(1, own.size());
        assertEquals("DELETED", own.get(0).getStatus());
    }

    @Test
    void getResourceShareAssociationsFiltersOwnedResourcesAndPrincipals() {
        ResourceShare share = service.createResourceShare("associations", List.of(OU_ARN),
                List.of(TGW_ARN), false, "us-east-1", OWNER);
        service.createResourceShare("other-region", List.of(OU_ARN), List.of(TGW_ARN),
                false, "eu-west-1", OWNER);
        service.createResourceShare("other-owner", List.of(OWNER), List.of(TGW_ARN),
                false, "us-east-1", ACCEPTER);

        PaginatedResult<ResourceShareAssociation> resources = associations(
                OWNER, "us-east-1", "RESOURCE", null, List.of(), TGW_ARN, null, null, null);
        assertEquals(1, resources.items().size());
        assertEquals(share.getResourceShareArn(), resources.items().getFirst().resourceShareArn());
        assertEquals(TGW_ARN, resources.items().getFirst().associatedEntity());
        assertEquals(share.getCreationTime(), resources.items().getFirst().creationTime());
        assertNull(resources.nextToken());

        assertEquals(1, associations(OWNER, "us-east-1", "PRINCIPAL", null,
                List.of(share.getResourceShareArn()), null, OU_ARN, null, null).items().size());
        assertTrue(associations(ACCEPTER, "us-east-1", "RESOURCE", null,
                List.of(share.getResourceShareArn()), TGW_ARN, null, null, null).items().isEmpty());
        assertTrue(associations(OWNER, "eu-west-1", "RESOURCE", null,
                List.of(share.getResourceShareArn()), TGW_ARN, null, null, null).items().isEmpty());
        assertTrue(associations(OWNER, "us-east-1", "RESOURCE", "DISASSOCIATED",
                List.of(), null, null, null, null).items().isEmpty());
        assertTrue(associations(OWNER, "us-east-1", "SOURCE", null,
                List.of(), null, null, null, null).items().isEmpty());

        service.disassociateResourceShare(share.getResourceShareArn(), List.of(TGW_ARN), List.of(),
                OWNER, "us-east-1");
        assertTrue(associations(OWNER, "us-east-1", "RESOURCE", null,
                List.of(share.getResourceShareArn()), null, null, null, null).items().isEmpty());
    }

    @Test
    void getResourceShareAssociationsPagesWithoutLeakingFiltersAcrossTokens() {
        ResourceShare share = service.createResourceShare("many-associations", List.of(OU_ARN),
                List.of(TGW_ARN, TGW_ARN + "2"), false, "us-east-1", OWNER);
        PaginatedResult<ResourceShareAssociation> first = associations(OWNER, "us-east-1", "RESOURCE",
                null, List.of(share.getResourceShareArn()), null, null, 1, null);
        assertEquals(1, first.items().size());
        assertNotNull(first.nextToken());
        PaginatedResult<ResourceShareAssociation> second = associations(OWNER, "us-east-1", "RESOURCE",
                null, List.of(share.getResourceShareArn()), null, null, 1, first.nextToken());
        assertEquals(1, second.items().size());
        assertNotEquals(first.items().getFirst().associatedEntity(), second.items().getFirst().associatedEntity());
        assertNull(second.nextToken());

        AwsException mismatched = assertThrows(AwsException.class, () -> associations(
                OWNER, "us-east-1", "PRINCIPAL", null, List.of(share.getResourceShareArn()),
                null, null, 1, first.nextToken()));
        assertEquals("InvalidNextTokenException", mismatched.getErrorCode());
        AwsException malformed = assertThrows(AwsException.class, () -> associations(
                OWNER, "us-east-1", "RESOURCE", null, List.of(share.getResourceShareArn()),
                null, null, 1, "not-a-token"));
        assertEquals("InvalidNextTokenException", malformed.getErrorCode());
    }

    @Test
    void getResourceSharesPageDoesNotConfuseMissingNameWithLiteralNull() {
        ResourceShare first = service.createResourceShare("first", List.of(), List.of(),
                false, "us-east-1", OWNER);
        ResourceShare second = service.createResourceShare("second", List.of(), List.of(),
                false, "us-east-1", OWNER);
        List<String> arns = List.of(first.getResourceShareArn(), second.getResourceShareArn());
        PaginatedResult<ResourceShare> page = service.getResourceSharesPage(
                OWNER, "SELF", null, arns, null, "us-east-1", 1, null);
        assertNotNull(page.nextToken());

        AwsException invalid = assertThrows(AwsException.class, () -> service.getResourceSharesPage(
                OWNER, "SELF", "null", arns, null, "us-east-1", 1, page.nextToken()));
        assertEquals("InvalidNextTokenException", invalid.getErrorCode());

        AwsException wrongOperation = assertThrows(AwsException.class, () -> associations(
                OWNER, "us-east-1", "RESOURCE", null, List.of(first.getResourceShareArn()),
                null, null, 1, page.nextToken()));
        assertEquals("InvalidNextTokenException", wrongOperation.getErrorCode());
    }

    @Test
    void getResourceShareAssociationsRejectsInvalidInput() {
        assertInvalidParameter(() -> associations(OWNER, "us-east-1", null, null,
                List.of(), null, null, null, null));
        assertInvalidParameter(() -> associations(OWNER, "us-east-1", "RESOURCE", null,
                List.of(), null, OWNER, null, null));
        assertInvalidParameter(() -> associations(OWNER, "us-east-1", "PRINCIPAL", null,
                List.of(), TGW_ARN, null, null, null));
        assertInvalidParameter(() -> associations(OWNER, "us-east-1", "RESOURCE", null,
                List.of(), null, null, 0, null));
        assertInvalidParameter(() -> associations(OWNER, "us-east-1", "RESOURCE", null,
                List.of(), null, null, 501, null));
        AwsException emptyToken = assertThrows(AwsException.class, () -> associations(
                OWNER, "us-east-1", "RESOURCE", null, List.of(), null, null, null, ""));
        assertEquals("InvalidNextTokenException", emptyToken.getErrorCode());
        AwsException malformedArn = assertThrows(AwsException.class, () -> associations(
                OWNER, "us-east-1", "RESOURCE", null, List.of("not-an-arn"),
                null, null, null, null));
        assertEquals("MalformedArnException", malformedArn.getErrorCode());
    }

    private PaginatedResult<ResourceShareAssociation> associations(String caller, String region, String type,
            String status, List<String> shareArns, String resourceArn, String principal,
            Integer maxResults, String nextToken) {
        return service.getResourceShareAssociations(caller, region, type, status, shareArns,
                resourceArn, principal, maxResults, nextToken);
    }

    @Test
    void createResourceShareIsVisibleToOwnerAsSelf() {
        ResourceShare share = service.createResourceShare(
                "us-east-1-tgw-share", List.of(OU_ARN), List.of(TGW_ARN), false, "us-east-1", OWNER);

        assertNotNull(share.getResourceShareArn());
        assertTrue(share.getResourceShareArn().startsWith("arn:aws:ram:us-east-1:" + OWNER + ":resource-share/"));
        assertEquals("ACTIVE", share.getStatus());

        List<ResourceShare> own = service.getResourceShares(OWNER, "SELF", "us-east-1");
        assertEquals(1, own.size());
        assertEquals("us-east-1-tgw-share", own.get(0).getName());
        assertEquals(OWNER, own.get(0).getOwningAccountId());
    }

    @Test
    void shareWithOuPrincipalIsVisibleToOrgAccountsAsOtherAccounts() {
        service.createResourceShare(
                "us-east-1-tgw-share", List.of(OU_ARN), List.of(TGW_ARN), false, "us-east-1", OWNER);

        List<ResourceShare> visible = service.getResourceShares(ACCEPTER, "OTHER-ACCOUNTS", "us-east-1");
        assertEquals(1, visible.size());
        assertEquals(OWNER, visible.get(0).getOwningAccountId());
    }

    @Test
    void ownShareIsNotListedUnderOtherAccounts() {
        service.createResourceShare(
                "us-east-1-tgw-share", List.of(OU_ARN), List.of(TGW_ARN), false, "us-east-1", OWNER);

        assertTrue(service.getResourceShares(OWNER, "OTHER-ACCOUNTS", "us-east-1").isEmpty());
        assertTrue(service.getResourceShares(ACCEPTER, "SELF", "us-east-1").isEmpty());
    }

    @Test
    void chinaOuPrincipalIsVisibleToOrgAccounts() {
        service.createResourceShare("cn-tgw-share",
                List.of("arn:aws-cn:organizations::000000000000:ou/o-abc123/ou-root-infra"),
                List.of("arn:aws-cn:ec2:cn-north-1:111111111111:transit-gateway/tgw-0abc"),
                false, "cn-north-1", OWNER);

        assertEquals(1, service.getResourceShares(ACCEPTER, "OTHER-ACCOUNTS", "cn-north-1").size());
        // A China share never reaches a commercial request, even for a member of the organization.
        assertTrue(service.getResourceShares(ACCEPTER, "OTHER-ACCOUNTS", "us-east-1").isEmpty());
    }

    /**
     * Mutations resolve a share in the request's region like the listings do: a share that
     * GetResourceShares in us-east-1 does not show cannot be deleted, changed or retagged from
     * there, and an invitation to it cannot be answered there.
     */
    @Test
    void mutationsInAnotherRegionDoNotReachTheShare() {
        ResourceShare share = service.createResourceShare(
                "eu-share", List.of(ACCEPTER), List.of(TGW_ARN), false, "eu-west-1", OWNER);
        String arn = share.getResourceShareArn();
        String invitationArn = service.getResourceShareInvitations(ACCEPTER, List.of(), List.of(), "eu-west-1")
                .get(0).resourceShareInvitationArn();

        for (Runnable wrongRegion : List.<Runnable>of(
                () -> service.deleteResourceShare(arn, OWNER, "us-east-1"),
                () -> service.updateResourceShare(arn, "renamed", null, OWNER, "us-east-1"),
                () -> service.associateResourceShare(arn, List.of(), List.of("333333333333"), OWNER, "us-east-1"),
                () -> service.disassociateResourceShare(arn, List.of(TGW_ARN), List.of(), OWNER, "us-east-1"),
                () -> service.tagResource(arn, Map.of("env", "test"), OWNER, "us-east-1"),
                () -> service.untagResource(arn, List.of("env"), OWNER, "us-east-1"))) {
            AwsException ex = assertThrows(AwsException.class, wrongRegion::run);
            assertEquals("UnknownResourceException", ex.getErrorCode());
        }
        for (Runnable wrongRegion : List.<Runnable>of(
                () -> service.acceptResourceShareInvitation(invitationArn, ACCEPTER, "us-east-1"),
                () -> service.rejectResourceShareInvitation(invitationArn, ACCEPTER, "us-east-1"))) {
            AwsException ex = assertThrows(AwsException.class, wrongRegion::run);
            assertEquals("ResourceShareInvitationArnNotFoundException", ex.getErrorCode());
        }

        ResourceShare unchanged = service.getResourceShares(OWNER, "SELF", "eu-west-1").get(0);
        assertEquals("eu-share", unchanged.getName());
        assertEquals("ACTIVE", unchanged.getStatus());
        assertEquals(List.of(TGW_ARN), unchanged.getResourceArns());
        assertEquals("ACCEPTED", service.acceptResourceShareInvitation(invitationArn, ACCEPTER, "eu-west-1").status());
    }

    /** A resource share is regional: the listing operations answer for the request's region only. */
    @Test
    void listingsAnswerForTheRequestsRegionOnly() {
        ResourceShare share = service.createResourceShare(
                "eu-share", List.of(ACCEPTER, OU_ARN), List.of(TGW_ARN), false, "eu-west-1", OWNER);

        assertEquals(1, service.getResourceShares(OWNER, "SELF", "eu-west-1").size());
        assertTrue(service.getResourceShares(OWNER, "SELF", "us-east-1").isEmpty());
        assertTrue(service.getResourceShares(ACCEPTER, "OTHER-ACCOUNTS", "us-east-1").isEmpty());
        assertTrue(service.listResources(ACCEPTER, "OTHER-ACCOUNTS", List.of(), "us-east-1").isEmpty());
        assertTrue(service.listPrincipals(OWNER, "SELF", List.of(), "us-east-1").isEmpty());
        assertEquals(List.of(share.getResourceShareArn()),
                service.getResourceShareInvitations(ACCEPTER, List.of(), List.of(), "eu-west-1").stream()
                        .map(ResourceShareInvitation::resourceShareArn).toList());
        assertTrue(service.getResourceShareInvitations(ACCEPTER, List.of(), List.of(), "us-east-1").isEmpty());
    }

    @Test
    void directAccountPrincipalIsVisibleToTheSharedAccount() {
        service.createResourceShare(
                "direct-share", List.of(ACCEPTER), List.of(TGW_ARN), false, "us-east-1", OWNER);

        assertEquals(1, service.getResourceShares(ACCEPTER, "OTHER-ACCOUNTS", "us-east-1").size());
    }

    @Test
    void unrelatedCallerCannotSeeAnAccountPrincipalShare() {
        service.createResourceShare(
                "ipam-pool-share", List.of(ACCEPTER), List.of(TGW_ARN), false, "us-east-1", OWNER);

        assertTrue(service.getResourceShares("000000000000", "OTHER-ACCOUNTS", "us-east-1").isEmpty());
        assertTrue(service.listResources("000000000000", "OTHER-ACCOUNTS", List.of(), "us-east-1").isEmpty());
        assertTrue(service.listPrincipals("000000000000", "OTHER-ACCOUNTS", List.of(), "us-east-1").isEmpty());
    }

    @Test
    void rejectedInvitationNoLongerGrantsShareVisibility() {
        service.createResourceShare(
                "direct-share", List.of(ACCEPTER), List.of(TGW_ARN), false, "us-east-1", OWNER);
        String invitationArn = service.getResourceShareInvitations(ACCEPTER, List.of(), List.of(), "us-east-1")
                .getFirst().resourceShareInvitationArn();

        service.rejectResourceShareInvitation(invitationArn, ACCEPTER, "us-east-1");

        assertTrue(service.getResourceShares(ACCEPTER, "OTHER-ACCOUNTS", "us-east-1").isEmpty());
        assertTrue(service.listResources(ACCEPTER, "OTHER-ACCOUNTS", List.of(), "us-east-1").isEmpty());
        assertTrue(service.listPrincipals(ACCEPTER, "OTHER-ACCOUNTS", List.of(), "us-east-1").isEmpty());
    }

    @Test
    void invitationsAreAlwaysEmptyUnderOrganizationSharing() {
        service.createResourceShare(
                "us-east-1-tgw-share", List.of(OU_ARN), List.of(TGW_ARN), false, "us-east-1", OWNER);

        assertTrue(service.getResourceShareInvitations(ACCEPTER, List.of(), List.of(), "us-east-1").isEmpty());
    }

    @Test
    void listResourcesReturnsSharedArnsWithTypeAndShareArn() {
        ResourceShare share = service.createResourceShare(
                "us-east-1-tgw-share", List.of(OU_ARN), List.of(TGW_ARN), false, "us-east-1", OWNER);

        List<SharedResource> resources =
                service.listResources(ACCEPTER, "OTHER-ACCOUNTS", List.of(share.getResourceShareArn()), "us-east-1");
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
                service.listResources(ACCEPTER, "OTHER-ACCOUNTS", List.of(other.getResourceShareArn()), "us-east-1");
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

        assertUnknownResource(() -> service.deleteResourceShare(arn, ACCEPTER, "us-east-1"));
        assertUnknownResource(() -> service.updateResourceShare(arn, "hijacked", null, ACCEPTER, "us-east-1"));
        assertUnknownResource(() -> service.associateResourceShare(arn, List.of(TGW_ARN), List.of(), ACCEPTER,
                "us-east-1"));
        assertUnknownResource(() -> service.disassociateResourceShare(arn, List.of(TGW_ARN), List.of(), ACCEPTER,
                "us-east-1"));
        assertUnknownResource(() -> service.tagResource(arn, Map.of("Owner", "attacker"), ACCEPTER, "us-east-1"));
        assertUnknownResource(() -> service.untagResource(arn, List.of("Owner"), ACCEPTER, "us-east-1"));

        ResourceShare untouched = service.getResourceShares(OWNER, "SELF", "us-east-1").get(0);
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

        assertEquals("renamed", service.updateResourceShare(arn, "renamed", null, OWNER, "us-east-1").getName());
        service.tagResource(arn, Map.of("Owner", "network"), OWNER, "us-east-1");
        assertEquals("network", service.getResourceShares(OWNER, "SELF", "us-east-1").get(0).getTags().get("Owner"));
        assertEquals("DELETED", service.deleteResourceShare(arn, OWNER, "us-east-1").getStatus());
    }

    /**
     * The model enumerates resourceOwner as SELF or OTHER-ACCOUNTS on GetResourceShares,
     * ListPrincipals and ListResources. Anything else used to fall through the "not SELF" branch
     * and silently return other accounts' shares, the opposite of what a caller who typo'd
     * "self" expected.
     */
    @Test
    void unmodelledResourceOwnerIsRejectedOnEveryReadPath() {
        service.createResourceShare(
                "us-east-1-tgw-share", List.of(OU_ARN), List.of(TGW_ARN), false, "us-east-1", OWNER);

        assertInvalidParameter(() -> service.getResourceShares(ACCEPTER, "self", "us-east-1"));
        assertInvalidParameter(() -> service.listPrincipals(ACCEPTER, "EVERYTHING", List.of(), "us-east-1"));
        assertInvalidParameter(() -> service.listResources(ACCEPTER, "OTHER_ACCOUNTS", List.of(), "us-east-1"));

        assertEquals(1, service.getResourceShares(ACCEPTER, "OTHER-ACCOUNTS", "us-east-1").size());
        assertEquals(0, service.getResourceShares(ACCEPTER, "SELF", "us-east-1").size());
    }

    @Test
    void mutatingADeletedShareRaisesUnknownResource() {
        // DELETED is terminal: the share stays readable for the retention window,
        // but every mutation resolves it the same as an ARN that never existed.
        ResourceShare share = service.createResourceShare(
                "gone", List.of(OU_ARN), List.of(TGW_ARN), false, "us-east-1", OWNER);
        String arn = share.getResourceShareArn();
        service.deleteResourceShare(arn, OWNER, "us-east-1");

        assertUnknownResource(() -> service.updateResourceShare(arn, "renamed", null, OWNER, "us-east-1"));
        assertUnknownResource(() -> service.associateResourceShare(arn, List.of(TGW_ARN), List.of(), OWNER,
                "us-east-1"));
        assertUnknownResource(() -> service.disassociateResourceShare(arn, List.of(TGW_ARN), List.of(), OWNER,
                "us-east-1"));
        assertUnknownResource(() -> service.tagResource(arn, Map.of("k", "v"), OWNER, "us-east-1"));
        assertUnknownResource(() -> service.untagResource(arn, List.of("k"), OWNER, "us-east-1"));
        assertUnknownResource(() -> service.deleteResourceShare(arn, OWNER, "us-east-1"));

        // Still readable with DELETED status: mutation rejection must not hide it from reads.
        assertEquals("DELETED", service.getResourceShares(OWNER, "SELF", "us-east-1").get(0).getStatus());
    }

    @Test
    void getResourceSharesAppliesTheNameFilter() {
        service.createResourceShare("tgw-share", List.of(OU_ARN), List.of(TGW_ARN), false, "us-east-1", OWNER);
        service.createResourceShare("ipam-share", List.of(OU_ARN), List.of(TGW_ARN), false, "us-east-1", OWNER);

        List<ResourceShare> matched =
                service.getResourceShares(OWNER, "SELF", "ipam-share", List.of(), null, "us-east-1");
        assertEquals(1, matched.size());
        assertEquals("ipam-share", matched.get(0).getName());

        assertTrue(service.getResourceShares(OWNER, "SELF", "no-such-share", List.of(), null, "us-east-1").isEmpty());
    }

    @Test
    void getResourceSharesAppliesTheResourceShareArnsFilter() {
        ResourceShare wanted = service.createResourceShare(
                "tgw-share", List.of(OU_ARN), List.of(TGW_ARN), false, "us-east-1", OWNER);
        service.createResourceShare("ipam-share", List.of(OU_ARN), List.of(TGW_ARN), false, "us-east-1", OWNER);

        List<ResourceShare> matched = service.getResourceShares(
                OWNER, "SELF", null, List.of(wanted.getResourceShareArn()), null, "us-east-1");
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
        service.deleteResourceShare(doomed.getResourceShareArn(), OWNER, "us-east-1");

        List<ResourceShare> active = service.getResourceShares(OWNER, "SELF", null, List.of(), "ACTIVE", "us-east-1");
        assertEquals(1, active.size());
        assertEquals("live", active.get(0).getName());

        List<ResourceShare> deleted = service.getResourceShares(OWNER, "SELF", null, List.of(), "DELETED", "us-east-1");
        assertEquals(1, deleted.size());
        assertEquals("gone", deleted.get(0).getName());

        // No status filter still returns both, as it does today.
        assertEquals(2, service.getResourceShares(OWNER, "SELF", "us-east-1").size());
    }

    @Test
    void unmodelledResourceShareStatusIsRejected() {
        assertInvalidParameter(() -> service.getResourceShares(OWNER, "SELF", null, List.of(), "active", "us-east-1"));
        assertInvalidParameter(() -> service.getResourceShares(OWNER, "SELF", null, List.of(), "GONE", "us-east-1"));
    }

    @Test
    void lastUpdatedTimeAdvancesOnMutationButCreationTimeDoesNot() {
        ResourceShare created = service.createResourceShare(
                "tgw-share", List.of(OU_ARN), List.of(TGW_ARN), false, "us-east-1", OWNER);
        String arn = created.getResourceShareArn();
        assertFalse(created.getLastUpdatedTime().isBefore(created.getCreationTime()));

        ResourceShare renamed = service.updateResourceShare(arn, "renamed", null, OWNER, "us-east-1");
        assertTrue(renamed.getLastUpdatedTime().isAfter(created.getLastUpdatedTime()));
        assertEquals(created.getCreationTime(), renamed.getCreationTime());

        service.tagResource(arn, Map.of("Owner", "network"), OWNER, "us-east-1");
        ResourceShare tagged = service.getResourceShares(OWNER, "SELF", "us-east-1").get(0);
        assertTrue(tagged.getLastUpdatedTime().isAfter(renamed.getLastUpdatedTime()));
        assertEquals(created.getCreationTime(), tagged.getCreationTime());
    }

    @Test
    void consecutiveWritesInOneClockTickAdvanceLastUpdatedTime() {
        Clock fixedClock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
        RamService fixedClockService = new RamService(new SharedStorageFactory(), null, fixedClock);
        fixedClockService.initializeStorage();

        ResourceShare created = fixedClockService.createResourceShare(
                "original", List.of(), List.of(), false, "us-east-1", OWNER);
        assertEquals(created.getCreationTime(), created.getLastUpdatedTime());
        String arn = created.getResourceShareArn();
        ResourceShare firstUpdate = fixedClockService.updateResourceShare(
                arn, "first", null, OWNER, "us-east-1");
        ResourceShare secondUpdate = fixedClockService.updateResourceShare(
                arn, "second", null, OWNER, "us-east-1");

        assertEquals(created.getCreationTime(), firstUpdate.getCreationTime());
        assertEquals(created.getCreationTime(), secondUpdate.getCreationTime());
        assertTrue(firstUpdate.getLastUpdatedTime().toEpochMilli()
                > created.getLastUpdatedTime().toEpochMilli());
        assertTrue(secondUpdate.getLastUpdatedTime().toEpochMilli()
                > firstUpdate.getLastUpdatedTime().toEpochMilli());
    }

    @Test
    void updateOfLegacyUnprefixedShareAdvancesLastUpdatedTime() {
        TestStorageFactory storage = new TestStorageFactory(false);
        Instant creationTime = Instant.parse("2029-01-01T00:00:00Z");
        String arn = "arn:aws:ram:us-east-1:" + OWNER + ":resource-share/legacy";
        RamService legacyService = new RamService(
                storage, null, Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC));
        legacyService.initializeStorage();
        ResourceShare legacy = new ResourceShare(
                arn, "legacy", OWNER, List.of(), List.of(), false, "ACTIVE",
                creationTime, creationTime, Map.of());
        storage.putLegacy("ram-resource-shares.json", arn, legacy);

        ResourceShare updated = legacyService.updateResourceShare(
                arn, "updated", null, OWNER, "us-east-1");

        assertEquals(creationTime, updated.getCreationTime());
        assertTrue(updated.getLastUpdatedTime().isAfter(creationTime));
    }

    @Test
    void overlappingUpdatesAdvanceLastUpdatedTimeForEachWrite() throws Exception {
        TestStorageFactory storage = new TestStorageFactory(true);
        Clock fixedClock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
        RamService concurrentService = new RamService(storage, null, fixedClock);
        concurrentService.initializeStorage();
        ResourceShare seeded = concurrentService.createResourceShare(
                "original", List.of(), List.of(), false, "us-east-1", OWNER);
        storage.armShareReads();

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ResourceShare>> results = new ArrayList<>();
            for (String name : List.of("first", "second")) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return concurrentService.updateResourceShare(
                            seeded.getResourceShareArn(), name, null, OWNER, "us-east-1");
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "every thread reached the start gate");
            start.countDown();

            Set<Instant> updateTimes = new HashSet<>();
            for (Future<ResourceShare> result : results) {
                updateTimes.add(result.get(5, TimeUnit.SECONDS).getLastUpdatedTime());
            }
            assertEquals(threadCount, updateTimes.size(), "each overlapping write gets a unique timestamp");
        } finally {
            executor.shutdownNow();
        }

        assertTrue(concurrentService.getResourceShares(OWNER, "SELF", "us-east-1").stream()
                .findFirst().orElseThrow().getLastUpdatedTime().isAfter(seeded.getLastUpdatedTime()));
    }

    @Test
    void concurrentTagAndDisassociateDoNotLoseEitherMutation() throws Exception {
        TestStorageFactory storage = new TestStorageFactory(false);
        RamService concurrentService = new RamService(storage, null);
        concurrentService.initializeStorage();
        ResourceShare share = concurrentService.createResourceShare(
                "concurrent-mutation", List.of(), List.of(TGW_ARN), false, "us-east-1", OWNER);
        storage.armShareScans();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<Thread> tagThread = new AtomicReference<>();
        try {
            Future<?> disassociate = executor.submit(() -> concurrentService.disassociateResourceShare(
                    share.getResourceShareArn(), List.of(TGW_ARN), List.of(), OWNER, "us-east-1"));
            assertTrue(storage.awaitFirstShareScan(), "the disassociation must reach its share scan");
            Future<?> tag = executor.submit(() -> {
                tagThread.set(Thread.currentThread());
                concurrentService.tagResource(share.getResourceShareArn(), Map.of("race", "preserve"),
                        OWNER, "us-east-1");
            });
            ThreadStates.awaitBlockedOrDone(tagThread, tag);
            assertTrue(tag.isDone() || storage.firstShareScanReleased()
                            || tagThread.get() != null && tagThread.get().getState() == Thread.State.BLOCKED,
                    "the tag worker must scan or block on the service lock");
            storage.releaseFirstShareScan();
            disassociate.get(15, TimeUnit.SECONDS);
            tag.get(15, TimeUnit.SECONDS);
        } finally {
            storage.releaseFirstShareScan();
            executor.shutdownNow();
        }

        ResourceShare stored = concurrentService.getResourceShares(OWNER, "SELF", "us-east-1").getFirst();
        assertTrue(stored.getResourceArns().isEmpty(),
                "the disassociation must survive the concurrent tag update");
        assertEquals("preserve", stored.getTags().get("race"),
                "the tag must survive the concurrent disassociation");
    }

    @Test
    void accountPrincipalWithoutOrganizationSharingCreatesPendingInvitation() {
        ResourceShare share = service.createResourceShare(
                "direct-share", List.of(ACCEPTER), List.of(TGW_ARN), false, "us-east-1", OWNER);

        List<ResourceShareInvitation> invitations =
                service.getResourceShareInvitations(ACCEPTER, List.of(), List.of(), "us-east-1");
        assertEquals(1, invitations.size());
        ResourceShareInvitation invitation = invitations.get(0);
        assertEquals(share.getResourceShareArn(), invitation.resourceShareArn());
        assertEquals(OWNER, invitation.senderAccountId());
        assertEquals(ACCEPTER, invitation.receiverAccountId());
        assertEquals("PENDING", invitation.status());

        // Receiver-only, per the API reference ("invitations that you have received"): the
        // sender does not see its own outstanding invitations through this call.
        assertTrue(service.getResourceShareInvitations(OWNER, List.of(), List.of(), "us-east-1").isEmpty());
        // But the share itself is visible regardless: invitations are bookkeeping, not a gate.
        assertEquals(1, service.getResourceShares(ACCEPTER, "OTHER-ACCOUNTS", "us-east-1").size());
    }

    @Test
    void organizationPrincipalNeverCreatesInvitationEvenWithoutOrganizationSharingEnabled() {
        service.createResourceShare(
                "ou-share", List.of(OU_ARN), List.of(TGW_ARN), false, "us-east-1", OWNER);

        assertTrue(service.getResourceShareInvitations(ACCEPTER, List.of(), List.of(), "us-east-1").isEmpty());
    }

    @Test
    void accountPrincipalUnderEnabledOrganizationSharingCreatesNoInvitation() {
        service.enableSharingWithAwsOrganization(OWNER);

        service.createResourceShare(
                "direct-share", List.of(ACCEPTER), List.of(TGW_ARN), false, "us-east-1", OWNER);

        assertTrue(service.getResourceShareInvitations(ACCEPTER, List.of(), List.of(), "us-east-1").isEmpty());
    }

    @Test
    void associatingNewAccountPrincipalCreatesInvitationButReassociatingExistingOneDoesNot() {
        ResourceShare share = service.createResourceShare(
                "direct-share", List.of(ACCEPTER), List.of(TGW_ARN), false, "us-east-1", OWNER);
        assertEquals(1, service.getResourceShareInvitations(ACCEPTER, List.of(), List.of(), "us-east-1").size());

        // Re-associating the same principal must not spawn a second invitation.
        service.associateResourceShare(share.getResourceShareArn(), List.of(), List.of(ACCEPTER), OWNER, "us-east-1");
        assertEquals(1, service.getResourceShareInvitations(ACCEPTER, List.of(), List.of(), "us-east-1").size());

        // A genuinely new principal gets its own.
        String other = "333333333333";
        service.associateResourceShare(share.getResourceShareArn(), List.of(), List.of(other), OWNER, "us-east-1");
        assertEquals(1, service.getResourceShareInvitations(other, List.of(), List.of(), "us-east-1").size());
        assertEquals(1, service.getResourceShareInvitations(ACCEPTER, List.of(), List.of(), "us-east-1").size());
    }

    @Test
    void acceptResourceShareInvitationTransitionsToAcceptedAndIsThenTerminal() {
        service.createResourceShare(
                "direct-share", List.of(ACCEPTER), List.of(TGW_ARN), false, "us-east-1", OWNER);
        String invitationArn = service.getResourceShareInvitations(ACCEPTER, List.of(), List.of(), "us-east-1")
                .get(0).resourceShareInvitationArn();

        ResourceShareInvitation accepted = service.acceptResourceShareInvitation(invitationArn, ACCEPTER, "us-east-1");
        assertEquals("ACCEPTED", accepted.status());
        assertEquals("ACCEPTED",
                service.getResourceShareInvitations(ACCEPTER, List.of(), List.of(), "us-east-1").get(0).status());

        AwsException repeat = assertThrows(AwsException.class,
                () -> service.acceptResourceShareInvitation(invitationArn, ACCEPTER, "us-east-1"));
        assertEquals("ResourceShareInvitationAlreadyAcceptedException", repeat.getErrorCode());
    }

    @Test
    void rejectResourceShareInvitationTransitionsToRejectedAndIsThenTerminal() {
        service.createResourceShare(
                "direct-share", List.of(ACCEPTER), List.of(TGW_ARN), false, "us-east-1", OWNER);
        String invitationArn = service.getResourceShareInvitations(ACCEPTER, List.of(), List.of(), "us-east-1")
                .get(0).resourceShareInvitationArn();

        ResourceShareInvitation rejected = service.rejectResourceShareInvitation(invitationArn, ACCEPTER, "us-east-1");
        assertEquals("REJECTED", rejected.status());

        AwsException repeat = assertThrows(AwsException.class,
                () -> service.rejectResourceShareInvitation(invitationArn, ACCEPTER, "us-east-1"));
        assertEquals("ResourceShareInvitationAlreadyRejectedException", repeat.getErrorCode());
    }

    @Test
    void reassociatingAfterRejectionSendsANewInvitation() {
        ResourceShare share = service.createResourceShare(
                "direct-share", List.of(ACCEPTER), List.of(TGW_ARN), false, "us-east-1", OWNER);
        String firstArn = service.getResourceShareInvitations(ACCEPTER, List.of(), List.of(), "us-east-1")
                .get(0).resourceShareInvitationArn();
        service.rejectResourceShareInvitation(firstArn, ACCEPTER, "us-east-1");

        // Disassociate and re-associate, same as a Terraform apply that removes then re-adds
        // the principal: real AWS sends a fresh invitation rather than leaving the account
        // permanently locked out by an old rejection.
        service.disassociateResourceShare(share.getResourceShareArn(), List.of(), List.of(ACCEPTER), OWNER,
                "us-east-1");
        service.associateResourceShare(share.getResourceShareArn(), List.of(), List.of(ACCEPTER), OWNER, "us-east-1");

        List<ResourceShareInvitation> invitations =
                service.getResourceShareInvitations(ACCEPTER, List.of(), List.of(), "us-east-1");
        assertEquals(2, invitations.size());
        assertTrue(invitations.stream().anyMatch(i -> "PENDING".equals(i.status())));

        // The live invitation must keep the share visible even when the rejected historical
        // invitation is returned first by storage scans.
        assertEquals(1, service.getResourceShares(ACCEPTER, "OTHER-ACCOUNTS", "us-east-1").size());
        assertEquals(1, service.listResources(ACCEPTER, "OTHER-ACCOUNTS",
                List.of(share.getResourceShareArn()), "us-east-1").size());
        assertEquals(1, service.listPrincipals(ACCEPTER, "OTHER-ACCOUNTS",
                List.of(share.getResourceShareArn()), "us-east-1").size());
    }

    @Test
    void onlyTheReceiverCanAcceptOrRejectAnInvitation() {
        service.createResourceShare(
                "direct-share", List.of(ACCEPTER), List.of(TGW_ARN), false, "us-east-1", OWNER);
        String invitationArn = service.getResourceShareInvitations(ACCEPTER, List.of(), List.of(), "us-east-1")
                .get(0).resourceShareInvitationArn();

        AwsException bySender = assertThrows(AwsException.class,
                () -> service.acceptResourceShareInvitation(invitationArn, OWNER, "us-east-1"));
        assertEquals("OperationNotPermittedException", bySender.getErrorCode());

        AwsException byStranger = assertThrows(AwsException.class,
                () -> service.rejectResourceShareInvitation(invitationArn, "444444444444", "us-east-1"));
        assertEquals("OperationNotPermittedException", byStranger.getErrorCode());
    }

    @Test
    void unknownInvitationArnFailsWithNotFoundException() {
        AwsException error = assertThrows(AwsException.class, () ->
                service.acceptResourceShareInvitation(
                        "arn:aws:ram:us-east-1:222222222222:resource-share-invitation/does-not-exist", ACCEPTER,
                                "us-east-1"));
        assertEquals("ResourceShareInvitationArnNotFoundException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void malformedInvitationArnFailsWithMalformedArnException() {
        AwsException error = assertThrows(AwsException.class, () ->
                service.acceptResourceShareInvitation("not-an-arn", ACCEPTER, "us-east-1"));
        assertEquals("MalformedArnException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());

        AwsException filterError = assertThrows(AwsException.class, () ->
                service.getResourceShareInvitations(ACCEPTER, List.of("not-an-arn"), List.of(), "us-east-1"));
        assertEquals("MalformedArnException", filterError.getErrorCode());
    }

    @Test
    void getResourceShareInvitationsFiltersByResourceShareArnAndInvitationArn() {
        ResourceShare share = service.createResourceShare(
                "direct-share", List.of(ACCEPTER), List.of(TGW_ARN), false, "us-east-1", OWNER);
        service.createResourceShare(
                "other-share", List.of(ACCEPTER), List.of(TGW_ARN), false, "us-east-1", OWNER);
        String invitationArn = service.getResourceShareInvitations(ACCEPTER, List.of(), List.of(), "us-east-1").stream()
                .filter(i -> i.resourceShareArn().equals(share.getResourceShareArn()))
                .findFirst().orElseThrow().resourceShareInvitationArn();

        assertEquals(1, service.getResourceShareInvitations(
                ACCEPTER, List.of(share.getResourceShareArn()), List.of(), "us-east-1").size());
        assertEquals(1, service.getResourceShareInvitations(
                ACCEPTER, List.of(), List.of(invitationArn), "us-east-1").size());
        assertTrue(service.getResourceShareInvitations(
                ACCEPTER, List.of("arn:aws:ram:us-east-1:111111111111:resource-share/nope"), List.of(),
                "us-east-1").isEmpty());
    }

    @Test
    void concurrentAcceptCallsOnTheSameInvitationOnlySucceedOnce() throws Exception {
        service.createResourceShare(
                "direct-share", List.of(ACCEPTER), List.of(TGW_ARN), false, "us-east-1", OWNER);
        String invitationArn = service.getResourceShareInvitations(ACCEPTER, List.of(), List.of(), "us-east-1")
                .get(0).resourceShareInvitationArn();

        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try {
                        service.acceptResourceShareInvitation(invitationArn, ACCEPTER, "us-east-1");
                        return true;
                    } catch (AwsException e) {
                        assertEquals("ResourceShareInvitationAlreadyAcceptedException", e.getErrorCode());
                        return false;
                    }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "every thread reached the start gate");
            start.countDown();

            int succeeded = 0;
            for (Future<Boolean> result : results) {
                if (result.get(5, TimeUnit.SECONDS)) {
                    succeeded++;
                }
            }
            assertEquals(1, succeeded, "exactly one concurrent Accept call should win the race");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentAssociateCallsForTheSamePrincipalCreateOnlyOneInvitation() throws Exception {
        ResourceShare share = service.createResourceShare(
                "direct-share", List.of(), List.of(TGW_ARN), false, "us-east-1", OWNER);

        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Void>> results = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    service.associateResourceShare(share.getResourceShareArn(), List.of(), List.of(ACCEPTER), OWNER,
                            "us-east-1");
                    return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "every thread reached the start gate");
            start.countDown();
            for (Future<Void> result : results) {
                result.get(5, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, service.getResourceShareInvitations(ACCEPTER, List.of(), List.of(), "us-east-1").size());
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
        // Same message an unknown ARN gets: a non-owner must not learn the share exists.
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

    private static final class TestStorageFactory extends StorageFactory {
        private final Map<String, RawStorage> rawStores = new HashMap<>();
        private final Map<String, AccountAwareStorageBackend<Object>> stores = new HashMap<>();
        private final boolean coordinateShareReads;

        private TestStorageFactory(boolean coordinateShareReads) {
            super(null, null);
            this.coordinateShareReads = coordinateShareReads;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName,
                                                    String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            AccountAwareStorageBackend<Object> store = stores.computeIfAbsent(fileName, ignored -> {
                RawStorage raw = new RawStorage(coordinateShareReads && fileName.equals("ram-resource-shares.json"));
                rawStores.put(fileName, raw);
                return new AccountAwareStorageBackend<>(raw, null, "000000000000");
            });
            return (AccountAwareStorageBackend<V>) store;
        }

        private void putLegacy(String fileName, String key, ResourceShare value) {
            rawStores.get(fileName).put(key, value);
        }

        private void armShareReads() {
            rawStores.get("ram-resource-shares.json").armReads();
        }

        private void armShareScans() {
            rawStores.get("ram-resource-shares.json").armScans();
        }

        private boolean awaitFirstShareScan() throws InterruptedException {
            return rawStores.get("ram-resource-shares.json").firstScanEntered.await(5, TimeUnit.SECONDS);
        }

        private void releaseFirstShareScan() {
            rawStores.get("ram-resource-shares.json").releaseFirstScan.countDown();
        }

        private boolean firstShareScanReleased() {
            return rawStores.get("ram-resource-shares.json").releaseFirstScan.getCount() == 0;
        }
    }

    private static final class RawStorage extends InMemoryStorage<String, Object> {
        private final boolean coordinateReads;
        private final AtomicInteger coordinatedReads = new AtomicInteger();
        private volatile CountDownLatch readGate = new CountDownLatch(0);
        private final AtomicInteger coordinatedScans = new AtomicInteger();
        private volatile CountDownLatch firstScanEntered = new CountDownLatch(0);
        private volatile CountDownLatch releaseFirstScan = new CountDownLatch(0);
        private volatile boolean coordinateScans;

        private RawStorage(boolean coordinateReads) {
            this.coordinateReads = coordinateReads;
        }

        private void armReads() {
            coordinatedReads.set(0);
            readGate = new CountDownLatch(2);
        }

        private void armScans() {
            coordinatedScans.set(0);
            firstScanEntered = new CountDownLatch(1);
            releaseFirstScan = new CountDownLatch(1);
            coordinateScans = true;
        }

        @Override
        public List<Object> scan(Predicate<String> keyFilter) {
            List<Object> snapshot = super.scan(keyFilter);
            if (!coordinateScans) {
                return snapshot;
            }
            int scanNumber = coordinatedScans.incrementAndGet();
            if (scanNumber == 1) {
                firstScanEntered.countDown();
                try {
                    assertTrue(releaseFirstScan.await(5, TimeUnit.SECONDS),
                            "the first share scan must be released");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("share scan interrupted", e);
                }
            } else if (scanNumber == 2) {
                releaseFirstScan.countDown();
            }
            return snapshot;
        }

        @Override
        public Optional<Object> get(String key) {
            if (coordinateReads && key.startsWith(OWNER + "/") && coordinatedReads.incrementAndGet() <= 2) {
                CountDownLatch gate = readGate;
                gate.countDown();
                try {
                    gate.await(250, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.get(key);
        }
    }
}
