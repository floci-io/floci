package io.floci.aws.services.cloudtrail;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

/** Behind a proxy Quarkus is told to trust, the address that proxy forwards is the source address. */
@QuarkusTest
@TestProfile(CloudTrailTrustedProxyIntegrationTest.TrustedLoopbackProxy.class)
class CloudTrailTrustedProxyIntegrationTest {

    /** The test client connects from loopback, which these settings make a trusted proxy. */
    public static class TrustedLoopbackProxy implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.http.proxy.proxy-address-forwarding", "true",
                    "quarkus.http.proxy.allow-x-forwarded", "true",
                    "quarkus.http.proxy.trusted-proxies", "127.0.0.1");
        }
    }

    @Inject
    CloudTrailLogWriter writer;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aTrustedProxysForwardedForIsTheSourceAddress() throws IOException {
        assertThat(CloudTrailSourceAddress.recordedFor(writer, "203.0.113.7"), equalTo("203.0.113.7"));
    }
}
