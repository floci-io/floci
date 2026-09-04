package io.github.hectorvent.floci.services.s3;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.iam.IamService;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S3's own auth switch is independent of global IAM enforcement.  These tests exercise the
 * service-level path with a real IAM ECS session so a temporary key cannot fall back to its secret
 * when the token is missing, stale, or supplied in the wrong request placement.
 */
class S3EcsAuthTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String BUCKET = "ecs-auth-bucket";
    private static final String OBJECT = "private.txt";
    private static final String ROLE_ARN = "arn:aws:iam::" + ACCOUNT_ID + ":role/EcsAuthRole";
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private IamService iamService;
    private S3Service s3Service;

    @BeforeEach
    void setUp() throws Exception {
        iamService = newIamService();
        iamService.createRole("EcsAuthRole", "/", "{}", null, 0, null);
        s3Service = newS3Service(iamService);
        s3Service.createBucket(BUCKET, "us-east-1");
        s3Service.putObject(BUCKET, OBJECT, "private".getBytes(), "text/plain", null);
    }

    @Test
    void headerRequestRequiresMatchingEcsSessionToken() {
        String accessKey = issue("A", "header+token/with%percent", Instant.now().plusSeconds(3600));

        S3Service.RequestAuthorization valid = S3RequestAuthorizationParser.parse(
                header(accessKey, "header+token/with%percent"), emptyUri());
        assertTrue(valid.signed());
        assertEquals("header+token/with%percent", valid.sessionToken());
        s3Service.authorizeGetObject(BUCKET, OBJECT, null, valid);

        assertInvalidAccessKey(header(accessKey, null));
        assertInvalidAccessKey(header(accessKey, "wrong-token"));
    }

    @Test
    void presignedRequestDecodesAndBindsEcsSessionToken() {
        String accessKey = issue("B", "query+token/slash%percent", Instant.now().plusSeconds(3600));
        String encodedToken = "query%2Btoken%2Fslash%25percent";
        MultivaluedMap<String, String> query = presignedQuery(accessKey, encodedToken);

        S3Service.RequestAuthorization valid = S3RequestAuthorizationParser.parse(null, query);
        assertEquals("query+token/slash%percent", valid.sessionToken());
        s3Service.authorizeGetObject(BUCKET, OBJECT, null, valid);

        query.putSingle("X-Amz-Security-Token", "wrong-token");
        assertInvalidAccessKey(S3RequestAuthorizationParser.parse(null, query));
    }

    @Test
    void expiredAndRevokedEcsCredentialsFailClosed() {
        String expired = issue("C", "expired-token", Instant.now().plusSeconds(3600));
        iamService.resolveEcsTaskRoleSession(expired).orElseThrow().setExpiration(Instant.EPOCH);
        assertInvalidAccessKey(header(expired, "expired-token"));

        String revoked = issue("D", "revoked-token", Instant.now().plusSeconds(3600));
        iamService.revokeEcsTaskRoleSession(
                "arn:aws:ecs:us-east-1:" + ACCOUNT_ID + ":task/default/task-D", revoked);
        assertInvalidAccessKey(header(revoked, "revoked-token"));
    }

    @Test
    void tokenHeaderWithoutAuthorizationCannotBypassUnsignedS3Read() {
        HttpHeaders headers = mock(HttpHeaders.class);
        when(headers.getHeaderString(anyString())).thenAnswer(invocation -> {
            String name = invocation.getArgument(0);
            return "X-Amz-Security-Token".equalsIgnoreCase(name) ? "any-token" : null;
        });

        S3Service.RequestAuthorization authorization = S3RequestAuthorizationParser.parse(
                headers, emptyUri());
        assertTrue(!authorization.signed());
        AwsException denied = assertThrows(AwsException.class,
                () -> s3Service.authorizeGetObject(BUCKET, OBJECT, null, authorization));
        assertEquals("AccessDenied", denied.getErrorCode());
    }

    private void assertInvalidAccessKey(HttpHeaders headers) {
        assertInvalidAccessKey(S3RequestAuthorizationParser.parse(headers, emptyUri()));
    }

    private void assertInvalidAccessKey(S3Service.RequestAuthorization authorization) {
        AwsException denied = assertThrows(AwsException.class,
                () -> s3Service.authorizeGetObject(BUCKET, OBJECT, null, authorization));
        assertEquals("InvalidAccessKeyId", denied.getErrorCode());
    }

    private String issue(String suffix, String token, Instant expiration) {
        String taskArn = "arn:aws:ecs:us-east-1:" + ACCOUNT_ID + ":task/default/task-" + suffix;
        String accessKey = "ASIAECS" + suffix + "E".repeat(12);
        String path = "/v2/credentials/" + suffix.repeat(48);
        iamService.registerEcsTaskRoleSession(taskArn, ACCOUNT_ID, accessKey,
                "ecs-secret-" + suffix, token, ROLE_ARN, expiration, path);
        return accessKey;
    }

    private static HttpHeaders header(String accessKey, String token) {
        HttpHeaders headers = mock(HttpHeaders.class);
        String authorization = "AWS4-HMAC-SHA256 Credential=" + accessKey + "/"
                + DATE.format(Instant.now()) + "/us-east-1/s3/aws4_request, "
                + "SignedHeaders=host;x-amz-date, Signature=test";
        when(headers.getHeaderString(anyString())).thenAnswer(invocation -> {
            String name = invocation.getArgument(0);
            if ("Authorization".equalsIgnoreCase(name)) {
                return authorization;
            }
            if ("X-Amz-Security-Token".equalsIgnoreCase(name)) {
                return token;
            }
            return null;
        });
        return headers;
    }

    private static MultivaluedMap<String, String> presignedQuery(String accessKey, String token) {
        String date = DATE.format(Instant.now());
        MultivaluedMap<String, String> query = new MultivaluedHashMap<>();
        query.putSingle("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
        query.putSingle("X-Amz-Credential", accessKey + "/" + date + "/us-east-1/s3/aws4_request");
        query.putSingle("X-Amz-Date", date + "T000000Z");
        query.putSingle("X-Amz-Expires", "3600");
        query.putSingle("X-Amz-SignedHeaders", "host");
        query.putSingle("X-Amz-Signature", "signature");
        query.putSingle("X-Amz-Security-Token", token);
        return query;
    }

    private static UriInfo emptyUri() {
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        return uriInfo;
    }

    @SuppressWarnings("unchecked")
    private static IamService newIamService() throws Exception {
        Constructor<?> constructor = Arrays.stream(IamService.class.getDeclaredConstructors())
                .filter(candidate -> candidate.getParameterCount() == 8)
                .findFirst().orElseThrow();
        constructor.setAccessible(true);
        return (IamService) constructor.newInstance(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new RegionResolver("us-east-1", ACCOUNT_ID));
    }

    private static S3Service newS3Service(IamService iamService) throws Exception {
        Constructor<?> constructor = Arrays.stream(S3Service.class.getDeclaredConstructors())
                .filter(candidate -> candidate.getParameterCount() == 18)
                .findFirst().orElseThrow();
        constructor.setAccessible(true);
        Object[] arguments = new Object[18];
        arguments[0] = new InMemoryStorage<>();
        arguments[1] = new InMemoryStorage<>();
        arguments[2] = AccountAwareStorageBackend.inMemory(ACCOUNT_ID);
        arguments[3] = Path.of("s3-ecs-auth-test");
        arguments[4] = true;
        arguments[12] = new RegionResolver("us-east-1", ACCOUNT_ID);
        arguments[13] = "http://localhost:4566";
        arguments[14] = new ObjectMapper();
        arguments[15] = true;
        arguments[16] = iamService;
        arguments[17] = false;
        return (S3Service) constructor.newInstance(arguments);
    }
}
