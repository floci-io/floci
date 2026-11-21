package io.floci.aws.services.iam;

import io.floci.aws.core.common.Totp;
import io.floci.aws.testing.ValidateSignaturesProfile;
import io.floci.aws.testing.VirtualMfaFixtures;
import io.floci.aws.testing.VirtualMfaFixtures.Device;
import io.floci.aws.testutil.StsRequests.Credentials;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testutil.StsRequests.request;
import static org.hamcrest.Matchers.equalTo;

/**
 * The two sentences a failed MFA claim earns on STS, which AWS words by the half of the claim that
 * failed: a serial no device of the caller's answers to, and a code that device did not produce.
 * Both are 403 {@code AccessDenied}, so the sentence is the only thing that tells a caller which
 * half to look at, and both are held here byte for byte, the whitespace inside them included.
 *
 * <p>The requests are signed because the claim is read against the caller the key belongs to: a
 * device is the user's, and an unsigned request names no user to own one.
 *
 * <p>{@code docs/services/iam.md} carries the recordings.
 */
@QuarkusTest
@TestProfile(ValidateSignaturesProfile.class)
class StsMfaRefusalWordingIntegrationTest {

    private static final String ACCOUNT = "000000000000";

    private static final Credentials ROOT = new Credentials("test", "test", null);

    private static final String CODE = "ErrorResponse.Error.Code";
    private static final String MESSAGE = "ErrorResponse.Error.Message";

    /** Recorded 2026-10-29. The two spaces after {@code MFA code.} are AWS's own. */
    private static final String SERIAL_FAILED =
            "MultiFactorAuthentication failed, unable to validate MFA code.  "
                    + "Please verify your MFA serial number is valid and associated with this user.";

    /** Recorded 2026-11-13. The sentence ends in a single space, which is also AWS's own. */
    private static final String CODE_FAILED =
            "MultiFactorAuthentication failed with invalid MFA one time pass code. ";

    /** What a session request's token code is accepted within, either side of the current step. */
    private static final int TOKEN_DRIFT_STEPS = 1;

    private final List<Runnable> cleanup = new ArrayList<>();

    @AfterEach
    void cleanup() {
        AssertionError failures = null;
        for (int i = cleanup.size() - 1; i >= 0; i--) {
            try {
                cleanup.get(i).run();
            } catch (AssertionError failure) {
                if (failures == null) {
                    failures = new AssertionError("Fixture cleanup failed");
                }
                failures.addSuppressed(failure);
            }
        }
        if (failures != null) {
            throw failures;
        }
    }

    /** A serial nothing of this caller's answers to names the serial half. */
    @Test
    void aSerialTheCallerHoldsNoDeviceForNamesTheSerialHalf() throws Exception {
        Credentials user = createUser();
        request(user, "sts", "GetSessionToken",
                Map.of("SerialNumber", "arn:aws:iam::" + ACCOUNT + ":mfa/no-such-device",
                        "TokenCode", "123456"))
                .then().statusCode(403)
                .body(CODE, equalTo("AccessDenied"))
                .body(MESSAGE, equalTo(SERIAL_FAILED));
    }

    /** A code the caller's own device did not produce names the code half instead. */
    @Test
    void aCodeTheDeviceDidNotProduceNamesTheCodeHalf() throws Exception {
        Credentials user = createUser();
        Device device = enableDevice(user);
        request(user, "sts", "GetSessionToken",
                Map.of("SerialNumber", device.serialNumber(), "TokenCode",
                        VirtualMfaFixtures.codeTheDeviceCannotProduce(
                                device.seed(), TOKEN_DRIFT_STEPS)))
                .then().statusCode(403)
                .body(CODE, equalTo("AccessDenied"))
                .body(MESSAGE, equalTo(CODE_FAILED));
    }

    /**
     * {@code AssumeRole} reads the same pair through the same seam and carries no recording of its
     * own, so it answers the same sentence, which is a reading rather than a measurement.
     */
    @Test
    void assumeRoleAnswersTheSameSentence() throws Exception {
        Credentials user = createUser();
        Device device = enableDevice(user);
        request(user, "sts", "AssumeRole",
                Map.of("RoleArn", "arn:aws:iam::" + ACCOUNT + ":role/mfa-wording-role",
                        "RoleSessionName", "mfa-wording",
                        "SerialNumber", device.serialNumber(), "TokenCode",
                        VirtualMfaFixtures.codeTheDeviceCannotProduce(
                                device.seed(), TOKEN_DRIFT_STEPS)))
                .then().statusCode(403)
                .body(CODE, equalTo("AccessDenied"))
                .body(MESSAGE, equalTo(CODE_FAILED));
    }

    /** The control: the device's own code still mints a session. */
    @Test
    void aRealCodeStillMintsASession() throws Exception {
        Credentials user = createUser();
        Device device = enableDevice(user);
        Response minted = request(user, "sts", "GetSessionToken",
                Map.of("SerialNumber", device.serialNumber(),
                        "TokenCode", Totp.codeAt(device.seed(), Totp.stepAt(Instant.now()))));
        minted.then().statusCode(200);
    }

    /**
     * A user holding one access key, created as root through the same signed transport these tests
     * use. The enforcement suites' shared fixture speaks the header transport instead, which this
     * profile refuses, so the two steps are taken here.
     */
    private Credentials createUser() throws Exception {
        String name = "mfa-wording-" + UUID.randomUUID();
        request(ROOT, "iam", "CreateUser", Map.of("UserName", name)).then().statusCode(200);
        cleanup.add(() -> remove("DeleteUser", Map.of("UserName", name)));
        Response created = request(ROOT, "iam", "CreateAccessKey", Map.of("UserName", name));
        created.then().statusCode(200);
        String prefix = "CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.";
        String key = created.xmlPath().getString(prefix + "AccessKeyId");
        cleanup.add(() -> remove("DeleteAccessKey", Map.of("UserName", name, "AccessKeyId", key)));
        return new Credentials(key, created.xmlPath().getString(prefix + "SecretAccessKey"), null);
    }

    /** A virtual device of that user's, enabled with two of its own consecutive codes. */
    private Device enableDevice(Credentials user) throws Exception {
        String userName = userNameOf(user);
        Response created = request(ROOT, "iam", "CreateVirtualMFADevice",
                Map.of("VirtualMFADeviceName", userName + "-device"));
        created.then().statusCode(200);
        Device device = VirtualMfaFixtures.deviceFrom(created.xmlPath());
        List<String> codes = VirtualMfaFixtures.twoConsecutiveCodes(device.seed());
        request(ROOT, "iam", "EnableMFADevice",
                Map.of("UserName", userName, "SerialNumber", device.serialNumber(),
                        "AuthenticationCode1", codes.get(0),
                        "AuthenticationCode2", codes.get(1))).then().statusCode(200);
        // Cleanup runs last-registered first, and an assigned device cannot be deleted at all:
        // the delete is registered first so the deactivate runs ahead of it.
        cleanup.add(() -> remove("DeleteVirtualMFADevice",
                Map.of("SerialNumber", device.serialNumber())));
        cleanup.add(() -> remove("DeactivateMFADevice",
                Map.of("UserName", userName, "SerialNumber", device.serialNumber())));
        return device;
    }

    /** The user the caller's key belongs to, which is who a device has to be enabled for. */
    private String userNameOf(Credentials user) throws Exception {
        Response caller = request(user, "sts", "GetCallerIdentity", Map.of());
        caller.then().statusCode(200);
        String arn = caller.xmlPath()
                .getString("GetCallerIdentityResponse.GetCallerIdentityResult.Arn");
        return arn.substring(arn.lastIndexOf('/') + 1);
    }

    private void remove(String action, Map<String, String> params) {
        try {
            request(ROOT, "iam", action, params).then().statusCode(200);
        } catch (Exception failure) {
            throw new AssertionError("Failed cleanup " + action, failure);
        }
    }
}
