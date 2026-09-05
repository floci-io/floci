package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.config.TlsCertificateManager;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * CreateUserPoolDomain hands a custom domain to the TLS certificate manager on the real wire
 * path. A prefix domain lives under {@code auth.<region>.amazoncognito.com} on AWS, a name Floci
 * does not serve, so it is left alone.
 */
@QuarkusTest
class CognitoDomainEnsureHostIntegrationTest {

    private static final String CERTIFICATE_ARN = "arn:aws:acm:us-east-1:000000000000:certificate/abc";

    @InjectMock
    TlsCertificateManager certificateManager;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void customDomainRegistersTheHostnameForTls() throws Exception {
        String poolId = createPool();
        String domain = "auth-" + System.nanoTime() + ".dev.localhost.floci.io";

        cognitoJson("CreateUserPoolDomain", """
                {
                  "Domain": "%s",
                  "UserPoolId": "%s",
                  "CustomDomainConfig": {"CertificateArn": "%s"}
                }
                """.formatted(domain, poolId, CERTIFICATE_ARN));

        verify(certificateManager).ensureHost(domain);
    }

    @Test
    void prefixDomainRegistersNothing() throws Exception {
        String poolId = createPool();

        cognitoJson("CreateUserPoolDomain", """
                {
                  "Domain": "prefix-%s",
                  "UserPoolId": "%s"
                }
                """.formatted(System.nanoTime(), poolId));

        verify(certificateManager, never()).ensureHost(anyString());
    }

    @Test
    void rejectedCreateRegistersNothing() {
        cognitoAction("CreateUserPoolDomain", """
                {
                  "Domain": "orphan-%s.dev.localhost.floci.io",
                  "UserPoolId": "us-east-1_missing",
                  "CustomDomainConfig": {"CertificateArn": "%s"}
                }
                """.formatted(System.nanoTime(), CERTIFICATE_ARN))
                .then()
                .statusCode(400);

        verify(certificateManager, never()).ensureHost(anyString());
    }

    private static String createPool() throws Exception {
        JsonNode pool = cognitoJson("CreateUserPool", "{\"PoolName\": \"EnsureHostPool\"}");
        return pool.path("UserPool").path("Id").asText();
    }
}
