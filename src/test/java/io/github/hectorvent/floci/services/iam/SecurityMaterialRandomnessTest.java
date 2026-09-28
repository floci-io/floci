package io.github.hectorvent.floci.services.iam;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the security-material generators against regressing to a non-CSPRNG source. Ordinary
 * resource IDs may keep using {@code ThreadLocalRandom}; credentials, session tokens, grant tokens,
 * and data keys must not.
 */
class SecurityMaterialRandomnessTest {

    private static final Path IAM_SERVICE =
            Path.of("src/main/java/io/github/hectorvent/floci/services/iam/IamService.java");
    private static final Path STS_HANDLER =
            Path.of("src/main/java/io/github/hectorvent/floci/services/iam/StsQueryHandler.java");
    private static final Path KMS_SERVICE =
            Path.of("src/main/java/io/github/hectorvent/floci/services/kms/KmsService.java");
    private static final Pattern RANDOM_SECRET_DECLARATION =
            Pattern.compile("private\\s+String\\s+randomSecret\\s*\\(int\\s+length\\)");

    @Test
    void iamAndStsSecretGeneratorsDoNotUseThreadLocalRandom() throws IOException {
        assertSecretGeneratorUsesSecureRandom(IAM_SERVICE);
        assertSecretGeneratorUsesSecureRandom(STS_HANDLER);
    }

    @Test
    void kmsServiceDoesNotImportThreadLocalRandom() throws IOException {
        assertFalse(Files.readString(KMS_SERVICE).contains("ThreadLocalRandom"),
                "KMS security material must come from SecureRandom, not ThreadLocalRandom");
    }

    private static void assertSecretGeneratorUsesSecureRandom(Path file) throws IOException {
        String source = Files.readString(file);
        Matcher declaration = RANDOM_SECRET_DECLARATION.matcher(source);
        assertTrue(declaration.find(), "no randomSecret(int length) declaration in " + file);
        int bodyStart = source.indexOf('{', declaration.end());
        assertTrue(bodyStart >= 0, "no body for randomSecret in " + file);
        String body = source.substring(bodyStart, matchingBrace(source, bodyStart) + 1);
        assertTrue(body.contains("secureRandom"),
                "randomSecret in " + file + " must draw from the SecureRandom field");
        assertFalse(body.contains("ThreadLocalRandom"),
                "randomSecret in " + file + " must use SecureRandom, not ThreadLocalRandom");
    }

    private static int matchingBrace(String source, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new IllegalArgumentException("unbalanced braces from index " + openIndex);
    }
}
