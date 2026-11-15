package io.floci.aws.services.s3;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.ResteasyRequests;
import io.floci.aws.core.common.auth.SigV4aSignature;
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
        return isSigned(httpHeaders.getHeaderString("Authorization"), ResteasyRequests.queryParameters(uriInfo));
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
        return parse(httpHeaders.getHeaderString("Authorization"), ResteasyRequests.queryParameters(uriInfo),
                httpHeaders.getHeaderString("X-Amz-Security-Token"));
    }

    static S3Service.RequestAuthorization parse(String authorization, MultivaluedMap<String, String> queryParameters) {
        return parse(authorization, queryParameters, null);
    }

    private static S3Service.RequestAuthorization parse(
            String authorization, MultivaluedMap<String, String> queryParameters, String headerSessionToken) {
        if (authorization != null && !authorization.isBlank()) {
            String accessKeyId = extractAuthorizationHeaderAccessKeyId(authorization);
            if (accessKeyId == null) {
                // Only SigV4 is parsed; unsupported schemes are reported as malformed auth.
                String message = "The authorization header is malformed; the credential scope is invalid.";
                throw new AwsException("AuthorizationHeaderMalformed", message, 400);
            }
            String sessionToken = !isBlank(headerSessionToken)
                    ? headerSessionToken
                    : queryParameters.getFirst("X-Amz-Security-Token");
            return new S3Service.RequestAuthorization(true, accessKeyId, sessionToken);
        }

        if (queryParameters.containsKey("X-Amz-Algorithm")) {
            String accessKeyId = extractPresignedAccessKeyId(queryParameters);
            if (accessKeyId == null) {
                throw new AwsException(AUTHORIZATION_QUERY_PARAMETERS_ERROR_CODE,
                        AUTHORIZATION_QUERY_PARAMETERS_ERROR_MESSAGE,
                        AUTHORIZATION_QUERY_PARAMETERS_ERROR_STATUS);
            }
            return new S3Service.RequestAuthorization(
                    true, accessKeyId, queryParameters.getFirst("X-Amz-Security-Token"));
        }

        return S3Service.RequestAuthorization.unsigned();
    }

    private static String extractAuthorizationHeaderAccessKeyId(String authorization) {
        String trimmed = authorization.trim();
        boolean asymmetric = trimmed.startsWith(SigV4aSignature.ALGORITHM + " ");
        if (!asymmetric && !trimmed.startsWith(SIGV4_ALGORITHM + " ")) {
            return null;
        }

        Map<String, String> parameters = authorizationParameters(trimmed.substring(trimmed.indexOf(' ')).trim());
        if (isBlank(parameters.get("SignedHeaders")) || isBlank(parameters.get("Signature"))) {
            return null;
        }
        return extractCredentialAccessKeyId(parameters.get("Credential"), asymmetric);
    }

    private static String extractPresignedAccessKeyId(MultivaluedMap<String, String> queryParameters) {
        String algorithm = queryParameters.getFirst("X-Amz-Algorithm");
        boolean asymmetric = SigV4aSignature.ALGORITHM.equals(algorithm);
        if (!asymmetric && !SIGV4_ALGORITHM.equals(algorithm)) {
            return null;
        }
        if (isMissingRequiredPresignedParameter(queryParameters)) {
            return null;
        }
        return extractCredentialAccessKeyId(queryParameters.getFirst("X-Amz-Credential"), asymmetric);
    }

    static boolean isMissingRequiredPresignedParameter(MultivaluedMap<String, String> queryParameters) {
        for (String parameter : REQUIRED_PRESIGNED_PARAMETERS) {
            if (isBlank(queryParameters.getFirst(parameter))) {
                return true;
            }
        }
        return SigV4aSignature.ALGORITHM.equals(queryParameters.getFirst("X-Amz-Algorithm"))
                && isBlank(queryParameters.getFirst("X-Amz-Region-Set"));
    }

    private static Map<String, String> authorizationParameters(String parameters) {
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String part : parameters.split(",")) {
            int equals = part.indexOf('=');
            if (equals <= 0) {
                return Map.of();
            }
            String key = part.substring(0, equals).trim();
            String value = part.substring(equals + 1).trim();
            if (parsed.putIfAbsent(key, value) != null) {
                return Map.of();
            }
        }
        return parsed;
    }

    /** A SigV4A scope drops the region segment: {@code <akid>/<date>/s3/aws4_request}. */
    private static String extractCredentialAccessKeyId(String credential, boolean asymmetric) {
        if (isBlank(credential)) {
            return null;
        }
        String[] parts = credential.split("/", -1);
        int serviceIndex = asymmetric ? 2 : 3;
        if (parts.length != serviceIndex + 2) {
            return null;
        }
        if (parts[0].isBlank() || !isEightDigitDate(parts[1]) || (!asymmetric && parts[2].isBlank())) {
            return null;
        }
        if (!"s3".equals(parts[serviceIndex]) || !"aws4_request".equals(parts[serviceIndex + 1])) {
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
