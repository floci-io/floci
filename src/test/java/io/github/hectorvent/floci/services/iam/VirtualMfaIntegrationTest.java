package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.Totp;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The virtual MFA device lifecycle over the wire: create, enable against real TOTP codes,
 * list, resync, deactivate, delete.
 *
 * <p>These drive the device the way a client does, reading {@code Base32StringSeed} from the
 * create response and computing codes from it, so a test that enables a device proves the seed it
 * handed is the one the service verifies against.
 *
 * <p>IAM state is shared across the suite, so every test names its own entities uniquely.
 */
@QuarkusTest
class VirtualMfaIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";

    private static RequestSpecification iam(String action) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", action);
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private static String createUser(String userName) {
        iam("CreateUser").formParam("UserName", userName).when().post("/").then().statusCode(200);
        return userName;
    }

    /** Creates a device and returns its serial number and decoded base32 seed. */
    private static Device createDevice(String deviceName) {
        ExtractableResponse<Response> created = iam("CreateVirtualMFADevice")
            .formParam("VirtualMFADeviceName", deviceName)
            .when().post("/").then().statusCode(200).extract();
        String base = "CreateVirtualMFADeviceResponse.CreateVirtualMFADeviceResult.VirtualMFADevice.";
        String encodedSeed = created.path(base + "Base32StringSeed");
        String seed = new String(Base64.getDecoder().decode(encodedSeed.trim()), StandardCharsets.UTF_8);
        return new Device(created.path(base + "SerialNumber"), seed);
    }

    private record Device(String serialNumber, String seed) {
        /** The consecutive pair a real authenticator would show now, as the API asks for. */
        String[] currentCodes() {
            long step = Totp.stepAt(Instant.now());
            return new String[] {
                    Totp.codeAt(seed, step - 1),
                    Totp.codeAt(seed, step)
            };
        }
    }

    private static void enable(String userName, Device device) {
        String[] codes = device.currentCodes();
        iam("EnableMFADevice")
            .formParam("UserName", userName)
            .formParam("SerialNumber", device.serialNumber())
            .formParam("AuthenticationCode1", codes[0])
            .formParam("AuthenticationCode2", codes[1])
        .when().post("/").then().statusCode(200);
    }

    @Test
    void createReturnsAnArnSerialNumberAndAUsableSeed() {
        String name = "mfa-create-" + suffix();
        Device device = createDevice(name);

        assertEquals("arn:aws:iam::000000000000:mfa/" + name, device.serialNumber());
        // 160 bits of base32 is 32 characters, and it has to decode as base32 for an
        // authenticator app to accept it.
        assertEquals(32, device.seed().length());
        assertEquals(20, Totp.base32Decode(device.seed()).length);
    }

    @Test
    void creatingTheSameNameTwiceIsEntityAlreadyExists() {
        String name = "mfa-dup-" + suffix();
        createDevice(name);

        iam("CreateVirtualMFADevice").formParam("VirtualMFADeviceName", name)
        .when().post("/").then()
            .statusCode(409)
            .body("ErrorResponse.Error.Code", equalTo("EntityAlreadyExists"));
    }

    @Test
    void enablingWithCodesFromTheSeedAssignsTheDeviceToTheUser() {
        String userName = createUser("mfa-enable-user-" + suffix());
        Device device = createDevice("mfa-enable-" + suffix());

        enable(userName, device);

        iam("ListMFADevices").formParam("UserName", userName)
        .when().post("/").then()
            .statusCode(200)
            .body("ListMFADevicesResponse.ListMFADevicesResult.MFADevices.member.SerialNumber",
                    equalTo(device.serialNumber()))
            .body("ListMFADevicesResponse.ListMFADevicesResult.MFADevices.member.UserName",
                    equalTo(userName))
            .body("ListMFADevicesResponse.ListMFADevicesResult.IsTruncated", equalTo("false"));
    }

    /**
     * The whole reason the seed is real: a caller that does not hold it cannot enable the device.
     * A stub that accepted any six digits would pass every other test in this file.
     */
    @Test
    void enablingWithCodesThatDoNotComeFromTheSeedIsRejected() {
        String userName = createUser("mfa-badcode-user-" + suffix());
        Device device = createDevice("mfa-badcode-" + suffix());

        iam("EnableMFADevice")
            .formParam("UserName", userName)
            .formParam("SerialNumber", device.serialNumber())
            .formParam("AuthenticationCode1", "000000")
            .formParam("AuthenticationCode2", "111111")
        .when().post("/").then()
            .statusCode(403)
            .body("ErrorResponse.Error.Code", equalTo("InvalidAuthenticationCode"));

        // And the failure left the device unassigned, rather than half-enabling it.
        iam("ListMFADevices").formParam("UserName", userName)
        .when().post("/").then()
            .statusCode(200)
            .body("ListMFADevicesResponse.ListMFADevicesResult.MFADevices", equalTo(""));
    }

    /** AWS asks for "a subsequent authentication code", so one code pasted twice is not a pair. */
    @Test
    void enablingWithTheSameCodeTwiceIsRejected() {
        String userName = createUser("mfa-samecode-user-" + suffix());
        Device device = createDevice("mfa-samecode-" + suffix());
        String code = device.currentCodes()[1];

        iam("EnableMFADevice")
            .formParam("UserName", userName)
            .formParam("SerialNumber", device.serialNumber())
            .formParam("AuthenticationCode1", code)
            .formParam("AuthenticationCode2", code)
        .when().post("/").then()
            .statusCode(403)
            .body("ErrorResponse.Error.Code", equalTo("InvalidAuthenticationCode"));
    }

    /**
     * {@code virtualMFADeviceName} is the one IAM name type with a minimum and a pattern but no
     * maximum length, so a name past the 128 that other IAM names stop at must still be accepted.
     */
    @Test
    void aDeviceNameLongerThanOtherIamNamesAllowIsAccepted() {
        String name = "mfa-long-" + "n".repeat(200) + suffix();

        iam("CreateVirtualMFADevice").formParam("VirtualMFADeviceName", name)
        .when().post("/").then()
            .statusCode(200)
            .body("CreateVirtualMFADeviceResponse.CreateVirtualMFADeviceResult"
                    + ".VirtualMFADevice.SerialNumber", equalTo("arn:aws:iam::000000000000:mfa/" + name));
    }

    @Test
    void aDeviceNameOutsideTheModeledPatternIsAValidationError() {
        iam("CreateVirtualMFADevice").formParam("VirtualMFADeviceName", "not a valid name")
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"));

        iam("CreateVirtualMFADevice").formParam("VirtualMFADeviceName", "")
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    /**
     * A serial number outside {@code serialNumberType}'s 9-to-256 range could never name a real
     * device, so AWS rejects the request shape rather than reporting the device missing.
     */
    @Test
    void aMalformedSerialNumberIsAValidationErrorNotNoSuchEntity() {
        iam("DeleteVirtualMFADevice").formParam("SerialNumber", "abc")
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"));

        iam("ListMFADeviceTags").formParam("SerialNumber", "n".repeat(257))
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"));

        iam("DeleteVirtualMFADevice").formParam("SerialNumber", "has spaces in it")
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    /** UserName is {@code Required: Yes} on EnableMFADevice and ResyncMFADevice. */
    @Test
    void omittingTheRequiredUserNameIsAValidationError() {
        Device device = createDevice("mfa-nouser-" + suffix());

        iam("EnableMFADevice")
            .formParam("SerialNumber", device.serialNumber())
            .formParam("AuthenticationCode1", "123456")
            .formParam("AuthenticationCode2", "654321")
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"));

        iam("ResyncMFADevice")
            .formParam("SerialNumber", device.serialNumber())
            .formParam("AuthenticationCode1", "123456")
            .formParam("AuthenticationCode2", "654321")
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    @Test
    void authenticationCodesThatAreNotSixDigitsAreAValidationError() {
        String userName = createUser("mfa-shortcode-user-" + suffix());
        Device device = createDevice("mfa-shortcode-" + suffix());

        iam("EnableMFADevice")
            .formParam("UserName", userName)
            .formParam("SerialNumber", device.serialNumber())
            .formParam("AuthenticationCode1", "12345")
            .formParam("AuthenticationCode2", "123456")
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    @Test
    void enablingADeviceThatIsAlreadyAssignedIsEntityAlreadyExists() {
        String firstUser = createUser("mfa-taken-a-" + suffix());
        String secondUser = createUser("mfa-taken-b-" + suffix());
        Device device = createDevice("mfa-taken-" + suffix());
        enable(firstUser, device);

        String[] codes = device.currentCodes();
        iam("EnableMFADevice")
            .formParam("UserName", secondUser)
            .formParam("SerialNumber", device.serialNumber())
            .formParam("AuthenticationCode1", codes[0])
            .formParam("AuthenticationCode2", codes[1])
        .when().post("/").then()
            .statusCode(409)
            .body("ErrorResponse.Error.Code", equalTo("EntityAlreadyExists"));
    }

    @Test
    void assignmentStatusSelectsBetweenAssignedAndUnassignedDevices() {
        String userName = createUser("mfa-status-user-" + suffix());
        Device assigned = createDevice("mfa-status-on-" + suffix());
        Device unassigned = createDevice("mfa-status-off-" + suffix());
        enable(userName, assigned);

        List<String> assignedSerials = listSerials("Assigned");
        assertTrue(assignedSerials.contains(assigned.serialNumber()));
        assertTrue(!assignedSerials.contains(unassigned.serialNumber()));

        List<String> unassignedSerials = listSerials("Unassigned");
        assertTrue(unassignedSerials.contains(unassigned.serialNumber()));
        assertTrue(!unassignedSerials.contains(assigned.serialNumber()));

        // Omitted AssignmentStatus defaults to Any, so both appear.
        List<String> all = listSerials(null);
        assertTrue(all.contains(assigned.serialNumber()));
        assertTrue(all.contains(unassigned.serialNumber()));
    }

    private static List<String> listSerials(String assignmentStatus) {
        RequestSpecification request = iam("ListVirtualMFADevices").formParam("MaxItems", "1000");
        if (assignmentStatus != null) {
            request = request.formParam("AssignmentStatus", assignmentStatus);
        }
        return request.when().post("/").then().statusCode(200)
            .extract().xmlPath().getList("ListVirtualMFADevicesResponse.ListVirtualMFADevicesResult"
                    + ".VirtualMFADevices.member.SerialNumber", String.class);
    }

    @Test
    void anUnknownAssignmentStatusIsAValidationError() {
        iam("ListVirtualMFADevices").formParam("AssignmentStatus", "Sometimes")
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    /**
     * The listing carries the assigned user but never the seed: it is the account-wide reader,
     * so returning seeds there would hand out every device's shared secret at once.
     */
    @Test
    void listingCarriesTheUserButNeverTheSeed() {
        String userName = createUser("mfa-noseed-user-" + suffix());
        Device device = createDevice("mfa-noseed-" + suffix());
        enable(userName, device);

        String body = iam("ListVirtualMFADevices").formParam("MaxItems", "1000")
            .when().post("/").then().statusCode(200).extract().asString();

        assertTrue(body.contains(device.serialNumber()));
        assertTrue(body.contains(userName), "the assigned user is part of the modeled shape");
        assertTrue(!body.contains(device.seed()), "but the seed is not");
        assertTrue(!body.contains("Base32StringSeed"));
    }

    @Test
    void resyncAcceptsCodesFromTheSeedAndRejectsOthers() {
        String userName = createUser("mfa-resync-user-" + suffix());
        Device device = createDevice("mfa-resync-" + suffix());
        enable(userName, device);

        String[] codes = device.currentCodes();
        iam("ResyncMFADevice")
            .formParam("UserName", userName)
            .formParam("SerialNumber", device.serialNumber())
            .formParam("AuthenticationCode1", codes[0])
            .formParam("AuthenticationCode2", codes[1])
        .when().post("/").then().statusCode(200);

        iam("ResyncMFADevice")
            .formParam("UserName", userName)
            .formParam("SerialNumber", device.serialNumber())
            .formParam("AuthenticationCode1", "000000")
            .formParam("AuthenticationCode2", "111111")
        .when().post("/").then()
            .statusCode(403)
            .body("ErrorResponse.Error.Code", equalTo("InvalidAuthenticationCode"));
    }

    /**
     * Resync exists for a device whose clock has drifted, so it has to accept a pair the enable
     * path would refuse, otherwise it could never fix the condition it is named for.
     */
    @Test
    void resyncAcceptsADriftedPairThatEnableWouldReject() {
        String userName = createUser("mfa-drift-user-" + suffix());
        Device device = createDevice("mfa-drift-" + suffix());
        long step = Totp.stepAt(Instant.now());
        String drifted1 = Totp.codeAt(device.seed(), step - 6);
        String drifted2 = Totp.codeAt(device.seed(), step - 5);

        iam("EnableMFADevice")
            .formParam("UserName", userName)
            .formParam("SerialNumber", device.serialNumber())
            .formParam("AuthenticationCode1", drifted1)
            .formParam("AuthenticationCode2", drifted2)
        .when().post("/").then()
            .statusCode(403)
            .body("ErrorResponse.Error.Code", equalTo("InvalidAuthenticationCode"));

        enable(userName, device);

        iam("ResyncMFADevice")
            .formParam("UserName", userName)
            .formParam("SerialNumber", device.serialNumber())
            .formParam("AuthenticationCode1", drifted1)
            .formParam("AuthenticationCode2", drifted2)
        .when().post("/").then().statusCode(200);
    }

    @Test
    void deactivateDetachesTheDeviceButKeepsIt() {
        String userName = createUser("mfa-deact-user-" + suffix());
        Device device = createDevice("mfa-deact-" + suffix());
        enable(userName, device);

        iam("DeactivateMFADevice")
            .formParam("UserName", userName)
            .formParam("SerialNumber", device.serialNumber())
        .when().post("/").then().statusCode(200);

        iam("ListMFADevices").formParam("UserName", userName)
        .when().post("/").then()
            .statusCode(200)
            .body("ListMFADevicesResponse.ListMFADevicesResult.MFADevices", equalTo(""));
        // The device itself survives, unassigned: AWS removes the association, not the device.
        assertTrue(listSerials("Unassigned").contains(device.serialNumber()));
    }

    /** The same seed still works afterwards, so re-enabling does not require re-provisioning. */
    @Test
    void aDeactivatedDeviceCanBeEnabledAgainWithTheSameSeed() {
        String userName = createUser("mfa-reenable-user-" + suffix());
        Device device = createDevice("mfa-reenable-" + suffix());
        enable(userName, device);

        iam("DeactivateMFADevice").formParam("UserName", userName)
            .formParam("SerialNumber", device.serialNumber())
        .when().post("/").then().statusCode(200);

        enable(userName, device);

        iam("ListMFADevices").formParam("UserName", userName)
        .when().post("/").then().statusCode(200)
            .body("ListMFADevicesResponse.ListMFADevicesResult.MFADevices.member.SerialNumber",
                    equalTo(device.serialNumber()));
    }

    @Test
    void deactivatingADeviceAssignedToSomeoneElseIsNoSuchEntity() {
        String owner = createUser("mfa-wrong-owner-" + suffix());
        String other = createUser("mfa-wrong-other-" + suffix());
        Device device = createDevice("mfa-wrong-" + suffix());
        enable(owner, device);

        iam("DeactivateMFADevice")
            .formParam("UserName", other)
            .formParam("SerialNumber", device.serialNumber())
        .when().post("/").then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    @Test
    void deletingAnAssignedDeviceIsDeleteConflict() {
        String userName = createUser("mfa-delconflict-user-" + suffix());
        Device device = createDevice("mfa-delconflict-" + suffix());
        enable(userName, device);

        iam("DeleteVirtualMFADevice").formParam("SerialNumber", device.serialNumber())
        .when().post("/").then()
            .statusCode(409)
            .body("ErrorResponse.Error.Code", equalTo("DeleteConflict"));
    }

    @Test
    void deleteRemovesAnUnassignedDevice() {
        Device device = createDevice("mfa-delete-" + suffix());

        iam("DeleteVirtualMFADevice").formParam("SerialNumber", device.serialNumber())
        .when().post("/").then().statusCode(200);

        assertTrue(!listSerials(null).contains(device.serialNumber()));

        iam("DeleteVirtualMFADevice").formParam("SerialNumber", device.serialNumber())
        .when().post("/").then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    /** DeleteUser's prerequisite chain: AWS refuses while the user still holds a device. */
    @Test
    void aUserWithAnEnabledDeviceCannotBeDeleted() {
        String userName = createUser("mfa-userdel-" + suffix());
        Device device = createDevice("mfa-userdel-dev-" + suffix());
        enable(userName, device);

        iam("DeleteUser").formParam("UserName", userName)
        .when().post("/").then()
            .statusCode(409)
            .body("ErrorResponse.Error.Code", equalTo("DeleteConflict"));

        iam("DeactivateMFADevice").formParam("UserName", userName)
            .formParam("SerialNumber", device.serialNumber())
        .when().post("/").then().statusCode(200);

        iam("DeleteUser").formParam("UserName", userName)
        .when().post("/").then().statusCode(200);
    }

    /**
     * A device records its holder by name, so a rename has to carry the assignment. If it does
     * not, DeleteUser sees no device under the new name and lets the user go, stranding a device
     * that can then never be deactivated (that resolves the user first) nor deleted (deletion
     * refuses while it is assigned).
     */
    @Test
    void renamingAUserCarriesItsMfaDeviceAssignment() {
        String original = createUser("mfa-rename-" + suffix());
        String renamed = "mfa-renamed-" + suffix();
        Device device = createDevice("mfa-rename-dev-" + suffix());
        enable(original, device);

        iam("UpdateUser").formParam("UserName", original).formParam("NewUserName", renamed)
        .when().post("/").then().statusCode(200);

        iam("ListMFADevices").formParam("UserName", renamed)
        .when().post("/").then()
            .statusCode(200)
            .body("ListMFADevicesResponse.ListMFADevicesResult.MFADevices.member.SerialNumber",
                    equalTo(device.serialNumber()))
            .body("ListMFADevicesResponse.ListMFADevicesResult.MFADevices.member.UserName",
                    equalTo(renamed));

        // The user is still held by the device, so deleting them is still refused.
        iam("DeleteUser").formParam("UserName", renamed)
        .when().post("/").then()
            .statusCode(409)
            .body("ErrorResponse.Error.Code", equalTo("DeleteConflict"));

        // And the device is reachable under the new name, so it can still be freed.
        iam("DeactivateMFADevice").formParam("UserName", renamed)
            .formParam("SerialNumber", device.serialNumber())
        .when().post("/").then().statusCode(200);
        iam("DeleteUser").formParam("UserName", renamed).when().post("/").then().statusCode(200);
    }

    /**
     * {@code virtualMFADeviceName} has no maximum length but the serial number it mints is capped
     * at 256, so a long-but-valid name could otherwise produce a device that every other operation
     * rejects. Creation refuses it rather than handing back an unusable serial.
     */
    @Test
    void aNameThatWouldMintAnUnusableSerialNumberIsRejected() {
        iam("CreateVirtualMFADevice").formParam("VirtualMFADeviceName", "n".repeat(250))
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"));

        // A name just inside the limit still works, so the check is not simply capping at 128.
        String name = "m".repeat(200) + suffix();
        String serial = iam("CreateVirtualMFADevice").formParam("VirtualMFADeviceName", name)
            .when().post("/").then().statusCode(200)
            .extract().path("CreateVirtualMFADeviceResponse.CreateVirtualMFADeviceResult"
                    + ".VirtualMFADevice.SerialNumber");
        assertTrue(serial.length() <= 256);
        // And the serial it handed back is one the other operations accept.
        iam("DeleteVirtualMFADevice").formParam("SerialNumber", serial)
        .when().post("/").then().statusCode(200);
    }

    /** AWS models Marker and MaxItems on ListMFADeviceTags, so a one-item page must paginate. */
    @Test
    void listMfaDeviceTagsPaginates() {
        Device device = createDevice("mfa-tagpage-" + suffix());
        iam("TagMFADevice").formParam("SerialNumber", device.serialNumber())
            .formParam("Tags.member.1.Key", "aaa").formParam("Tags.member.1.Value", "1")
            .formParam("Tags.member.2.Key", "bbb").formParam("Tags.member.2.Value", "2")
        .when().post("/").then().statusCode(200);

        ExtractableResponse<Response> first = iam("ListMFADeviceTags")
            .formParam("SerialNumber", device.serialNumber()).formParam("MaxItems", "1")
            .when().post("/").then().statusCode(200)
            .body("ListMFADeviceTagsResponse.ListMFADeviceTagsResult.IsTruncated", equalTo("true"))
            .body("ListMFADeviceTagsResponse.ListMFADeviceTagsResult.Tags.member.Key", equalTo("aaa"))
            .extract();
        String marker = first.path("ListMFADeviceTagsResponse.ListMFADeviceTagsResult.Marker");
        assertNotNull(marker, "a truncated tag page carries a Marker");

        iam("ListMFADeviceTags").formParam("SerialNumber", device.serialNumber())
            .formParam("MaxItems", "1").formParam("Marker", marker)
        .when().post("/").then()
            .statusCode(200)
            .body("ListMFADeviceTagsResponse.ListMFADeviceTagsResult.Tags.member.Key", equalTo("bbb"))
            .body("ListMFADeviceTagsResponse.ListMFADeviceTagsResult.IsTruncated", equalTo("false"));
    }

    /**
     * The MFA actions were missing from {@code AwsQueryController.IAM_ACTIONS} as well, so an
     * unauthenticated client reached SQS instead of IAM. Pinned here because the two families
     * regressed the same way and nothing else covers this routing for MFA.
     */
    @Test
    void mfaActionsRouteViaTheActionFallbackWhenAuthHeaderAbsent() {
        String name = "mfa-fallback-" + suffix();

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateVirtualMFADevice")
            .formParam("VirtualMFADeviceName", name)
        .when().post("/").then()
            .statusCode(200)
            .body("CreateVirtualMFADeviceResponse.CreateVirtualMFADeviceResult"
                    + ".VirtualMFADevice.SerialNumber",
                    equalTo("arn:aws:iam::000000000000:mfa/" + name));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteVirtualMFADevice")
            .formParam("SerialNumber", "arn:aws:iam::000000000000:mfa/" + name)
        .when().post("/").then().statusCode(200);
    }

    @Test
    void tagsRoundTripAndAreListedSortedByKey() {
        Device device = createDevice("mfa-tags-" + suffix());

        iam("TagMFADevice").formParam("SerialNumber", device.serialNumber())
            .formParam("Tags.member.1.Key", "zone").formParam("Tags.member.1.Value", "b")
            .formParam("Tags.member.2.Key", "env").formParam("Tags.member.2.Value", "test")
        .when().post("/").then().statusCode(200);

        iam("ListMFADeviceTags").formParam("SerialNumber", device.serialNumber())
        .when().post("/").then()
            .statusCode(200)
            .body("ListMFADeviceTagsResponse.ListMFADeviceTagsResult.Tags.member[0].Key", equalTo("env"))
            .body("ListMFADeviceTagsResponse.ListMFADeviceTagsResult.Tags.member[1].Key", equalTo("zone"));

        iam("UntagMFADevice").formParam("SerialNumber", device.serialNumber())
            .formParam("TagKeys.member.1", "zone")
        .when().post("/").then().statusCode(200);

        iam("ListMFADeviceTags").formParam("SerialNumber", device.serialNumber())
        .when().post("/").then()
            .statusCode(200)
            .body("ListMFADeviceTagsResponse.ListMFADeviceTagsResult.Tags.member.Key", equalTo("env"));
    }

    @Test
    void tagsGivenAtCreationAreKept() {
        String name = "mfa-createtag-" + suffix();
        iam("CreateVirtualMFADevice").formParam("VirtualMFADeviceName", name)
            .formParam("Tags.member.1.Key", "owner").formParam("Tags.member.1.Value", "platform")
        .when().post("/").then().statusCode(200);

        iam("ListMFADeviceTags").formParam("SerialNumber", "arn:aws:iam::000000000000:mfa/" + name)
        .when().post("/").then()
            .statusCode(200)
            .body("ListMFADeviceTagsResponse.ListMFADeviceTagsResult.Tags.member.Key", equalTo("owner"))
            .body("ListMFADeviceTagsResponse.ListMFADeviceTagsResult.Tags.member.Value", equalTo("platform"));
    }

    @Test
    void operationsAgainstAnUnknownSerialNumberAreNoSuchEntity() {
        String missing = "arn:aws:iam::000000000000:mfa/mfa-missing-" + suffix();
        String userName = createUser("mfa-missing-user-" + suffix());

        iam("EnableMFADevice").formParam("UserName", userName).formParam("SerialNumber", missing)
            .formParam("AuthenticationCode1", "123456").formParam("AuthenticationCode2", "654321")
        .when().post("/").then().statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));

        iam("ListMFADeviceTags").formParam("SerialNumber", missing)
        .when().post("/").then().statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    @Test
    void theAccountSummaryCountsDevicesAndTheirAssignments() {
        String userName = createUser("mfa-summary-user-" + suffix());
        long before = summaryValue("MFADevices");
        long inUseBefore = summaryValue("MFADevicesInUse");

        Device assigned = createDevice("mfa-summary-on-" + suffix());
        createDevice("mfa-summary-off-" + suffix());
        enable(userName, assigned);

        assertEquals(before + 2, summaryValue("MFADevices"));
        assertEquals(inUseBefore + 1, summaryValue("MFADevicesInUse"));
    }

    private static long summaryValue(String key) {
        String value = iam("GetAccountSummary").when().post("/").then().statusCode(200)
            .extract().path("GetAccountSummaryResponse.GetAccountSummaryResult.SummaryMap.entry"
                    + ".find { it.key == '" + key + "' }.value");
        assertNotNull(value, key + " missing from the summary map");
        return Long.parseLong(value);
    }

    @Test
    void listVirtualMfaDevicesPaginatesWithMaxItemsAndMarker() {
        String prefix = "mfa-page-" + suffix();
        createDevice(prefix + "-a");
        createDevice(prefix + "-b");

        ExtractableResponse<Response> firstPage = iam("ListVirtualMFADevices").formParam("MaxItems", "1")
            .when().post("/").then().statusCode(200)
            .body("ListVirtualMFADevicesResponse.ListVirtualMFADevicesResult.IsTruncated", equalTo("true"))
            .extract();
        String marker = firstPage.path("ListVirtualMFADevicesResponse."
                + "ListVirtualMFADevicesResult.Marker");
        String firstSerial = firstPage.path("ListVirtualMFADevicesResponse."
                + "ListVirtualMFADevicesResult.VirtualMFADevices.member.SerialNumber");
        assertNotNull(marker, "a truncated page carries a Marker");

        String secondSerial = iam("ListVirtualMFADevices").formParam("MaxItems", "1")
            .formParam("Marker", marker)
            .when().post("/").then().statusCode(200)
            .extract().path("ListVirtualMFADevicesResponse."
                    + "ListVirtualMFADevicesResult.VirtualMFADevices.member.SerialNumber");
        assertTrue(!firstSerial.equals(secondSerial), "the marker advanced past the first device");
    }

    @Test
    void maxItemsOutsideTheModeledRangeIsAValidationError() {
        iam("ListVirtualMFADevices").formParam("MaxItems", "0")
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"));

        iam("ListVirtualMFADevices").formParam("MaxItems", "1001")
        .when().post("/").then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    @Test
    void enablingMoreThanTheDevicesPerUserQuotaIsLimitExceeded() {
        String userName = createUser("mfa-quota-user-" + suffix());
        for (int i = 0; i < 8; i++) {
            enable(userName, createDevice("mfa-quota-" + i + "-" + suffix()));
        }
        Device ninth = createDevice("mfa-quota-9-" + suffix());

        String[] codes = ninth.currentCodes();
        iam("EnableMFADevice")
            .formParam("UserName", userName)
            .formParam("SerialNumber", ninth.serialNumber())
            .formParam("AuthenticationCode1", codes[0])
            .formParam("AuthenticationCode2", codes[1])
        .when().post("/").then()
            .statusCode(409)
            .body("ErrorResponse.Error.Code", equalTo("LimitExceeded"));
    }
}
