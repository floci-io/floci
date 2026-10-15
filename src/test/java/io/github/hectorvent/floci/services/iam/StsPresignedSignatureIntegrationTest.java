package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.testutil.AwsRequestSigner;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(StsPresignedSignatureIntegrationTest.SignaturesAndEnforcement.class)
class StsPresignedSignatureIntegrationTest {
    @Inject IamService iam;

    public static class SignaturesAndEnforcement implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.auth.validate-signatures", "true",
                    "floci.services.iam.enforcement-enabled", "true");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"valid", "forged", "missing", "expired", "scope", "body", "future", "date", "expiry", "algorithm", "host", "blank",
            "alias", "url-shadow", "duplicate", "signature-first", "encoded", "unknown", "inactive"})
    void queryIdentityRequiresAuthenticRequest(String mode) throws Exception {
        String name = "presigned-auth-" + UUID.randomUUID();
        Response user = root("CreateUser", "UserName=" + name);
        user.then().statusCode(200);
        String arn = user.xmlPath().getString("CreateUserResponse.CreateUserResult.User.Arn");
        String id = user.xmlPath().getString("CreateUserResponse.CreateUserResult.User.UserId");
        String key = null;
        try {
            Response created = root("CreateAccessKey", "UserName=" + name);
            created.then().statusCode(200);
            key = created.xmlPath().getString("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
            String secret = created.xmlPath().getString("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.SecretAccessKey");
            String body = ("alias".equals(mode) ? "Operation" : "Action") + "=GetCallerIdentity&Version=2011-06-15";
            URI endpoint = URI.create("http://localhost:" + RestAssured.port + "/");
            given().headers(AwsRequestSigner.signedAs(key, secret, "sts")
                            .headersFor("POST", endpoint, Map.of(), body.getBytes(StandardCharsets.UTF_8)))
                    .contentType("application/x-www-form-urlencoded").body(body).post("/")
                    .then().statusCode(200).body("GetCallerIdentityResponse.GetCallerIdentityResult.Arn", equalTo(arn));
            Instant time = "expired".equals(mode) ? Instant.now().minusSeconds(120)
                    : "future".equals(mode) ? Instant.now().plusSeconds(1800) : Instant.now();
            String date = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(time);
            String service = "scope".equals(mode) ? "iam" : "sts";
            String scope = ("date".equals(mode) ? "20000101" : date.substring(0, 8)) + "/us-east-1/" + service + "/aws4_request";
            TreeMap<String, String> query = new TreeMap<>(Map.of("X-Amz-Algorithm", "AWS4-HMAC-SHA256",
                    "X-Amz-Credential", ("unknown".equals(mode) ? "AKIAUNKNOWN" : key) + "/" + scope, "X-Amz-Date", date, "X-Amz-Expires", "60",
                    "X-Amz-SignedHeaders", "host"));
            if ("expiry".equals(mode)) {
                query.put("X-Amz-Expires", "604801");
            } else if ("algorithm".equals(mode)) {
                query.put("X-Amz-Algorithm", "unsupported");
            } else if ("host".equals(mode)) {
                query.put("X-Amz-SignedHeaders", "content-type");
            } else if ("url-shadow".equals(mode)) {
                query.put("Action", "GetSessionToken");
            } else if ("inactive".equals(mode)) {
                root("UpdateAccessKey", "UserName=" + name + "&AccessKeyId=" + key + "&Status=Inactive")
                        .then().statusCode(200);
            }
            String encoded = query.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                    .collect(Collectors.joining("&"));
            if ("duplicate".equals(mode)) {
                encoded = encoded.replace("X-Amz-Date=", "X-Amz-Credential=zzzz&X-Amz-Date=");
            }
            String canonical = "POST\n/\n" + encoded + "\nhost:" + endpoint.getAuthority() + "\n\nhost\n"
                    + Digests.sha256Hex(body.getBytes(StandardCharsets.UTF_8));
            String toSign = "AWS4-HMAC-SHA256\n" + date + "\n" + scope + "\n" + Digests.sha256Hex(canonical);
            String signature = SigV4RequestValidator.hexEncode(SigV4RequestValidator.hmacSha256(
                    SigV4RequestValidator.deriveSigningKey(secret, date.substring(0, 8), "us-east-1", service), toSign));
            if (!"missing".equals(mode)) {
                encoded += "&X-Amz-Signature=" + ("forged".equals(mode) || "blank".equals(mode) || "alias".equals(mode)
                        || "url-shadow".equals(mode) || "signature-first".equals(mode) ? "0".repeat(64) : signature);
            }
            if ("signature-first".equals(mode)) {
                encoded += "&X-Amz-Signature=" + signature;
            } else if ("duplicate".equals(mode)) {
                encoded += "&X-Amz-Signature=unused";
            } else if ("encoded".equals(mode)) {
                encoded = encoded.replace("X-Amz-Credential", "X-Amz-%43redential");
            }
            RequestSpecification request = given().urlEncodingEnabled(false);
            if ("blank".equals(mode)) {
                request.header("Authorization", " ");
            }
            Response response = request.contentType("application/x-www-form-urlencoded")
                    .body("body".equals(mode) ? body + "&Extra=changed" : body).post(endpoint + "?" + encoded);
            if ("valid".equals(mode) || "duplicate".equals(mode) || "encoded".equals(mode)) {
                response.then().statusCode(200).body("GetCallerIdentityResponse.GetCallerIdentityResult.Arn", equalTo(arn))
                        .body("GetCallerIdentityResponse.GetCallerIdentityResult.UserId", equalTo(id));
            } else {
                boolean malformed = Set.of("missing", "expiry", "algorithm", "host").contains(mode);
                String code = Set.of("unknown", "inactive").contains(mode) ? "InvalidClientTokenId"
                        : malformed ? "IncompleteSignature" : "SignatureDoesNotMatch";
                response.then().statusCode(malformed ? 400 : 403).body("ErrorResponse.Error.Code", equalTo(code));
            }
        } finally {
            try {
                if (key != null) {
                    root("DeleteAccessKey", "UserName=" + name + "&AccessKeyId=" + key).then().statusCode(200);
                }
            } finally {
                root("DeleteUser", "UserName=" + name).then().statusCode(200);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"query", "missing", "wrong", "header-first", "form-first", "bad-header", "bad-form"})
    void temporaryTokenSelectionMatchesIdentity(String mode) throws Exception {
        Response issued = root("GetSessionToken", "DurationSeconds=900");
        issued.then().statusCode(200);
        String prefix = "GetSessionTokenResponse.GetSessionTokenResult.Credentials.";
        String key = issued.xmlPath().getString(prefix + "AccessKeyId");
        String secret = issued.xmlPath().getString(prefix + "SecretAccessKey");
        String token = issued.xmlPath().getString(prefix + "SessionToken");
        try {
            String body = "Action=GetCallerIdentity&Version=2011-06-15";
            Map<String, String> headers = new TreeMap<>();
            TreeMap<String, String> query = new TreeMap<>();
            if (!"missing".equals(mode)) {
                query.put("X-Amz-Security-Token", Set.of("wrong", "header-first", "form-first").contains(mode)
                        ? "wrong" : token);
            }
            if ("header-first".equals(mode)) {
                headers.put("X-Amz-Security-Token", token);
                body += "&X-Amz-Security-Token=wrong";
            } else if ("bad-header".equals(mode)) {
                headers.put("X-Amz-Security-Token", "wrong");
            } else if ("form-first".equals(mode) || "bad-form".equals(mode)) {
                body += "&X-Amz-Security-Token=" + encode("form-first".equals(mode) ? token : "wrong");
            }
            URI uri = presign(key, secret, body, query);
            Response response = given().headers(headers).urlEncodingEnabled(false)
                    .contentType("application/x-www-form-urlencoded").body(body).post(uri);
            if (Set.of("query", "header-first", "form-first").contains(mode)) {
                response.then().statusCode(200).body("GetCallerIdentityResponse.GetCallerIdentityResult.Arn",
                        equalTo("arn:aws:iam::000000000000:root"));
            } else {
                response.then().statusCode(403).body("ErrorResponse.Error.Code", equalTo("InvalidClientTokenId"));
            }
        } finally {
            iam.unregisterSession("000000000000", key);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nonblankHeaderRemainsTheAuthenticationOwner(boolean forgedHeader) throws Exception {
        String body = "Action=GetCallerIdentity";
        URI valid = presign("test", "test", body, new TreeMap<>());
        URI uri = forgedHeader ? valid : URI.create(valid.toString()
                .replaceAll("X-Amz-Signature=[^&]*", "X-Amz-Signature=forged"));
        Map<String, String> headers = AwsRequestSigner.signedAs("test", forgedHeader ? "wrong" : "test", "sts")
                .headersFor("POST", uri, Map.of(), body.getBytes(StandardCharsets.UTF_8));
        Response response = given().headers(headers).urlEncodingEnabled(false)
                .contentType("application/x-www-form-urlencoded").body(body).post(uri);
        response.then().statusCode(forgedHeader ? 403 : 200);
        if (forgedHeader) {
            response.then().body("ErrorResponse.Error.Code", equalTo("SignatureDoesNotMatch"));
        } else {
            response.then().body("GetCallerIdentityResponse.GetCallerIdentityResult.Arn",
                    equalTo("arn:aws:iam::000000000000:root"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"GetSessionToken", "GetFederationToken", "AssumeRole"})
    void urlIdentityDoesNotChangeOtherBodyOperations(String action) {
        // Query credentials must be authenticated for the operation that actually executes.
        Response result = given().queryParam("Action", "GetCallerIdentity").queryParam("X-Amz-Credential", "test/20261013/us-east-1/sts/aws4_request")
                .contentType("application/x-www-form-urlencoded").body("Action=" + action + "&Name=outside")
                .post("/");
        String key = result.xmlPath().getString(action + "Response." + action + "Result.Credentials.AccessKeyId");
        try {
            result.then().statusCode(400).body("ErrorResponse.Error.Code", equalTo("IncompleteSignature"));
        } finally {
            if (key != null) {
                iam.unregisterSession("000000000000", key);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void signedPayloadMarkerIsHonored(boolean signedMarker) throws Exception {
        String body = "Action=GetCallerIdentity";
        Map<String, String> headers = Map.of("x-amz-content-sha256", "UNSIGNED-PAYLOAD");
        URI uri = presign("test", "test", body, new TreeMap<>(), signedMarker);
        Response response = given().headers(headers).urlEncodingEnabled(false)
                .contentType("application/x-www-form-urlencoded").body(body).post(uri);
        response.then().statusCode(signedMarker ? 200 : 403);
        if (!signedMarker) {
            response.then().body("ErrorResponse.Error.Code", equalTo("SignatureDoesNotMatch"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"GetSessionToken", "GetFederationToken", "AssumeRole"})
    void querySignaturesProtectCredentialIssuance(String action) throws Exception {
        String roleName = "presigned-issuance-" + UUID.randomUUID();
        String body = "Action=" + action + "&Name=presigned&DurationSeconds=900";
        if ("AssumeRole".equals(action)) {
            Response role = root("CreateRole", "RoleName=" + roleName + "&AssumeRolePolicyDocument="
                    + encode("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"Principal\":\"*\",\"Action\":\"sts:AssumeRole\"}]}"));
            role.then().statusCode(200);
            body += "&RoleArn=" + encode(role.xmlPath().getString("CreateRoleResponse.CreateRoleResult.Role.Arn"))
                    + "&RoleSessionName=presigned";
        }
        String issuedKey = null;
        try {
            URI valid = presign("test", "test", body, new TreeMap<>());
            URI forged = URI.create(valid.toString().replaceAll("X-Amz-Signature=[^&]*", "X-Amz-Signature=forged"));
            given().urlEncodingEnabled(false).contentType("application/x-www-form-urlencoded").body(body).post(forged)
                    .then().statusCode(403).body("ErrorResponse.Error.Code", equalTo("SignatureDoesNotMatch"));
            Response issued = given().urlEncodingEnabled(false).contentType("application/x-www-form-urlencoded")
                    .body(body).post(valid);
            issuedKey = issued.xmlPath().getString(action + "Response." + action + "Result.Credentials.AccessKeyId");
            issued.then().statusCode(200);
            assertNotNull(issuedKey);
        } finally {
            if (issuedKey != null) {
                iam.unregisterSession("000000000000", issuedKey);
            }
            if ("AssumeRole".equals(action)) {
                root("DeleteRole", "RoleName=" + roleName).then().statusCode(200);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"json, false", "json, true", "rest, false", "rest, true", "query, false", "query, true"})
    void sharedVerifierCoversOtherProtocols(String protocol, boolean forged) throws Exception {
        String method = "rest".equals(protocol) ? "GET" : "POST";
        String path = "rest".equals(protocol) ? "/schedule-groups" : "/";
        String service = "rest".equals(protocol) ? "scheduler" : "query".equals(protocol) ? "iam" : "sqs";
        String body = "rest".equals(protocol) ? "" : "query".equals(protocol) ? "Action=ListUsers&Version=2010-05-08" : "{}";
        URI uri = presignRequest("test", forged ? "wrong" : "test", method, path, service, body,
                new TreeMap<>(), null);
        RequestSpecification request = given().urlEncodingEnabled(false);
        if ("json".equals(protocol)) {
            request.contentType("application/x-amz-json-1.0").header("X-Amz-Target", "AmazonSQS.ListQueues");
        } else if ("query".equals(protocol)) {
            request.contentType("application/x-www-form-urlencoded");
        }
        if (!"GET".equals(method)) {
            if ("query".equals(protocol)) {
                request.body(body);
            } else {
                request.body(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        Response response = request.request(method, uri);
        response.then().statusCode(forged ? 403 : 200);
        if (forged) {
            if ("query".equals(protocol)) {
                response.then().body("ErrorResponse.Error.Code", equalTo("SignatureDoesNotMatch"));
            } else {
                response.then().header("X-Amzn-Errortype", equalTo("InvalidSignatureException"));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"sts, expired", "sts, scope", "iam, expired", "iam, scope",
            "sqs, expired", "sqs, scope", "lambda, expired", "lambda, scope",
            "sts, future", "iam, future", "sqs, future", "lambda, future"})
    void queryRefusalExplainsExpiryOrExpectedService(String service, String failure) throws Exception {
        boolean rest = "lambda".equals(service);
        boolean queryProtocol = "sts".equals(service) || "iam".equals(service);
        String method = rest ? "GET" : "POST";
        String path = rest ? "/2015-03-31/functions" : "/";
        String body = "sts".equals(service) ? "Action=GetCallerIdentity&Version=2011-06-15"
                : "iam".equals(service) ? "Action=ListUsers&Version=2010-05-08" : rest ? "" : "{}";
        boolean expired = "expired".equals(failure);
        boolean future = "future".equals(failure);
        long cutoffOffset = future ? 900 : -60;
        Instant signedAt = Instant.now().plusSeconds(future ? 1200 : expired ? -120 : 0)
                .truncatedTo(ChronoUnit.SECONDS);
        URI uri = presignRequest("test", "test", method, path, expired || future ? service : "kms", body,
                new TreeMap<>(), null, signedAt);
        RequestSpecification request = given().urlEncodingEnabled(false);
        if (queryProtocol) {
            request.contentType("application/x-www-form-urlencoded");
        } else if (!rest) {
            request.contentType("application/x-amz-json-1.0").header("X-Amz-Target", "AmazonSQS.ListQueues");
        }
        if (queryProtocol) {
            request.body(body);
        } else if (!rest) {
            request.body(body.getBytes(StandardCharsets.UTF_8));
        }
        Instant before = Instant.now().plusSeconds(cutoffOffset).truncatedTo(ChronoUnit.SECONDS);
        Response response = request.request(method, uri);
        Instant after = Instant.now().plusSeconds(cutoffOffset).truncatedTo(ChronoUnit.SECONDS);
        response.then().statusCode(403);
        String message;
        if (queryProtocol) {
            response.then().body("ErrorResponse.Error.Code", equalTo("SignatureDoesNotMatch"));
            message = response.xmlPath().getString("ErrorResponse.Error.Message");
        } else {
            response.then().header("X-Amzn-Errortype", equalTo("InvalidSignatureException"));
            message = response.jsonPath().getString("message");
        }
        if (expired || future) {
            DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
            String prefix = future
                    ? "Signature not yet current: " + format.format(signedAt) + " is still later than "
                    : "Signature expired: " + format.format(signedAt) + " is now earlier than ";
            assertTrue(message.startsWith(prefix), message);
            Instant cutoff = Instant.from(format.parse(message.substring(prefix.length())));
            assertTrue(!cutoff.isBefore(before) && !cutoff.isAfter(after), message);
        } else {
            assertEquals("Credential should be scoped to correct service: '"
                    + service + "'", message);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"airflow", "mwaa", "kms"})
    void multipleAcceptedScopesDoNotInventCanonicalSigningName(String service) throws Exception {
        URI uri = presignRequest("test", "test", "GET", "/environments", service, "", new TreeMap<>(), null);
        Response response = given().urlEncodingEnabled(false).get(uri);
        if ("kms".equals(service)) {
            response.then().statusCode(403).header("X-Amzn-Errortype", equalTo("InvalidSignatureException"))
                    .body("message", equalTo("Credential should be scoped to the service receiving the request."));
        } else {
            response.then().statusCode(200);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad%ZZ=x&Action=ListUsers", "Action=ListUsers&X-Amz-Security-Token=%"})
    void malformedFormCannotEscapeAsServerError(String body) throws Exception {
        URI uri = presignRequest("test", "test", "POST", "/", "iam", body, new TreeMap<>(), null);
        given().urlEncodingEnabled(false).contentType("application/x-www-form-urlencoded").body(body).post(uri)
                .then().statusCode(400);
    }

    private static URI presign(String key, String secret, String body, TreeMap<String, String> query) throws Exception {
        return presign(key, secret, body, query, null);
    }

    private static URI presign(String key, String secret, String body, TreeMap<String, String> query,
                               Boolean signedMarker) throws Exception {
        return presignRequest(key, secret, "POST", "/", "sts", body, query, signedMarker);
    }

    private static URI presignRequest(String key, String secret, String method, String path, String service,
                                     String body, TreeMap<String, String> query, Boolean signedMarker) throws Exception {
        return presignRequest(key, secret, method, path, service, body, query, signedMarker, Instant.now());
    }

    private static URI presignRequest(String key, String secret, String method, String path, String service,
                                     String body, TreeMap<String, String> query, Boolean signedMarker,
                                     Instant signedAt) throws Exception {
        URI endpoint = URI.create("http://localhost:" + RestAssured.port + path);
        String date = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(signedAt);
        String scope = date.substring(0, 8) + "/us-east-1/" + service + "/aws4_request";
        query.putAll(Map.of("X-Amz-Algorithm", "AWS4-HMAC-SHA256", "X-Amz-Credential", key + "/" + scope,
                "X-Amz-Date", date, "X-Amz-Expires", "60", "X-Amz-SignedHeaders", Boolean.TRUE.equals(signedMarker)
                        ? "host;x-amz-content-sha256" : "host"));
        String encoded = query.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
        String signedHeaders = query.get("X-Amz-SignedHeaders");
        String canonical = method + "\n" + path + "\n" + encoded + "\nhost:" + endpoint.getAuthority() + "\n"
                + (Boolean.TRUE.equals(signedMarker) ? "x-amz-content-sha256:UNSIGNED-PAYLOAD\n" : "")
                + "\n" + signedHeaders + "\n"
                + (signedMarker != null ? "UNSIGNED-PAYLOAD" : Digests.sha256Hex(body.getBytes(StandardCharsets.UTF_8)));
        String toSign = "AWS4-HMAC-SHA256\n" + date + "\n" + scope + "\n" + Digests.sha256Hex(canonical);
        String signature = SigV4RequestValidator.hexEncode(SigV4RequestValidator.hmacSha256(
                SigV4RequestValidator.deriveSigningKey(secret, date.substring(0, 8), "us-east-1", service), toSign));
        return URI.create(endpoint + "?" + encoded + "&X-Amz-Signature=" + signature);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static Response root(String action, String parameters) throws Exception {
        String body = "Action=" + action + "&" + parameters;
        URI uri = URI.create("http://localhost:" + RestAssured.port + "/");
        return given().headers(AwsRequestSigner.signedAs("test", "test", "GetSessionToken".equals(action) ? "sts" : "iam")
                        .headersFor("POST", uri, Map.of(), body.getBytes(StandardCharsets.UTF_8)))
                .contentType("application/x-www-form-urlencoded").body(body).post("/");
    }
}
