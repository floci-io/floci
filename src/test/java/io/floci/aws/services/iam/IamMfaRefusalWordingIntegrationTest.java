package io.floci.aws.services.iam;

import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.VirtualMfaFixtures;
import io.floci.aws.testing.VirtualMfaFixtures.Device;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * What IAM answers for a device it will not take: the code that is not one of the device's outputs,
 * and the second device at a path and name another already has. Both sentences are recorded against
 * a real account and both are wholly fixed text.
 *
 * <p>{@code docs/services/iam.md} carries the recordings.
 */
@QuarkusTest
class IamMfaRefusalWordingIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261118/us-east-1/iam/aws4_request";

    private static final String CODE = "ErrorResponse.Error.Code";
    private static final String MESSAGE = "ErrorResponse.Error.Message";

    /** Recorded on EnableMFADevice and ResyncMFADevice, 2026-11-05. */
    private static final String BAD_CODE = "Authentication code for device is not valid.";

    /** Recorded on CreateVirtualMFADevice for a second device at the same path and name. */
    private static final String DUPLICATE = "MFA device already exists.";

    /** What {@code EnableMFADevice} accepts either side of the current step. */
    private static final int ENABLE_DRIFT_STEPS = 1;

    /** What {@code ResyncMFADevice} accepts, the operation being for a device that has drifted. */
    private static final int RESYNC_DRIFT_STEPS = 10;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private static RequestSpecification iam(String action) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", action);
    }

    private static String unique(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    private String createUser() {
        String userName = unique("mfa-wording-user");
        iam("CreateUser").formParam("UserName", userName).when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeleteUser").formParam("UserName", userName)
                .when().post("/").then().statusCode(200));
        return userName;
    }

    /** A device of this account's, with the seed its creation handed back. */
    private Device createDevice(String deviceName) {
        Device device = VirtualMfaFixtures.deviceFrom(
                iam("CreateVirtualMFADevice").formParam("VirtualMFADeviceName", deviceName)
                        .when().post("/").then().statusCode(200).extract().xmlPath());
        cleanup.register(() -> iam("DeleteVirtualMFADevice")
                .formParam("SerialNumber", device.serialNumber())
                .when().post("/").then().statusCode(200));
        return device;
    }

    /**
     * Enables {@code device} for {@code userName} with two of its own consecutive codes, and
     * registers the deactivate. Cleanup runs last-registered first, and neither the device nor the
     * user it is assigned to can be deleted while the assignment stands, so this registration has
     * to come after both deletes to run before them.
     */
    private void enableDevice(String userName, Device device) {
        List<String> codes = VirtualMfaFixtures.twoConsecutiveCodes(device.seed());
        iam("EnableMFADevice").formParam("UserName", userName)
                .formParam("SerialNumber", device.serialNumber())
                .formParam("AuthenticationCode1", codes.get(0))
                .formParam("AuthenticationCode2", codes.get(1))
                .when().post("/").then().statusCode(200);
        cleanup.register(() -> iam("DeactivateMFADevice").formParam("UserName", userName)
                .formParam("SerialNumber", device.serialNumber())
                .when().post("/").then().statusCode(200));
    }

    /** Codes the device never produced are refused with AWS's own sentence, and nothing changes. */
    @Test
    void enablingWithCodesTheDeviceNeverProducedAnswersTheRecordedSentence() {
        String userName = createUser();
        Device device = createDevice(unique("mfa-wording-device"));
        List<String> wrong = VirtualMfaFixtures.codesTheDeviceCannotProduce(
                device.seed(), ENABLE_DRIFT_STEPS, 2);
        iam("EnableMFADevice").formParam("UserName", userName)
                .formParam("SerialNumber", device.serialNumber())
                .formParam("AuthenticationCode1", wrong.get(0))
                .formParam("AuthenticationCode2", wrong.get(1))
                .when().post("/").then().statusCode(403)
                .body(CODE, equalTo("InvalidAuthenticationCode"))
                .body(MESSAGE, equalTo(BAD_CODE));

        iam("ListVirtualMFADevices").formParam("AssignmentStatus", "Unassigned")
                .when().post("/").then().statusCode(200);
    }

    /** Resyncing answers the same sentence, the recordings covering both operations. */
    @Test
    void resyncingWithCodesTheDeviceNeverProducedAnswersTheSameSentence() {
        String userName = createUser();
        Device device = createDevice(unique("mfa-wording-resync"));
        enableDevice(userName, device);
        List<String> wrong = VirtualMfaFixtures.codesTheDeviceCannotProduce(
                device.seed(), RESYNC_DRIFT_STEPS, 2);

        iam("ResyncMFADevice").formParam("UserName", userName)
                .formParam("SerialNumber", device.serialNumber())
                .formParam("AuthenticationCode1", wrong.get(0))
                .formParam("AuthenticationCode2", wrong.get(1))
                .when().post("/").then().statusCode(403)
                .body(CODE, equalTo("InvalidAuthenticationCode"))
                .body(MESSAGE, equalTo(BAD_CODE));
    }

    /** A second device at the same path and name, which AWS answers in five words. */
    @Test
    void aSecondDeviceAtTheSamePathAndNameAnswersTheRecordedSentence() {
        String deviceName = unique("mfa-wording-duplicate");
        createDevice(deviceName);
        iam("CreateVirtualMFADevice").formParam("VirtualMFADeviceName", deviceName)
                .when().post("/").then().statusCode(409)
                .body(CODE, equalTo("EntityAlreadyExists"))
                .body(MESSAGE, equalTo(DUPLICATE));
    }

    /** The control: two real codes still enable the device, and it is the user's afterwards. */
    @Test
    void twoRealCodesStillEnableTheDevice() {
        String userName = createUser();
        Device device = createDevice(unique("mfa-wording-control"));
        enableDevice(userName, device);
        iam("ListMFADevices").formParam("UserName", userName)
                .when().post("/").then().statusCode(200)
                .body("ListMFADevicesResponse.ListMFADevicesResult.MFADevices.member.SerialNumber",
                        equalTo(device.serialNumber()));
    }
}
