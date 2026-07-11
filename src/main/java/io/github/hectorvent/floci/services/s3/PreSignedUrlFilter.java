package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.iam.IamService;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.stream.Collectors;

@Provider
public class PreSignedUrlFilter implements ContainerRequestFilter {

    private static final String LEGACY_ACCESS_KEY_ID = "test";
    private static final String LEGACY_SECRET_KEY = "test";

    private final PreSignedUrlGenerator presignGenerator;
    private final S3Service s3Service;
    private final IamService iamService;

    @Inject
    public PreSignedUrlFilter(PreSignedUrlGenerator presignGenerator,
                              S3Service s3Service,
                              IamService iamService) {
        this.presignGenerator = presignGenerator;
        this.s3Service = s3Service;
        this.iamService = iamService;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        var queryParams = requestContext.getUriInfo().getQueryParameters();

        // Only process if this is a pre-signed URL request
        String algorithm = queryParams.getFirst("X-Amz-Algorithm");
        if (algorithm == null) {
            return;
        }

        if (S3RequestAuthorizationParser.isMissingRequiredPresignedParameter(queryParams)) {
            requestContext.abortWith(errorResponse(
                    S3RequestAuthorizationParser.AUTHORIZATION_QUERY_PARAMETERS_ERROR_STATUS,
                    S3RequestAuthorizationParser.AUTHORIZATION_QUERY_PARAMETERS_ERROR_CODE,
                    S3RequestAuthorizationParser.AUTHORIZATION_QUERY_PARAMETERS_ERROR_MESSAGE));
            return;
        }

        String amzDate = queryParams.getFirst("X-Amz-Date");
        String expiresStr = queryParams.getFirst("X-Amz-Expires");
        String signature = queryParams.getFirst("X-Amz-Signature");

        int expires;
        try {
            expires = Integer.parseInt(expiresStr);
        } catch (NumberFormatException e) {
            requestContext.abortWith(errorResponse(403, "AccessDenied",
                    "Invalid X-Amz-Expires value."));
            return;
        }

        // Check expiration
        if (presignGenerator.isExpired(amzDate, expires)) {
            requestContext.abortWith(errorResponse(403, "AccessDenied",
                    "Request has expired."));
            return;
        }

        // Verify signature: SigV4 when enforce-auth is enabled, custom when validateSignatures is enabled
        if (s3Service.isAuthEnforced()) {
            String credential = queryParams.getFirst("X-Amz-Credential");
            String decodedCredential = URLDecoder.decode(credential, StandardCharsets.UTF_8);
            String[] credParts = decodedCredential.split("/");
            if (credParts.length < 5) {
                requestContext.abortWith(errorResponse(403, "InvalidAccessKeyId",
                        "The AWS Access Key Id you provided does not exist in our records."));
                return;
            }

            String accessKeyId = credParts[0];
            String secretKey = resolveSecretKey(accessKeyId);
            if (secretKey == null) {
                requestContext.abortWith(errorResponse(403, "InvalidAccessKeyId",
                        "The AWS Access Key Id you provided does not exist in our records."));
                return;
            }

            if (!verifySigV4Signature(requestContext, signature, secretKey)) {
                requestContext.abortWith(errorResponse(403, "SignatureDoesNotMatch",
                        "The request signature we calculated does not match the signature you provided."));
            }
        } else if (presignGenerator.shouldValidateSignatures()) {
            String path = requestContext.getUriInfo().getPath();
            String[] parts = path.split("/", 3);
            if (parts.length < 3) {
                requestContext.abortWith(errorResponse(403, "AccessDenied",
                        "Invalid pre-signed URL path."));
                return;
            }
            String bucket = parts[1];
            String key = parts[2];
            String method = requestContext.getMethod();

            if (!presignGenerator.verifySignature(method, bucket, key, amzDate, expires, signature)) {
                requestContext.abortWith(errorResponse(403, "SignatureDoesNotMatch",
                        "The request signature we calculated does not match the signature you provided."));
            }
        }
    }

    private boolean verifySigV4Signature(ContainerRequestContext requestContext,
                                        String signature, String secretKey) {
        try {
            var queryParams = requestContext.getUriInfo().getQueryParameters();
            String credential = queryParams.getFirst("X-Amz-Credential");
            String amzDate = queryParams.getFirst("X-Amz-Date");
            String signedHeaders = queryParams.getFirst("X-Amz-SignedHeaders");

            String decodedCredential = URLDecoder.decode(credential, StandardCharsets.UTF_8);
            String[] credParts = decodedCredential.split("/");
            if (credParts.length < 5) {
                return false;
            }
            String date = credParts[1];
            String region = credParts[2];
            String service = credParts[3];
            String credentialScope = date + "/" + region + "/" + service + "/aws4_request";

            // Build canonical query string from raw query (excluding X-Amz-Signature)
            String rawQuery = requestContext.getUriInfo().getRequestUri().getRawQuery();
            String canonicalQueryString = Arrays.stream(rawQuery.split("&"))
                    .filter(p -> !rawParamName(p).equals("X-Amz-Signature"))
                    .sorted((a, b) -> rawParamName(a).compareTo(rawParamName(b)))
                    .collect(Collectors.joining("&"));

            // Build canonical headers from signed headers
            String host = requestContext.getUriInfo().getRequestUri().getHost();
            int port = requestContext.getUriInfo().getRequestUri().getPort();
            String authority = (port > 0 && port != 80 && port != 443) ? host + ":" + port : host;

            StringBuilder canonicalHeaders = new StringBuilder();
            for (String header : signedHeaders.split(";")) {
                if ("host".equals(header)) {
                    canonicalHeaders.append("host:").append(authority).append("\n");
                } else {
                    String value = requestContext.getHeaderString(header);
                    canonicalHeaders.append(header).append(":").append(value != null ? value.trim() : "").append("\n");
                }
            }

            // Canonical request
            String path = requestContext.getUriInfo().getRequestUri().getRawPath();
            String canonicalRequest = requestContext.getMethod() + "\n"
                    + path + "\n"
                    + canonicalQueryString + "\n"
                    + canonicalHeaders + "\n"
                    + signedHeaders + "\n"
                    + "UNSIGNED-PAYLOAD";

            // String to sign
            String stringToSign = "AWS4-HMAC-SHA256\n"
                    + amzDate + "\n"
                    + credentialScope + "\n"
                    + sha256Hex(canonicalRequest);

            // Derive signing key and compute expected signature
            byte[] signingKey = deriveSigningKey(secretKey, date, region, service);
            String expectedSignature = hexEncode(hmacSha256(signingKey, stringToSign));

            return MessageDigest.isEqual(
                    expectedSignature.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));

        } catch (Exception e) {
            return false;
        }
    }

    private String resolveSecretKey(String accessKeyId) {
        if (LEGACY_ACCESS_KEY_ID.equals(accessKeyId)) {
            return LEGACY_SECRET_KEY;
        }
        if (iamService != null) {
            return iamService.findSecretKey(accessKeyId).orElse(null);
        }
        return null;
    }

    private static String rawParamName(String rawPair) {
        int eq = rawPair.indexOf('=');
        return eq >= 0 ? rawPair.substring(0, eq) : rawPair;
    }

    private static byte[] deriveSigningKey(String secretKey, String date, String region,
                                           String service) throws Exception {
        byte[] kSecret = ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8);
        byte[] kDate = hmacSha256(kSecret, date);
        byte[] kRegion = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, service);
        return hmacSha256(kService, "aws4_request");
    }

    private static byte[] hmacSha256(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return hexEncode(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hexEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private Response errorResponse(int status, String code, String message) {
        String xml = new XmlBuilder()
                .raw("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .start("Error")
                  .elem("Code", code)
                  .elem("Message", message)
                .end("Error")
                .build();
        return Response.status(status).entity(xml).type(MediaType.APPLICATION_XML).build();
    }
}
