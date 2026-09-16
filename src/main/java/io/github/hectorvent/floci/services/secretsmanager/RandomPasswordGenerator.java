package io.github.hectorvent.floci.services.secretsmanager;

import com.fasterxml.jackson.databind.JsonNode;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Generates random passwords following the same rules as the AWS Secrets Manager
 * {@code GetRandomPassword} API. Reused by both the JSON handler and CloudFormation
 * {@code GenerateSecretString} provisioning.
 *
 * @see <a href="https://docs.aws.amazon.com/secretsmanager/latest/apireference/API_GetRandomPassword.html">
 *     AWS Secrets Manager – GetRandomPassword</a>
 */
public final class RandomPasswordGenerator {

    private static final String LOWERCASE = "abcdefghijklmnopqrstuvwxyz";
    private static final String UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String DIGITS = "0123456789";
    private static final String PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~";

    private RandomPasswordGenerator() {
    }

    /**
     * Generate a random password from a JSON node that may contain:
     * {@code PasswordLength}, {@code ExcludeCharacters}, {@code ExcludeLowercase},
     * {@code ExcludeUppercase}, {@code ExcludeNumbers}, {@code ExcludePunctuation},
     * {@code IncludeSpace}, {@code RequireEachIncludedType}.
     *
     * @param node JSON object with optional password-generation fields
     * @return the generated password
     * @throws IllegalArgumentException if PasswordLength is out of range or the charset is empty
     */
    public static String generate(JsonNode node) {
        boolean excludeLower = boolField(node, "ExcludeLowercase");
        boolean excludeUpper = boolField(node, "ExcludeUppercase");
        boolean excludeNumbers = boolField(node, "ExcludeNumbers");
        boolean excludePunctuation = boolField(node, "ExcludePunctuation");
        boolean includeSpace = boolField(node, "IncludeSpace");
        String excludeChars = stringField(node, "ExcludeCharacters");
        if (excludeChars != null && excludeChars.length() > 4096) {
            throw new IllegalArgumentException("ExcludeCharacters must be at most 4096 characters.");
        }

        JsonNode passwordLengthNode = node == null ? null : node.get("PasswordLength");
        int length = (passwordLengthNode != null && !passwordLengthNode.isNull())
                ? passwordLengthNode.asInt(32) : 32;
        if (length < 1 || length > 4096) {
            throw new IllegalArgumentException("PasswordLength must be between 1 and 4096.");
        }

        JsonNode reqNode = node == null ? null : node.get("RequireEachIncludedType");
        boolean requireEach = reqNode == null || reqNode.isNull() || reqNode.asBoolean();

        // Build charset
        StringBuilder charset = new StringBuilder();
        if (!excludeLower) charset.append(LOWERCASE);
        if (!excludeUpper) charset.append(UPPERCASE);
        if (!excludeNumbers) charset.append(DIGITS);
        if (!excludePunctuation) charset.append(PUNCTUATION);
        if (includeSpace) charset.append(" ");

        if (excludeChars != null) {
            for (int i = charset.length() - 1; i >= 0; i--) {
                if (excludeChars.indexOf(charset.charAt(i)) >= 0) {
                    charset.deleteCharAt(i);
                }
            }
        }

        if (charset.isEmpty()) {
            throw new IllegalArgumentException("The password charset is empty after applying exclusions.");
        }

        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder(length);

        if (requireEach) {
            List<String> types = new ArrayList<>();
            if (!excludeLower) types.add(LOWERCASE);
            if (!excludeUpper) types.add(UPPERCASE);
            if (!excludeNumbers) types.add(DIGITS);
            if (!excludePunctuation) types.add(PUNCTUATION);
            if (includeSpace) types.add(" ");

            if (excludeChars != null) {
                types = types.stream()
                        .map(t -> t.chars()
                                .filter(c -> excludeChars.indexOf(c) < 0)
                                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                                .toString())
                        .filter(t -> !t.isEmpty())
                        .collect(Collectors.toList());
            }
            for (String type : types) {
                password.append(type.charAt(random.nextInt(type.length())));
            }
        }

        for (int i = password.length(); i < length; i++) {
            password.append(charset.charAt(random.nextInt(charset.length())));
        }

        // Shuffle so required seed chars aren't always at the start
        for (int i = length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char tmp = password.charAt(i);
            password.setCharAt(i, password.charAt(j));
            password.setCharAt(j, tmp);
        }

        return password.toString();
    }

    private static boolean boolField(JsonNode node, String name) {
        if (node == null) return false;
        JsonNode f = node.get(name);
        return f != null && !f.isNull() && f.asBoolean();
    }

    private static String stringField(JsonNode node, String name) {
        if (node == null) return null;
        JsonNode f = node.get(name);
        return (f != null && !f.isNull()) ? f.asText() : null;
    }
}
