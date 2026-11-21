package io.floci.aws.testing;

import io.floci.aws.core.common.Totp;
import io.restassured.path.xml.XmlPath;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The parts of a virtual MFA device a test needs after creating one: the seed its creation handed
 * back, the pair of consecutive codes an enable or a resync takes, and codes the device cannot
 * produce. The transport stays with each test, which is the only part that differs between the
 * classes using this: one speaks the Authorization header, another signs every request.
 */
public final class VirtualMfaFixtures {

    private static final String DEVICE_MEMBER =
            "CreateVirtualMFADeviceResponse.CreateVirtualMFADeviceResult.VirtualMFADevice.";

    /** The accepted window either side of the current step, plus a step of margin. */
    private static final int MARGIN_STEPS = 1;

    private VirtualMfaFixtures() {
    }

    /** A device and the seed it answers codes from, read off a {@code CreateVirtualMFADevice}. */
    public record Device(String serialNumber, String seed) {
    }

    /**
     * What a {@code CreateVirtualMFADevice} response describes. {@code Base32StringSeed} is sent
     * the way AWS sends it, base64 over the base32 text, so it is decoded once here.
     */
    public static Device deviceFrom(XmlPath created) {
        return new Device(created.getString(DEVICE_MEMBER + "SerialNumber"),
                new String(Base64.getDecoder()
                        .decode(created.getString(DEVICE_MEMBER + "Base32StringSeed").trim()),
                        StandardCharsets.UTF_8));
    }

    /**
     * The two codes an enable or a resync takes, oldest first: the step before the current one and
     * the current one, which is what a caller reading two codes off an authenticator sends.
     */
    public static List<String> twoConsecutiveCodes(String seed) {
        long step = Totp.stepAt(Instant.now());
        return List.of(Totp.codeAt(seed, step - 1), Totp.codeAt(seed, step));
    }

    /**
     * A six-digit code the device cannot produce for any step the operation would accept, with a
     * step of margin either side in case the window moves while the request is in flight.
     *
     * <p>A literal will not do. Every six-digit string is a code that some step produces, so a
     * fixed {@code 000000} is a code the device may well answer with at the moment the test runs,
     * and the refusal it is checking would not happen.
     */
    public static String codeTheDeviceCannotProduce(String seed, int driftSteps) {
        return codesTheDeviceCannotProduce(seed, driftSteps, 1).getFirst();
    }

    /** The same, {@code count} of them, each different from the others. */
    public static List<String> codesTheDeviceCannotProduce(String seed, int driftSteps, int count) {
        long step = Totp.stepAt(Instant.now());
        Set<String> accepted = new HashSet<>();
        for (long s = step - driftSteps - MARGIN_STEPS; s <= step + driftSteps + MARGIN_STEPS; s++) {
            accepted.add(Totp.codeAt(seed, s));
        }
        List<String> unused = new ArrayList<>();
        for (int candidate = 0; candidate < 1_000_000 && unused.size() < count; candidate++) {
            String code = "%06d".formatted(candidate);
            if (!accepted.contains(code)) {
                unused.add(code);
            }
        }
        if (unused.size() < count) {
            throw new IllegalStateException("No unused six-digit code left for seed window");
        }
        return List.copyOf(unused);
    }
}
