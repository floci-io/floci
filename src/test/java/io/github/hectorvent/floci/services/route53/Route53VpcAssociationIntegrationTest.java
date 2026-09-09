package io.github.hectorvent.floci.services.route53;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.route53.model.VpcAssociation;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class Route53VpcAssociationIntegrationTest {

    private static final String XML = "application/xml";
    private static final String ZONE_ACCOUNT = "000000000301";
    private static final String VPC_ACCOUNT = "000000000302";
    private static final String OTHER_ACCOUNT = "000000000303";

    @Inject
    Route53Service service;

    @Test
    void associateListAndDisassociateRoundTrip() {
        String zoneId = createPrivateZone("roundtrip.internal.", "vpc-assoc-roundtrip", "vpc-roundtrip-primary");

        given().contentType(XML)
                .body(vpcRequest("AssociateVPCWithHostedZoneRequest", "vpc-roundtrip-secondary",
                        "<Comment>central endpoints</Comment>"))
                .post("/2013-04-01/hostedzone/" + zoneId + "/associatevpc")
                .then().statusCode(200)
                .body(containsString("<AssociateVPCWithHostedZoneResponse"))
                .body(containsString("<ChangeInfo>"))
                .body(containsString("<Status>INSYNC</Status>"));

        given().get("/2013-04-01/hostedzone/" + zoneId)
                .then().statusCode(200)
                .body(containsString("<VPCId>vpc-roundtrip-primary</VPCId>"))
                .body(containsString("<VPCId>vpc-roundtrip-secondary</VPCId>"));

        given().get("/2013-04-01/hostedzonesbyvpc?vpcid=vpc-roundtrip-secondary&vpcregion=us-east-1")
                .then().statusCode(200)
                .body(containsString("<ListHostedZonesByVPCResponse"))
                .body(containsString("<HostedZoneSummary>"))
                .body(containsString("<HostedZoneId>" + zoneId + "</HostedZoneId>"))
                .body(containsString("<Name>roundtrip.internal.</Name>"))
                .body(containsString("<OwningAccount>"))
                .body(containsString("<MaxItems>"));

        given().contentType(XML)
                .body(vpcRequest("DisassociateVPCFromHostedZoneRequest", "vpc-roundtrip-secondary", ""))
                .post("/2013-04-01/hostedzone/" + zoneId + "/disassociatevpc")
                .then().statusCode(200)
                .body(containsString("<DisassociateVPCFromHostedZoneResponse"))
                .body(containsString("<Status>INSYNC</Status>"));

        given().get("/2013-04-01/hostedzone/" + zoneId)
                .then().statusCode(200)
                .body(containsString("<VPCId>vpc-roundtrip-primary</VPCId>"))
                .body(not(containsString("<VPCId>vpc-roundtrip-secondary</VPCId>")));

        given().get("/2013-04-01/hostedzonesbyvpc?vpcid=vpc-roundtrip-secondary&vpcregion=us-east-1")
                .then().statusCode(200)
                .body(not(containsString("<HostedZoneId>" + zoneId + "</HostedZoneId>")));
    }

    @Test
    void associateIsIdempotentForAnAlreadyAssociatedVpc() {
        String zoneId = createPrivateZone("idempotent.internal.", "vpc-assoc-idempotent", "vpc-idempotent-primary");

        given().contentType(XML)
                .body(vpcRequest("AssociateVPCWithHostedZoneRequest", "vpc-idempotent-primary", ""))
                .post("/2013-04-01/hostedzone/" + zoneId + "/associatevpc")
                .then().statusCode(200);

        // The zone must still carry exactly one association, so removing it is the
        // last-association case rather than leaving a duplicate behind.
        given().contentType(XML)
                .body(vpcRequest("DisassociateVPCFromHostedZoneRequest", "vpc-idempotent-primary", ""))
                .post("/2013-04-01/hostedzone/" + zoneId + "/disassociatevpc")
                .then().statusCode(400)
                .body(containsString("<Code>LastVPCAssociation</Code>"));
    }

    @Test
    void listHostedZonesByVpcPaginatesWithNextToken() {
        String shared = "vpc-paginated-shared";
        String firstZone = createPrivateZone("aaa-paged.internal.", "vpc-assoc-paged-a", shared);
        String secondZone = createPrivateZone("bbb-paged.internal.", "vpc-assoc-paged-b", shared);

        String token = given().get("/2013-04-01/hostedzonesbyvpc?vpcid=" + shared
                        + "&vpcregion=us-east-1&maxitems=1")
                .then().statusCode(200)
                .body(containsString("<HostedZoneId>" + firstZone + "</HostedZoneId>"))
                .body(not(containsString("<HostedZoneId>" + secondZone + "</HostedZoneId>")))
                .body(containsString("<NextToken>"))
                .extract().path("ListHostedZonesByVPCResponse.NextToken").toString();

        // Following the token must advance the page rather than repeat the first one.
        given().get("/2013-04-01/hostedzonesbyvpc?vpcid=" + shared
                        + "&vpcregion=us-east-1&maxitems=1&nexttoken=" + token)
                .then().statusCode(200)
                .body(containsString("<HostedZoneId>" + secondZone + "</HostedZoneId>"))
                .body(not(containsString("<HostedZoneId>" + firstZone + "</HostedZoneId>")))
                .body(not(containsString("<NextToken>")));
    }

    @Test
    void associateWithPublicZoneIsRejected() {
        String create = """
                <CreateHostedZoneRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <Name>public-assoc.example.com</Name>
                  <CallerReference>vpc-assoc-public-zone</CallerReference>
                </CreateHostedZoneRequest>
                """;
        String zoneId = zoneIdFrom(given().contentType(XML).body(create)
                .post("/2013-04-01/hostedzone")
                .then().statusCode(201).extract().header("Location"));

        given().contentType(XML)
                .body(vpcRequest("AssociateVPCWithHostedZoneRequest", "vpc-public-reject", ""))
                .post("/2013-04-01/hostedzone/" + zoneId + "/associatevpc")
                .then().statusCode(400)
                .body(containsString("<Code>PublicZoneVPCAssociation</Code>"));
    }

    @Test
    void disassociateUnknownVpcReturnsVpcAssociationNotFound() {
        String zoneId = createPrivateZone("notfound.internal.", "vpc-assoc-notfound", "vpc-notfound-primary");

        given().contentType(XML)
                .body(vpcRequest("DisassociateVPCFromHostedZoneRequest", "vpc-never-associated", ""))
                .post("/2013-04-01/hostedzone/" + zoneId + "/disassociatevpc")
                .then().statusCode(404)
                .body(containsString("<Code>VPCAssociationNotFound</Code>"));
    }

    @Test
    void associateWithoutVpcElementIsInvalidInput() {
        String zoneId = createPrivateZone("novpc.internal.", "vpc-assoc-missing-vpc", "vpc-missing-primary");

        String body = """
                <AssociateVPCWithHostedZoneRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <Comment>no vpc supplied</Comment>
                </AssociateVPCWithHostedZoneRequest>
                """;
        given().contentType(XML).body(body)
                .post("/2013-04-01/hostedzone/" + zoneId + "/associatevpc")
                .then().statusCode(400)
                .body(containsString("<Code>InvalidInput</Code>"));
    }

    @Test
    void associateWithUnknownHostedZoneReturnsNoSuchHostedZone() {
        given().contentType(XML)
                .body(vpcRequest("AssociateVPCWithHostedZoneRequest", "vpc-orphan", ""))
                .post("/2013-04-01/hostedzone/ZNOSUCHZONE123/associatevpc")
                .then().statusCode(404)
                .body(containsString("<Code>NoSuchHostedZone</Code>"));
    }

    @Test
    void createListAndDeleteVpcAssociationAuthorization() {
        String zoneId = createPrivateZone("authz.internal.", "vpc-assoc-authorization", "vpc-authz-primary");

        given().contentType(XML)
                .body(vpcRequest("CreateVPCAssociationAuthorizationRequest", "vpc-authz-guest", ""))
                .post("/2013-04-01/hostedzone/" + zoneId + "/authorizevpcassociation")
                .then().statusCode(200)
                .body(containsString("<CreateVPCAssociationAuthorizationResponse"))
                .body(containsString("<HostedZoneId>" + zoneId + "</HostedZoneId>"))
                .body(containsString("<VPCId>vpc-authz-guest</VPCId>"))
                .body(containsString("<VPCRegion>us-east-1</VPCRegion>"));

        given().get("/2013-04-01/hostedzone/" + zoneId + "/authorizevpcassociation")
                .then().statusCode(200)
                .body(containsString("<ListVPCAssociationAuthorizationsResponse"))
                .body(containsString("<HostedZoneId>" + zoneId + "</HostedZoneId>"))
                .body(containsString("<VPCId>vpc-authz-guest</VPCId>"));

        given().contentType(XML)
                .body(vpcRequest("DeleteVPCAssociationAuthorizationRequest", "vpc-authz-guest", ""))
                .post("/2013-04-01/hostedzone/" + zoneId + "/deauthorizevpcassociation")
                .then().statusCode(200);

        given().get("/2013-04-01/hostedzone/" + zoneId + "/authorizevpcassociation")
                .then().statusCode(200)
                .body(not(containsString("<VPCId>vpc-authz-guest</VPCId>")));

        given().contentType(XML)
                .body(vpcRequest("DeleteVPCAssociationAuthorizationRequest", "vpc-authz-guest", ""))
                .post("/2013-04-01/hostedzone/" + zoneId + "/deauthorizevpcassociation")
                .then().statusCode(404)
                .body(containsString("<Code>VPCAssociationAuthorizationNotFound</Code>"));
    }

    @Test
    void authorizeVpcAssociationOnUnknownZoneReturnsNoSuchHostedZone() {
        given().contentType(XML)
                .body(vpcRequest("CreateVPCAssociationAuthorizationRequest", "vpc-authz-orphan", ""))
                .post("/2013-04-01/hostedzone/ZNOSUCHZONE456/authorizevpcassociation")
                .then().statusCode(404)
                .body(containsString("<Code>NoSuchHostedZone</Code>"));
    }

    @Test
    void listHostedZonesByVpcRequiresVpcIdAndRegion() {
        given().get("/2013-04-01/hostedzonesbyvpc?vpcregion=us-east-1")
                .then().statusCode(400)
                .body(containsString("<Code>InvalidInput</Code>"))
                .body(containsString("VPCId is required"));

        given().get("/2013-04-01/hostedzonesbyvpc?vpcid=vpc-no-region")
                .then().statusCode(400)
                .body(containsString("<Code>InvalidInput</Code>"))
                .body(containsString("VPCRegion is required"));
    }

    @Test
    void associateWithAnUnmodelledVpcRegionIsRejected() {
        String zoneId = createPrivateZone("badregion.internal.", "vpc-assoc-bad-region", "vpc-badregion-primary");

        String body = """
                <AssociateVPCWithHostedZoneRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <VPC><VPCRegion>us-east-99</VPCRegion><VPCId>vpc-badregion-secondary</VPCId></VPC>
                </AssociateVPCWithHostedZoneRequest>
                """;
        given().contentType(XML).body(body)
                .post("/2013-04-01/hostedzone/" + zoneId + "/associatevpc")
                .then().statusCode(400)
                .body(containsString("<Code>InvalidInput</Code>"))
                .body(containsString("VPCRegion"));

        // The rejected association must not have been stored.
        given().get("/2013-04-01/hostedzone/" + zoneId)
                .then().statusCode(200)
                .body(not(containsString("<VPCId>vpc-badregion-secondary</VPCId>")));
    }

    @Test
    void authorizeVpcAssociationWithAnUnmodelledVpcRegionIsRejected() {
        String zoneId = createPrivateZone("authzregion.internal.", "vpc-assoc-authz-region", "vpc-authzregion-primary");

        String body = """
                <CreateVPCAssociationAuthorizationRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <VPC><VPCRegion>not-a-region</VPCRegion><VPCId>vpc-authzregion-guest</VPCId></VPC>
                </CreateVPCAssociationAuthorizationRequest>
                """;
        given().contentType(XML).body(body)
                .post("/2013-04-01/hostedzone/" + zoneId + "/authorizevpcassociation")
                .then().statusCode(400)
                .body(containsString("<Code>InvalidInput</Code>"))
                .body(containsString("VPCRegion"));
    }

    @Test
    void listHostedZonesByVpcAcceptsAnUnmodelledVpcRegionAndReturnsNoMatches() {
        // Deliberately not enum-gated (see legacyAssociationWithAnUnmodelledRegionCanStillBeListedAndRemoved):
        // an unrecognized region with no associations simply yields an empty result, the same as
        // any other vpcid/vpcregion pair nothing is associated with.
        given().get("/2013-04-01/hostedzonesbyvpc?vpcid=vpc-anything&vpcregion=us-east-99")
                .then().statusCode(200)
                .body(not(containsString("<HostedZoneSummary>")));
    }

    /**
     * The VPCRegion enum covers partitions this emulator does not advertise, so the check
     * must follow the model rather than the DescribeRegions catalog.
     */
    @Test
    void associateAcceptsAModelledNonCommercialVpcRegion() {
        String zoneId = createPrivateZone("isoregion.internal.", "vpc-assoc-iso-region", "vpc-isoregion-primary");

        String body = """
                <AssociateVPCWithHostedZoneRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <VPC><VPCRegion>us-iso-east-1</VPCRegion><VPCId>vpc-isoregion-secondary</VPCId></VPC>
                </AssociateVPCWithHostedZoneRequest>
                """;
        given().contentType(XML).body(body)
                .post("/2013-04-01/hostedzone/" + zoneId + "/associatevpc")
                .then().statusCode(200)
                .body(containsString("<AssociateVPCWithHostedZoneResponse"));
    }

    @Test
    void createHostedZoneRejectsAnUnmodelledVpcRegion() {
        String create = """
                <CreateHostedZoneRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <Name>createregion.internal.</Name>
                  <CallerReference>vpc-assoc-create-bad-region</CallerReference>
                  <VPC><VPCRegion>us-east-99</VPCRegion><VPCId>vpc-createregion-primary</VPCId></VPC>
                </CreateHostedZoneRequest>
                """;
        given().contentType(XML).body(create)
                .post("/2013-04-01/hostedzone")
                .then().statusCode(400)
                .body(containsString("<Code>InvalidInput</Code>"))
                .body(containsString("VPCRegion"));
    }

    @Test
    void listHostedZonesByVpcWithAnUnknownNextTokenIsInvalidPaginationToken() {
        String zoneId = createPrivateZone("badtoken.internal.", "vpc-assoc-bad-token", "vpc-badtoken-primary");

        given().get("/2013-04-01/hostedzonesbyvpc?vpcid=vpc-badtoken-primary&vpcregion=us-east-1"
                        + "&nexttoken=" + zoneId + "-does-not-exist")
                .then().statusCode(400)
                .body(containsString("<Code>InvalidPaginationToken</Code>"))
                .body(containsString("NextToken"));
    }

    @Test
    void associateWithAVpcAlreadyOnAnotherZoneOfTheSameNameIsConflictingDomainExists() {
        String shared = "vpc-domain-conflict-shared";
        String firstZone = createPrivateZone("domainconflict.internal.", "vpc-assoc-domain-conflict-a", shared);
        String secondZone = createPrivateZone("domainconflict.internal.", "vpc-assoc-domain-conflict-b",
                "vpc-domain-conflict-other");

        given().contentType(XML)
                .body(vpcRequest("AssociateVPCWithHostedZoneRequest", shared, ""))
                .post("/2013-04-01/hostedzone/" + secondZone + "/associatevpc")
                .then().statusCode(400)
                .body(containsString("<Code>ConflictingDomainExists</Code>"));

        // The rejected association must not have been stored on the second zone.
        given().get("/2013-04-01/hostedzone/" + secondZone)
                .then().statusCode(200)
                .body(not(containsString("<VPCId>" + shared + "</VPCId>")));
    }

    @Test
    void listHostedZonesByVpcWithANonPositiveMaxItemsIsInvalidInput() {
        given().get("/2013-04-01/hostedzonesbyvpc?vpcid=vpc-anything&vpcregion=us-east-1&maxitems=0")
                .then().statusCode(400)
                .body(containsString("<Code>InvalidInput</Code>"))
                .body(containsString("MaxItems"));
    }

    @Test
    void listHostedZonesByVpcPaginationIsStableAcrossEqualNames() {
        // Sorting only by name leaves the relative order of equal-named zones dependent on the
        // backing store's own iteration order, which can differ between the page-1 and page-2
        // fetch calls (each re-scans the store), skipping or repeating an entry at the
        // ID-based continuation point. Tie-breaking by ID as well makes the total order fixed
        // regardless of iteration order, so this asserts the walk is a stable ID-ascending walk.
        String shared = "vpc-tiebreak-shared";
        java.util.List<String> zoneIds = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            zoneIds.add(createPrivateZone("tiebreak.internal.", "vpc-tiebreak-" + i, shared));
        }
        java.util.List<String> expectedOrder = new java.util.ArrayList<>(zoneIds);
        java.util.Collections.sort(expectedOrder);

        java.util.List<String> walked = new java.util.ArrayList<>();
        String token = null;
        for (int page = 0; page < zoneIds.size(); page++) {
            String url = "/2013-04-01/hostedzonesbyvpc?vpcid=" + shared + "&vpcregion=us-east-1&maxitems=1"
                    + (token != null ? "&nexttoken=" + token : "");
            io.restassured.response.Response resp = given().get(url).then().statusCode(200).extract().response();
            io.restassured.path.xml.XmlPath xml = resp.xmlPath();
            walked.add(xml.getString("ListHostedZonesByVPCResponse.HostedZoneSummaries.HostedZoneSummary.HostedZoneId"));
            String next = xml.getString("ListHostedZonesByVPCResponse.NextToken");
            token = (next == null || next.isEmpty()) ? null : next;
        }

        assertEquals(expectedOrder, walked,
                "pagination across equal-named zones must follow a stable ID-ascending tie-break");
    }

    @Test
    void legacyAssociationWithAnUnmodelledRegionCanStillBeListedAndRemoved() {
        // Simulates a VPC association persisted before requireModelledVpcRegion existed (or by a
        // region since retired from the enum): seed it directly through the service, bypassing
        // the controller's enum check entirely, the way an upgrade would inherit it from disk.
        String zoneId = createPrivateZone(
                "legacy-region.internal.", "vpc-legacy-region", "vpc-legacy-primary");
        service.associateVpcWithHostedZone(zoneId,
                new VpcAssociation("vpc-legacy-secondary", "legacy-unmodelled-region-1"), null);

        given().get("/2013-04-01/hostedzonesbyvpc?vpcid=vpc-legacy-secondary&vpcregion=legacy-unmodelled-region-1")
                .then().statusCode(200)
                .body(containsString("<HostedZoneId>" + zoneId + "</HostedZoneId>"));

        given().contentType(XML)
                .body("""
                        <DisassociateVPCFromHostedZoneRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                          <VPC><VPCRegion>legacy-unmodelled-region-1</VPCRegion><VPCId>vpc-legacy-secondary</VPCId></VPC>
                        </DisassociateVPCFromHostedZoneRequest>
                        """)
                .post("/2013-04-01/hostedzone/" + zoneId + "/disassociatevpc")
                .then().statusCode(200)
                .body(containsString("<DisassociateVPCFromHostedZoneResponse"));
    }

    @Test
    void crossAccountAuthorizationEnforcesVpcOwnershipAndCleanupLifecycle() {
        String zoneVpc = createVpcForAccount(ZONE_ACCOUNT, "10.81.0.0/16");
        String spokeVpc = createVpcForAccount(VPC_ACCOUNT, "10.82.0.0/16");
        String zoneId = createPrivateZoneForAccount(
                ZONE_ACCOUNT, "cross-account.internal.", "vpc-assoc-cross-account", zoneVpc);

        // AWS requires the VPC owner to associate, and requires the hosted-zone owner to
        // authorize first. A different account without authorization gets NotAuthorizedException.
        given().contentType(XML)
                .header("Authorization", route53Auth(VPC_ACCOUNT))
                .body(vpcRequest("AssociateVPCWithHostedZoneRequest", spokeVpc, ""))
                .post("/2013-04-01/hostedzone/" + zoneId + "/associatevpc")
                .then().statusCode(401)
                .body(containsString("<Code>NotAuthorizedException</Code>"));

        given().contentType(XML)
                .header("Authorization", route53Auth(ZONE_ACCOUNT))
                .body(vpcRequest("CreateVPCAssociationAuthorizationRequest", spokeVpc, ""))
                .post("/2013-04-01/hostedzone/" + zoneId + "/authorizevpcassociation")
                .then().statusCode(200);

        // The authorization is specifically for the account that owns this VPC. A third account
        // cannot reuse the hosted-zone owner's authorization by copying the VPC id.
        given().contentType(XML)
                .header("Authorization", route53Auth(OTHER_ACCOUNT))
                .body(vpcRequest("AssociateVPCWithHostedZoneRequest", spokeVpc, ""))
                .post("/2013-04-01/hostedzone/" + zoneId + "/associatevpc")
                .then().statusCode(400)
                .body(containsString("<Code>InvalidVPCId</Code>"));

        given().contentType(XML)
                .header("Authorization", route53Auth(VPC_ACCOUNT))
                .body(vpcRequest("AssociateVPCWithHostedZoneRequest", spokeVpc, ""))
                .post("/2013-04-01/hostedzone/" + zoneId + "/associatevpc")
                .then().statusCode(200);

        // ListHostedZonesByVPC is cross-account and reports the account that owns the zone.
        given().header("Authorization", route53Auth(VPC_ACCOUNT))
                .get("/2013-04-01/hostedzonesbyvpc?vpcid=" + spokeVpc + "&vpcregion=us-east-1")
                .then().statusCode(200)
                .body(containsString("<HostedZoneId>" + zoneId + "</HostedZoneId>"))
                .body(containsString("<OwningAccount>" + ZONE_ACCOUNT + "</OwningAccount>"));

        // AWS documents ListHostedZonesByVPC as cross-account discovery: callers can list
        // associations for a VPC regardless of which account owns the VPC or hosted zone.
        given().header("Authorization", route53Auth(OTHER_ACCOUNT))
                .get("/2013-04-01/hostedzonesbyvpc?vpcid=" + spokeVpc + "&vpcregion=us-east-1")
                .then().statusCode(200)
                .body(containsString("<HostedZoneId>" + zoneId + "</HostedZoneId>"))
                .body(containsString("<OwningAccount>" + ZONE_ACCOUNT + "</OwningAccount>"));

        // AWS recommends deleting the authorization after association. Success has an empty body,
        // and deleting it must not remove the established association.
        given().contentType(XML)
                .header("Authorization", route53Auth(ZONE_ACCOUNT))
                .body(vpcRequest("DeleteVPCAssociationAuthorizationRequest", spokeVpc, ""))
                .post("/2013-04-01/hostedzone/" + zoneId + "/deauthorizevpcassociation")
                .then().statusCode(200)
                .body(org.hamcrest.Matchers.emptyOrNullString());

        given().header("Authorization", route53Auth(VPC_ACCOUNT))
                .get("/2013-04-01/hostedzonesbyvpc?vpcid=" + spokeVpc + "&vpcregion=us-east-1")
                .then().statusCode(200)
                .body(containsString("<HostedZoneId>" + zoneId + "</HostedZoneId>"));

        // AWS permits either the hosted-zone owner or the VPC owner to disassociate.
        given().contentType(XML)
                .header("Authorization", route53Auth(ZONE_ACCOUNT))
                .body(vpcRequest("DisassociateVPCFromHostedZoneRequest", spokeVpc, ""))
                .post("/2013-04-01/hostedzone/" + zoneId + "/disassociatevpc")
                .then().statusCode(200);

        // With the authorization cleaned up, a later reassociation requires a new authorization.
        given().contentType(XML)
                .header("Authorization", route53Auth(VPC_ACCOUNT))
                .body(vpcRequest("AssociateVPCWithHostedZoneRequest", spokeVpc, ""))
                .post("/2013-04-01/hostedzone/" + zoneId + "/associatevpc")
                .then().statusCode(401)
                .body(containsString("<Code>NotAuthorizedException</Code>"));
    }

    @Test
    void unownedAuthorizationCannotBeConsumedCrossAccount() {
        String zoneVpc = createVpcForAccount(ZONE_ACCOUNT, "10.83.0.0/16");
        String zoneId = createPrivateZoneForAccount(
                ZONE_ACCOUNT, "unowned-auth.internal.", "vpc-assoc-unowned-auth", zoneVpc);
        String unknownVpc = "vpc-unmodelled-cross-account";

        given().contentType(XML)
                .header("Authorization", route53Auth(ZONE_ACCOUNT))
                .body(vpcRequest("CreateVPCAssociationAuthorizationRequest", unknownVpc, ""))
                .post("/2013-04-01/hostedzone/" + zoneId + "/authorizevpcassociation")
                .then().statusCode(200);

        given().contentType(XML)
                .header("Authorization", route53Auth(OTHER_ACCOUNT))
                .body(vpcRequest("AssociateVPCWithHostedZoneRequest", unknownVpc, ""))
                .post("/2013-04-01/hostedzone/" + zoneId + "/associatevpc")
                .then().statusCode(400)
                .body(containsString("<Code>InvalidVPCId</Code>"));
    }

    @Test
    void legacyAssociationWithoutOwnerStillRequiresVpcOrZoneOwner() {
        String zoneVpc = createVpcForAccount(ZONE_ACCOUNT, "10.84.0.0/16");
        String spokeVpc = createVpcForAccount(VPC_ACCOUNT, "10.85.0.0/16");
        String zoneId = createPrivateZoneForAccount(
                ZONE_ACCOUNT, "legacy-association.internal.", "vpc-assoc-legacy-owner", zoneVpc);

        given().contentType(XML)
                .header("Authorization", route53Auth(ZONE_ACCOUNT))
                .body(vpcRequest("CreateVPCAssociationAuthorizationRequest", spokeVpc, ""))
                .post("/2013-04-01/hostedzone/" + zoneId + "/authorizevpcassociation")
                .then().statusCode(200);
        given().contentType(XML)
                .header("Authorization", route53Auth(VPC_ACCOUNT))
                .body(vpcRequest("AssociateVPCWithHostedZoneRequest", spokeVpc, ""))
                .post("/2013-04-01/hostedzone/" + zoneId + "/associatevpc")
                .then().statusCode(200);

        var zone = service.listHostedZonesByVpc(spokeVpc, "us-east-1").stream()
                .filter(candidate -> candidate.getId().equals(zoneId))
                .findFirst().orElseThrow();
        zone.getVpcAssociations().stream()
                .filter(association -> spokeVpc.equals(association.getVpcId()))
                .findFirst().orElseThrow()
                .setOwnerAccountId(null);

        given().contentType(XML)
                .header("Authorization", route53Auth(OTHER_ACCOUNT))
                .body(vpcRequest("DisassociateVPCFromHostedZoneRequest", spokeVpc, ""))
                .post("/2013-04-01/hostedzone/" + zoneId + "/disassociatevpc")
                .then().statusCode(400)
                .body(containsString("<Code>InvalidVPCId</Code>"));

        given().contentType(XML)
                .header("Authorization", route53Auth(VPC_ACCOUNT))
                .body(vpcRequest("DisassociateVPCFromHostedZoneRequest", spokeVpc, ""))
                .post("/2013-04-01/hostedzone/" + zoneId + "/disassociatevpc")
                .then().statusCode(200);
    }

    @Test
    void legacyNonDefaultZoneInfersOwnerFromStoragePartition() {
        String zoneVpc = createVpcForAccount(ZONE_ACCOUNT, "10.86.0.0/16");
        String zoneId = createPrivateZoneForAccount(
                ZONE_ACCOUNT, "legacy-zone.internal.", "vpc-assoc-legacy-zone", zoneVpc);

        var legacyZone = service.listHostedZonesByVpc(zoneVpc, "us-east-1").stream()
                .filter(candidate -> candidate.getId().equals(zoneId))
                .findFirst().orElseThrow();
        legacyZone.setOwnerAccountId(null);

        given().contentType(XML)
                .header("Authorization", route53Auth(ZONE_ACCOUNT))
                .body(vpcRequest("CreateVPCAssociationAuthorizationRequest", "vpc-legacy-zone-guest", ""))
                .post("/2013-04-01/hostedzone/" + zoneId + "/authorizevpcassociation")
                .then().statusCode(200);

        given().header("Authorization", route53Auth(ZONE_ACCOUNT))
                .get("/2013-04-01/hostedzone/" + zoneId + "/authorizevpcassociation")
                .then().statusCode(200)
                .body(containsString("<VPCId>vpc-legacy-zone-guest</VPCId>"));
    }

    @Test
    void documentedVpcAssociationQuotaReturnsLimitsExceeded() {
        String zoneId = createPrivateZone(
                "association-limit.internal.", "vpc-assoc-limit", "vpc-limit-primary");
        for (int i = 1; i < 300; i++) {
            service.associateVpcWithHostedZone(
                    zoneId, new VpcAssociation("vpc-limit-" + i, "us-east-1"), null);
        }

        AwsException error = assertThrows(AwsException.class, () ->
                service.associateVpcWithHostedZone(
                        zoneId, new VpcAssociation("vpc-limit-overflow", "us-east-1"), null));
        assertEquals("LimitsExceeded", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void documentedAuthorizationQuotaReturnsTooManyAuthorizations() {
        String zoneId = createPrivateZone(
                "authorization-limit.internal.", "vpc-authz-limit", "vpc-authz-limit-primary");
        for (int i = 0; i < 1000; i++) {
            service.createVpcAssociationAuthorization(
                    zoneId, new VpcAssociation("vpc-authz-limit-" + i, "us-east-1"));
        }

        AwsException error = assertThrows(AwsException.class, () ->
                service.createVpcAssociationAuthorization(
                        zoneId, new VpcAssociation("vpc-authz-limit-overflow", "us-east-1")));
        assertEquals("TooManyVPCAssociationAuthorizations", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    private static String createVpcForAccount(String accountId, String cidr) {
        return given()
                .formParam("Action", "CreateVpc")
                .formParam("CidrBlock", cidr)
                .header("Authorization", ec2Auth(accountId))
                .post("/")
                .then().statusCode(200)
                .extract().path("CreateVpcResponse.vpc.vpcId");
    }

    private static String createPrivateZoneForAccount(String accountId, String name,
                                                       String callerReference, String vpcId) {
        String create = """
                <CreateHostedZoneRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <Name>%s</Name>
                  <CallerReference>%s</CallerReference>
                  <VPC><VPCRegion>us-east-1</VPCRegion><VPCId>%s</VPCId></VPC>
                </CreateHostedZoneRequest>
                """.formatted(name, callerReference, vpcId);
        return zoneIdFrom(given().contentType(XML)
                .header("Authorization", route53Auth(accountId))
                .body(create)
                .post("/2013-04-01/hostedzone")
                .then().statusCode(201).extract().header("Location"));
    }

    private static String route53Auth(String accountId) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId
                + "/20260905/us-east-1/route53/aws4_request, SignedHeaders=host, Signature=abc";
    }

    private static String ec2Auth(String accountId) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId
                + "/20260905/us-east-1/ec2/aws4_request, SignedHeaders=host, Signature=abc";
    }

    private static String createPrivateZone(String name, String callerReference, String vpcId) {
        String create = """
                <CreateHostedZoneRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <Name>%s</Name>
                  <CallerReference>%s</CallerReference>
                  <VPC><VPCRegion>us-east-1</VPCRegion><VPCId>%s</VPCId></VPC>
                </CreateHostedZoneRequest>
                """.formatted(name, callerReference, vpcId);
        return zoneIdFrom(given().contentType(XML).body(create)
                .post("/2013-04-01/hostedzone")
                .then().statusCode(201).extract().header("Location"));
    }

    private static String vpcRequest(String rootElement, String vpcId, String extra) {
        return """
                <%s xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <VPC><VPCRegion>us-east-1</VPCRegion><VPCId>%s</VPCId></VPC>%s
                </%s>
                """.formatted(rootElement, vpcId, extra, rootElement);
    }

    private static String zoneIdFrom(String location) {
        return location.substring(location.lastIndexOf('/') + 1);
    }
}
