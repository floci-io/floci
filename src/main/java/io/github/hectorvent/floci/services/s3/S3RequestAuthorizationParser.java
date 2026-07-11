package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class S3RequestAuthorizationParser {

    private static final String SIGV4_ALGORITHM = "AWS4-HMAC-SHA256";
    static final String AUTHORIZATION_QUERY_PARAMETERS_ERROR_CODE = "AuthorizationQueryParametersError";
    static final String AUTHORIZATION_QUERY_PARAMETERS_ERROR_MESSAGE =
            "Query-string authentication version 4 requires the X-Amz-Algorithm, "
                    + "X-Amz-Credential, X-Amz-Signature, X-Amz-Date, X-Amz-SignedHeaders, "
                    + "and X-Amz-Expires parameters.";
    static final int AUTHORIZATION_QUERY_PARAMETERS_ERROR_STATUS = 400;

    private static final Set<String> REQUIRED_PRESIGNED_PARAMETERS = Set.of(
            "X-Amz-Algorithm",
            "X-Amz-Credential",
            "X-Amz-Date",
            "X-Amz-Expires",
            "X-Amz-SignedHeaders",
            "X-Amz-Signature");

    private S3RequestAuthorizationParser() {
    }

    static boolean isSigned(HttpHeaders httpHeaders, UriInfo uriInfo) {
        return isSigned(httpHeaders.getHeaderString("Authorization"), uriInfo.getQueryParameters());
    }

    static boolean isSigned(String authorization, MultivaluedMap<String, String> queryParameters) {
        return (authorization != null && !authorization.isBlank())
                || queryParameters.containsKey("X-Amz-Algorithm");
    }

    static S3Service.RequestAuthorization parseIfRequired(
            boolean enforceAuth, HttpHeaders httpHeaders, UriInfo uriInfo) {
        if (!enforceAuth) {
            return S3Service.RequestAuthorization.unsigned();
        }
        return parse(httpHeaders, uriInfo);
    }

    static S3Service.RequestAuthorization parseIfRequired(
            boolean enforceAuth, String authorization, MultivaluedMap<String, String> queryParameters) {
        if (!enforceAuth) {
            return S3Service.RequestAuthorization.unsigned();
        }
        return parse(authorization, queryParameters);
    }

    static S3Service.RequestAuthorization parse(HttpHeaders httpHeaders, UriInfo uriInfo) {
        return parse(httpHeaders.getHeaderString("Authorization"), uriInfo.getQueryParameters());
    }

    static S3Service.RequestAuthorization parse(String authorization, MultivaluedMap<String, String> queryParameters) {
        if (authorization != null && !authorization.isBlank()) {
            String accessKeyId = extractAuthorizationHeaderAccessKeyId(authorization);
            if (accessKeyId == null) {
                // Only SigV4 is parsed; unsupported schemes are reported as malformed auth.
                String message = "The authorization header is malformed; the credential scope is invalid.";
                throw new AwsException("AuthorizationHeaderMalformed", message, 400);
            }
            return new S3Service.RequestAuthorization(true, accessKeyId);
        }

        if (queryParameters.containsKey("X-Amz-Algorithm")) {
            String accessKeyId = extractPresignedAccessKeyId(queryParameters);
            if (accessKeyId == null) {
                throw new AwsException(AUTHORIZATION_QUERY_PARAMETERS_ERROR_CODE,
                        AUTHORIZATION_QUERY_PARAMETERS_ERROR_MESSAGE,
                        AUTHORIZATION_QUERY_PARAMETERS_ERROR_STATUS);
            }
            return new S3Service.RequestAuthorization(true, accessKeyId);
        }

        return S3Service.RequestAuthorization.unsigned();
    }

    private static String extractAuthorizationHeaderAccessKeyId(String authorization) {
        String trimmed = authorization.trim();
        if (!trimmed.startsWith(SIGV4_ALGORITHM + " ")) {
            return null;
        }

        Map<String, String> parameters = authorizationParameters(trimmed.substring(SIGV4_ALGORITHM.length()).trim());
        if (isBlank(parameters.get("SignedHeaders")) || isBlank(parameters.get("Signature"))) {
            return null;
        }
        return extractCredentialAccessKeyId(parameters.get("Credential"));
    }

    private static String extractPresignedAccessKeyId(MultivaluedMap<String, String> queryParameters) {
        if (!SIGV4_ALGORITHM.equals(queryParameters.getFirst("X-Amz-Algorithm"))) {
            return null;
        }
        if (isMissingRequiredPresignedParameter(queryParameters)) {
            return null;
        }
        return extractCredentialAccessKeyId(queryParameters.getFirst("X-Amz-Credential"));
    }

    static boolean isMissingRequiredPresignedParameter(MultivaluedMap<String, String> queryParameters) {
        for (String parameter : REQUIRED_PRESIGNED_PARAMETERS) {
            if (isBlank(queryParameters.getFirst(parameter))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> authorizationParameters(String parameters) {
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String part : parameters.split(",")) {
            int equals = part.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            parsed.put(part.substring(0, equals).trim(), part.substring(equals + 1).trim());
        }
        return parsed;
    }

    private static String extractCredentialAccessKeyId(String credential) {
        if (isBlank(credential)) {
            return null;
        }
        String[] parts = credential.split("/", -1);
        if (parts.length != 5) {
            return null;
        }
        if (parts[0].isBlank() || !isEightDigitDate(parts[1]) || parts[2].isBlank()) {
            return null;
        }
        if (!"s3".equals(parts[3]) || !"aws4_request".equals(parts[4])) {
            return null;
        }
        return parts[0];
    }

    private static boolean isEightDigitDate(String value) {
        if (value.length() != 8) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
