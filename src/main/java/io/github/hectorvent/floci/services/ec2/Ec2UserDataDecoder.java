package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.AwsException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;

public final class Ec2UserDataDecoder {

    private Ec2UserDataDecoder() {
    }

    public static String decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameterValue", "UserData is not valid base64 content.", 400);
        }
        if (decoded.length >= 2 && (decoded[0] & 0xff) == 0x1f && (decoded[1] & 0xff) == 0x8b) {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(decoded))) {
                decoded = gzip.readAllBytes();
            } catch (IOException e) {
                throw new AwsException("InvalidParameterValue", "UserData is not valid gzip content.", 400);
            }
        }
        return new String(decoded, StandardCharsets.UTF_8);
    }
}
