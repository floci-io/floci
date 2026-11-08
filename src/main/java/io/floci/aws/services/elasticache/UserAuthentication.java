package io.floci.aws.services.elasticache;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.elasticache.model.AuthMode;

import java.util.List;
import java.util.Locale;

/**
 * The authentication a CreateUser or ModifyUser request, or an AWS::ElastiCache::User resource,
 * asks for. AWS accepts it either as an {@code AuthenticationMode} or through the top-level
 * {@code Passwords} and {@code NoPasswordRequired} members, so passwords given either way mean
 * password authentication.
 */
public record UserAuthentication(AuthMode mode, List<String> passwords) {

    /**
     * Returns null when nothing names an authentication at all, so the caller can keep its own default.
     *
     * @param type AuthenticationMode.Type, or null when absent
     * @param passwords the passwords from AuthenticationMode or the top-level member
     * @param noPasswordRequired the top-level NoPasswordRequired member
     */
    public static UserAuthentication resolve(String type, List<String> passwords, boolean noPasswordRequired) {
        if (type == null || type.isBlank()) {
            if (noPasswordRequired && !passwords.isEmpty()) {
                throw invalidCombination("NoPasswordRequired cannot be true when Passwords are provided.");
            }
            if (!passwords.isEmpty()) {
                return new UserAuthentication(AuthMode.PASSWORD, passwords);
            }
            return noPasswordRequired ? new UserAuthentication(AuthMode.NO_AUTH, List.of()) : null;
        }

        String normalizedType = type.toLowerCase(Locale.ROOT);
        if (noPasswordRequired && !"no-password-required".equals(normalizedType)) {
            throw invalidCombination("NoPasswordRequired cannot be true when AuthenticationMode.Type is " + type + ".");
        }
        return switch (normalizedType) {
            case "password" -> {
                if (passwords.isEmpty()) {
                    throw new AwsException("InvalidParameterValue",
                            "Passwords are required when AuthenticationMode.Type is password.", 400);
                }
                yield new UserAuthentication(AuthMode.PASSWORD, passwords);
            }
            case "iam", "no-password-required" -> {
                if (!passwords.isEmpty()) {
                    throw invalidCombination("Passwords cannot be provided when AuthenticationMode.Type is " + type + ".");
                }
                yield new UserAuthentication("iam".equals(normalizedType) ? AuthMode.IAM : AuthMode.NO_AUTH, List.of());
            }
            default -> throw new AwsException("InvalidParameterValue",
                    "Invalid AuthenticationMode.Type " + type + ". Valid values are password, no-password-required and iam.", 400);
        };
    }

    private static AwsException invalidCombination(String message) {
        return new AwsException("InvalidParameterCombination", message, 400);
    }
}
