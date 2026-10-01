package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.UserDataPipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class Ec2UserDataDecoder {

    private static final int MAX_USER_DATA_BYTES = 16 * 1024;
    private static final int MAX_ENCODED_USER_DATA_LENGTH = ((MAX_USER_DATA_BYTES + 2) / 3) * 4;

    private Ec2UserDataDecoder() {
    }

    public static String decodeIfMissing(String userData, String encodedUserData) {
        return userData != null ? userData : decode(encodedUserData);
    }

    public static String decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        if (encoded.length() > MAX_ENCODED_USER_DATA_LENGTH) {
            throw new AwsException("InvalidParameterValue", "UserData exceeds the 16 KiB limit.", 400);
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameterValue", "UserData is not valid base64 content.", 400);
        }
        if (decoded.length > MAX_USER_DATA_BYTES) {
            throw new AwsException("InvalidParameterValue", "UserData exceeds the 16 KiB limit.", 400);
        }
        if (decoded.length >= 2 && (decoded[0] & 0xff) == 0x1f && (decoded[1] & 0xff) == 0x8b) {
            try {
                decoded = UserDataPipeline.decompressGzipWithLimit(decoded);
                if (decoded == null) {
                    throw new AwsException("InvalidParameterValue", "UserData exceeds the decompressed size limit.", 400);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AwsException("InternalError", "Interrupted while decoding UserData.", 500);
            } catch (IOException e) {
                throw new AwsException("InvalidParameterValue", "UserData is not valid gzip content.", 400);
            }
        }
        return new String(decoded, StandardCharsets.UTF_8);
    }
}
