package io.github.hectorvent.floci.services.ram;

import io.github.hectorvent.floci.services.ram.model.ResourceShare;
import io.github.hectorvent.floci.services.ram.model.SharedResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        service = new RamService();
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
}
