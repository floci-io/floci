package io.github.hectorvent.floci.services.cognito;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ReservedTags;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.ses.SesService;
import io.github.hectorvent.floci.services.sns.SnsService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Cognito's OIDC-facing URLs (issuer, JWKS URI, token/userInfo endpoints) are handed to external
 * clients — they end up in the JWT {@code iss} claim and the {@code .well-known/openid-configuration}
 * document — so they must honor the {@code FLOCI_HOSTNAME} override the same way SQS, SNS, S3, and
 * Lambda do. That means the service must be wired from {@link EmulatorConfig#effectiveBaseUrl()},
 * not the raw {@link EmulatorConfig#baseUrl()} (which ignores the hostname override).
 *
 * <p>This drives the real {@code effectiveBaseUrl()} default method (only its inputs — {@code baseUrl},
 * {@code hostname}, {@code tls}) are stubbed) so it reproduces the reported bug end to end: against the
 * old {@code baseUrl()} wiring the assertions below fail because the {@code localhost -> floci} host
 * swap never reaches Cognito.
 */
class CognitoServiceEffectiveBaseUrlTest {

    private CognitoService serviceWithHostnameOverride(String baseUrl, String hostname) {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(new InMemoryStorage<>());

        EmulatorConfig.TlsConfig tls = Mockito.mock(EmulatorConfig.TlsConfig.class);
        when(tls.enabled()).thenReturn(false);

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        when(config.baseUrl()).thenReturn(baseUrl);
        when(config.hostname()).thenReturn(Optional.ofNullable(hostname));
        when(config.tls()).thenReturn(tls);
        when(config.effectiveBaseUrl()).thenCallRealMethod();

        return new CognitoService(
                storageFactory,
                config,
                new RegionResolver("us-east-1", "000000000000"),
                Mockito.mock(LambdaService.class),
                Mockito.mock(SesService.class),
                Mockito.mock(SnsService.class),
                Clock.systemUTC()
        );
    }

    @Test
    void oidcUrlsHonorHostnameOverride() {
        // FLOCI_BASE_URL=http://localhost:4566 with FLOCI_HOSTNAME=floci — the multi-container /
        // Docker Compose case where localhost is unreachable from other containers.
        CognitoService service = serviceWithHostnameOverride("http://localhost:4566", "floci");

        UserPool pool = service.createUserPool(
                Map.of("PoolName", "PinnedPool", "UserPoolTags", Map.of(ReservedTags.OVERRIDE_ID_KEY, "custompool")),
                "us-east-1"
        );

        assertEquals("http://floci:4566/custompool", service.getIssuer(pool.getId()));
        assertEquals("http://floci:4566/custompool/.well-known/jwks.json", service.getJwksUri(pool.getId()));
        assertEquals("http://floci:4566/cognito-idp/oauth2/token", service.getTokenEndpoint());
        assertEquals("http://floci:4566/cognito-idp/oauth2/userInfo", service.getUserInfoEndpoint());
    }

    @Test
    void oidcUrlsFallBackToBaseUrlWhenNoHostname() {
        // No FLOCI_HOSTNAME: effectiveBaseUrl() falls back to baseUrl(), preserving default behavior.
        CognitoService service = serviceWithHostnameOverride("http://localhost:4566", null);

        UserPool pool = service.createUserPool(
                Map.of("PoolName", "PinnedPool", "UserPoolTags", Map.of(ReservedTags.OVERRIDE_ID_KEY, "custompool")),
                "us-east-1"
        );

        assertEquals("http://localhost:4566/custompool", service.getIssuer(pool.getId()));
    }
}
