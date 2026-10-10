package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * Transit gateway peering attachments over the EC2 Query protocol: the create, describe, accept,
 * tag and delete calls the Terraform provider's {@code aws_ec2_transit_gateway_peering_attachment}
 * and its {@code _accepter} make. Every state change is read back through a separate describe
 * rather than trusted from the mutating call's own response.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2TransitGatewayPeeringAttachmentIntegrationTest {

    private static final String EAST =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";
    private static final String WEST =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-west-2/ec2/aws4_request";
    /** A second account, in the same region as {@link #EAST}. */
    private static final String PEER_ACCOUNT =
            "AWS4-HMAC-SHA256 Credential=000000000002/20260205/us-east-1/ec2/aws4_request";
    private static final String ITEM =
            "DescribeTransitGatewayPeeringAttachmentsResponse.transitGatewayPeeringAttachments.item";

    private static String requesterTgw;
    private static String accepterTgw;
    private static String attachmentId;

    @Test
    @Order(1)
    void createTheTwoGateways() {
        requesterTgw = createTransitGateway(EAST);
        accepterTgw = createTransitGateway(EAST);
    }

    private static String createTransitGateway(String auth) {
        return given()
            .formParam("Action", "CreateTransitGateway")
            .header("Authorization", auth)
        .when().post("/")
        .then().statusCode(200)
            .extract().path("CreateTransitGatewayResponse.transitGateway.transitGatewayId");
    }

    @Test
    @Order(2)
    void createAwaitsAcceptance() {
        attachmentId = given()
            .formParam("Action", "CreateTransitGatewayPeeringAttachment")
            .formParam("TransitGatewayId", requesterTgw)
            .formParam("PeerTransitGatewayId", accepterTgw)
            .formParam("PeerAccountId", "000000000000")
            .formParam("PeerRegion", "us-east-1")
            .formParam("TagSpecification.1.ResourceType", "transit-gateway-attachment")
            .formParam("TagSpecification.1.Tag.1.Key", "side")
            .formParam("TagSpecification.1.Tag.1.Value", "requester")
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(200)
            .body("CreateTransitGatewayPeeringAttachmentResponse.transitGatewayPeeringAttachment.state",
                    equalTo("pendingAcceptance"))
            .extract().path("CreateTransitGatewayPeeringAttachmentResponse.transitGatewayPeeringAttachment"
                    + ".transitGatewayAttachmentId");

        given()
            .formParam("Action", "DescribeTransitGatewayPeeringAttachments")
            .formParam("TransitGatewayAttachmentIds.1", attachmentId)
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(200)
            .body(ITEM + ".state", equalTo("pendingAcceptance"))
            .body(ITEM + ".requesterTgwInfo.transitGatewayId", equalTo(requesterTgw))
            .body(ITEM + ".requesterTgwInfo.ownerId", equalTo("000000000000"))
            .body(ITEM + ".requesterTgwInfo.region", equalTo("us-east-1"))
            .body(ITEM + ".accepterTgwInfo.transitGatewayId", equalTo(accepterTgw))
            .body(ITEM + ".accepterTgwInfo.ownerId", equalTo("000000000000"))
            .body(ITEM + ".accepterTgwInfo.region", equalTo("us-east-1"))
            .body(ITEM + ".options.dynamicRouting", equalTo("disable"))
            .body(ITEM + ".tagSet.item.key", equalTo("side"))
            .body(ITEM + ".tagSet.item.value", equalTo("requester"));
    }

    /** The accepter module finds the attachment this way, by the accepter's own gateway. */
    @Test
    @Order(3)
    void theAccepterFindsItByItsGatewayAndState() {
        given()
            .formParam("Action", "DescribeTransitGatewayPeeringAttachments")
            .formParam("Filter.1.Name", "transit-gateway-id")
            .formParam("Filter.1.Value.1", accepterTgw)
            .formParam("Filter.2.Name", "state")
            .formParam("Filter.2.Value.1", "available")
            .formParam("Filter.2.Value.2", "pendingAcceptance")
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(200)
            .body(ITEM + ".transitGatewayAttachmentId", equalTo(attachmentId));

        given()
            .formParam("Action", "DescribeTransitGatewayPeeringAttachments")
            .formParam("Filter.1.Name", "state")
            .formParam("Filter.1.Value.1", "available")
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(200)
            .body(ITEM + ".transitGatewayAttachmentId", not(hasItem(attachmentId)));
    }

    @Test
    @Order(4)
    void acceptMakesItAvailableAndTagsRoundTrip() {
        given()
            .formParam("Action", "AcceptTransitGatewayPeeringAttachment")
            .formParam("TransitGatewayAttachmentId", attachmentId)
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(200);

        given()
            .formParam("Action", "CreateTags")
            .formParam("ResourceId.1", attachmentId)
            .formParam("Tag.1.Key", "side")
            .formParam("Tag.1.Value", "accepter")
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(200);

        given()
            .formParam("Action", "DescribeTransitGatewayPeeringAttachments")
            .formParam("TransitGatewayAttachmentIds.1", attachmentId)
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(200)
            .body(ITEM + ".state", equalTo("available"))
            .body(ITEM + ".tagSet.item.value", equalTo("accepter"));

        // Only a pending attachment can be accepted.
        given()
            .formParam("Action", "AcceptTransitGatewayPeeringAttachment")
            .formParam("TransitGatewayAttachmentId", attachmentId)
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("IncorrectState"));
    }

    @Test
    @Order(5)
    void deleteRemovesIt() {
        given()
            .formParam("Action", "DeleteTransitGatewayPeeringAttachment")
            .formParam("TransitGatewayAttachmentId", attachmentId)
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(200)
            .body("DeleteTransitGatewayPeeringAttachmentResponse.transitGatewayPeeringAttachment.state",
                    equalTo("deleted"));

        given()
            .formParam("Action", "DescribeTransitGatewayPeeringAttachments")
            .formParam("TransitGatewayAttachmentIds.1", attachmentId)
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidTransitGatewayAttachmentID.NotFound"));
    }

    @Test
    @Order(6)
    void anUnknownRequesterGatewayIsRefused() {
        given()
            .formParam("Action", "CreateTransitGatewayPeeringAttachment")
            .formParam("TransitGatewayId", "tgw-0123456789abcdef0")
            .formParam("PeerTransitGatewayId", accepterTgw)
            .formParam("PeerAccountId", "000000000000")
            .formParam("PeerRegion", "us-east-1")
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidTransitGatewayID.NotFound"));
    }

    /**
     * A cross-region peering is one attachment id seen from both regions, and only the accepter's
     * region can accept it.
     */
    @Test
    @Order(7)
    void crossRegionIsVisibleFromBothSidesAndAcceptedOnlyByThePeer() {
        String westTgw = createTransitGateway(WEST);
        String id = given()
            .formParam("Action", "CreateTransitGatewayPeeringAttachment")
            .formParam("TransitGatewayId", requesterTgw)
            .formParam("PeerTransitGatewayId", westTgw)
            .formParam("PeerAccountId", "000000000000")
            .formParam("PeerRegion", "us-west-2")
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(200)
            .extract().path("CreateTransitGatewayPeeringAttachmentResponse.transitGatewayPeeringAttachment"
                    + ".transitGatewayAttachmentId");

        given()
            .formParam("Action", "AcceptTransitGatewayPeeringAttachment")
            .formParam("TransitGatewayAttachmentId", id)
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(400);

        given()
            .formParam("Action", "AcceptTransitGatewayPeeringAttachment")
            .formParam("TransitGatewayAttachmentId", id)
            .header("Authorization", WEST)
        .when().post("/")
        .then().statusCode(200);

        given()
            .formParam("Action", "DescribeTransitGatewayPeeringAttachments")
            .formParam("TransitGatewayAttachmentIds.1", id)
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(200)
            .body(ITEM + ".state", equalTo("available"))
            .body(ITEM + ".accepterTgwInfo.region", equalTo("us-west-2"));

        given()
            .formParam("Action", "DeleteTransitGatewayPeeringAttachment")
            .formParam("TransitGatewayAttachmentId", id)
            .header("Authorization", WEST)
        .when().post("/")
        .then().statusCode(200);
    }

    /** Either end of an accepted peering holds its gateway, the same way a VPC attachment does. */
    @Test
    @Order(8)
    void aPeeredGatewayCannotBeDeletedFromEitherEnd() {
        String requester = createTransitGateway(EAST);
        String accepter = createTransitGateway(EAST);
        String id = createPeering(EAST, requester, accepter, "000000000000");
        accept(EAST, id);

        for (String gateway : new String[] {requester, accepter}) {
            given()
                .formParam("Action", "DeleteTransitGateway")
                .formParam("TransitGatewayId", gateway)
                .header("Authorization", EAST)
            .when().post("/")
            .then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("IncorrectState"));

            given()
                .formParam("Action", "DescribeTransitGateways")
                .formParam("TransitGatewayIds.1", gateway)
                .header("Authorization", EAST)
            .when().post("/")
            .then().statusCode(200)
                .body("DescribeTransitGatewaysResponse.transitGatewaySet.item.state", equalTo("available"));
        }

        given()
            .formParam("Action", "DeleteTransitGatewayPeeringAttachment")
            .formParam("TransitGatewayAttachmentId", id)
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(200);

        given()
            .formParam("Action", "DeleteTransitGateway")
            .formParam("TransitGatewayId", requester)
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(200);
    }

    /**
     * Cross-account: the requester cannot accept on the peer's behalf, the peer account sees and
     * accepts the attachment, and each side's owner filters are relative to itself.
     */
    @Test
    @Order(9)
    void crossAccountIsAcceptedOnlyByThePeerAccount() {
        String peerTgw = createTransitGateway(PEER_ACCOUNT);
        String id = createPeering(EAST, requesterTgw, peerTgw, "000000000002");

        given()
            .formParam("Action", "AcceptTransitGatewayPeeringAttachment")
            .formParam("TransitGatewayAttachmentId", id)
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidTransitGatewayAttachmentID.NotFound"));

        given()
            .formParam("Action", "DescribeTransitGatewayPeeringAttachments")
            .formParam("TransitGatewayAttachmentIds.1", id)
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(200)
            .body(ITEM + ".state", equalTo("pendingAcceptance"));

        // The peer sees itself as local and the requester as remote.
        given()
            .formParam("Action", "DescribeTransitGatewayPeeringAttachments")
            .formParam("Filter.1.Name", "local-owner-id")
            .formParam("Filter.1.Value.1", "000000000002")
            .formParam("Filter.2.Name", "remote-owner-id")
            .formParam("Filter.2.Value.1", "000000000000")
            .header("Authorization", PEER_ACCOUNT)
        .when().post("/")
        .then().statusCode(200)
            .body(ITEM + ".transitGatewayAttachmentId", equalTo(id));

        given()
            .formParam("Action", "DescribeTransitGatewayPeeringAttachments")
            .formParam("Filter.1.Name", "local-owner-id")
            .formParam("Filter.1.Value.1", "000000000000")
            .formParam("Filter.2.Name", "remote-owner-id")
            .formParam("Filter.2.Value.1", "000000000002")
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(200)
            .body(ITEM + ".transitGatewayAttachmentId", equalTo(id));

        given()
            .formParam("Action", "AcceptTransitGatewayPeeringAttachment")
            .formParam("TransitGatewayAttachmentId", id)
            .header("Authorization", PEER_ACCOUNT)
        .when().post("/")
        .then().statusCode(200);

        given()
            .formParam("Action", "DescribeTransitGatewayPeeringAttachments")
            .formParam("TransitGatewayAttachmentIds.1", id)
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(200)
            .body(ITEM + ".state", equalTo("available"));

        // The peer's gateway is held by the requester's peering too.
        given()
            .formParam("Action", "DeleteTransitGateway")
            .formParam("TransitGatewayId", peerTgw)
            .header("Authorization", PEER_ACCOUNT)
        .when().post("/")
        .then().statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("IncorrectState"));

        given()
            .formParam("Action", "DeleteTransitGatewayPeeringAttachment")
            .formParam("TransitGatewayAttachmentId", id)
            .header("Authorization", PEER_ACCOUNT)
        .when().post("/")
        .then().statusCode(200);

        given()
            .formParam("Action", "DescribeTransitGatewayPeeringAttachments")
            .formParam("TransitGatewayAttachmentIds.1", id)
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidTransitGatewayAttachmentID.NotFound"));
    }

    /**
     * Anyone can name any gateway as their peer, so a pending peering must not stop the named
     * gateway's owner deleting it. Deleting it declines the peering instead.
     */
    @Test
    @Order(10)
    void aPendingPeeringDoesNotPinTheAccepterGateway() {
        String peerTgw = createTransitGateway(PEER_ACCOUNT);
        String id = createPeering(EAST, requesterTgw, peerTgw, "000000000002");

        given()
            .formParam("Action", "DeleteTransitGateway")
            .formParam("TransitGatewayId", peerTgw)
            .header("Authorization", PEER_ACCOUNT)
        .when().post("/")
        .then().statusCode(200);

        given()
            .formParam("Action", "DescribeTransitGatewayPeeringAttachments")
            .formParam("TransitGatewayAttachmentIds.1", id)
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(200)
            .body(ITEM + ".state", equalTo("rejected"));

        given()
            .formParam("Action", "AcceptTransitGatewayPeeringAttachment")
            .formParam("TransitGatewayAttachmentId", id)
            .header("Authorization", PEER_ACCOUNT)
        .when().post("/")
        .then().statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("IncorrectState"));

        given()
            .formParam("Action", "DeleteTransitGatewayPeeringAttachment")
            .formParam("TransitGatewayAttachmentId", id)
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(200);
    }

    /**
     * Tags belong to the account that set them: each side of a cross-account peering sees its own,
     * and deleting from either side removes both.
     */
    @Test
    @Order(11)
    void crossAccountTagsArePerSideAndGoWithTheAttachment() {
        String peerTgw = createTransitGateway(PEER_ACCOUNT);
        String id = given()
            .formParam("Action", "CreateTransitGatewayPeeringAttachment")
            .formParam("TransitGatewayId", requesterTgw)
            .formParam("PeerTransitGatewayId", peerTgw)
            .formParam("PeerAccountId", "000000000002")
            .formParam("PeerRegion", "us-east-1")
            .formParam("TagSpecification.1.ResourceType", "transit-gateway-attachment")
            .formParam("TagSpecification.1.Tag.1.Key", "side")
            .formParam("TagSpecification.1.Tag.1.Value", "requester")
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(200)
            .extract().path("CreateTransitGatewayPeeringAttachmentResponse.transitGatewayPeeringAttachment"
                    + ".transitGatewayAttachmentId");
        accept(PEER_ACCOUNT, id);

        given()
            .formParam("Action", "CreateTags")
            .formParam("ResourceId.1", id)
            .formParam("Tag.1.Key", "side")
            .formParam("Tag.1.Value", "accepter")
            .header("Authorization", PEER_ACCOUNT)
        .when().post("/")
        .then().statusCode(200);

        for (String[] viewer : new String[][] {{EAST, "requester"}, {PEER_ACCOUNT, "accepter"}}) {
            given()
                .formParam("Action", "DescribeTransitGatewayPeeringAttachments")
                .formParam("TransitGatewayAttachmentIds.1", id)
                .header("Authorization", viewer[0])
            .when().post("/")
            .then().statusCode(200)
                .body(ITEM + ".tagSet.item.value", equalTo(viewer[1]));
        }

        given()
            .formParam("Action", "DeleteTransitGatewayPeeringAttachment")
            .formParam("TransitGatewayAttachmentId", id)
            .header("Authorization", PEER_ACCOUNT)
        .when().post("/")
        .then().statusCode(200);

        for (String viewer : new String[] {EAST, PEER_ACCOUNT}) {
            given()
                .formParam("Action", "DescribeTags")
                .formParam("Filter.1.Name", "resource-id")
                .formParam("Filter.1.Value.1", id)
                .header("Authorization", viewer)
            .when().post("/")
            .then().statusCode(200)
                .body("DescribeTagsResponse.tagSet", equalTo(""));
        }

        given()
            .formParam("Action", "DeleteTransitGateway")
            .formParam("TransitGatewayId", peerTgw)
            .header("Authorization", PEER_ACCOUNT)
        .when().post("/")
        .then().statusCode(200);
    }

    /**
     * An account names someone else's gateway as the peer with itself as the peer account. It must
     * not be able to accept that: an accepted peering pins the gateway, and its real owner could
     * then never delete it.
     */
    @Test
    @Order(12)
    void anAccountCannotAcceptForAGatewayItDoesNotOwn() {
        String victimTgw = createTransitGateway(EAST);
        String attackerTgw = createTransitGateway(PEER_ACCOUNT);
        String id = createPeering(PEER_ACCOUNT, attackerTgw, victimTgw, "000000000002");

        given()
            .formParam("Action", "AcceptTransitGatewayPeeringAttachment")
            .formParam("TransitGatewayAttachmentId", id)
            .header("Authorization", PEER_ACCOUNT)
        .when().post("/")
        .then().statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidTransitGatewayID.NotFound"));

        given()
            .formParam("Action", "DescribeTransitGatewayPeeringAttachments")
            .formParam("TransitGatewayAttachmentIds.1", id)
            .header("Authorization", PEER_ACCOUNT)
        .when().post("/")
        .then().statusCode(200)
            .body(ITEM + ".state", equalTo("pendingAcceptance"));

        given()
            .formParam("Action", "DeleteTransitGateway")
            .formParam("TransitGatewayId", victimTgw)
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(200);

        given()
            .formParam("Action", "DescribeTransitGateways")
            .formParam("TransitGatewayIds.1", victimTgw)
            .header("Authorization", EAST)
        .when().post("/")
        .then().statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidTransitGatewayID.NotFound"));

        given()
            .formParam("Action", "DeleteTransitGatewayPeeringAttachment")
            .formParam("TransitGatewayAttachmentId", id)
            .header("Authorization", PEER_ACCOUNT)
        .when().post("/")
        .then().statusCode(200);
    }

    private static void accept(String auth, String attachmentId) {
        given()
            .formParam("Action", "AcceptTransitGatewayPeeringAttachment")
            .formParam("TransitGatewayAttachmentId", attachmentId)
            .header("Authorization", auth)
        .when().post("/")
        .then().statusCode(200);
    }

    private static String createPeering(String auth, String tgw, String peerTgw, String peerAccount) {
        return given()
            .formParam("Action", "CreateTransitGatewayPeeringAttachment")
            .formParam("TransitGatewayId", tgw)
            .formParam("PeerTransitGatewayId", peerTgw)
            .formParam("PeerAccountId", peerAccount)
            .formParam("PeerRegion", "us-east-1")
            .header("Authorization", auth)
        .when().post("/")
        .then().statusCode(200)
            .extract().path("CreateTransitGatewayPeeringAttachmentResponse.transitGatewayPeeringAttachment"
                    + ".transitGatewayAttachmentId");
    }
}
