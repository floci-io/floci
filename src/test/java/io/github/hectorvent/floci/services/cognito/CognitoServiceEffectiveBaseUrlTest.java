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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Cognito's OIDC-facing URLs (issuer, JWKS URI, token/userInfo endpoints) are handed to external
 * clients — they end up in the JWT {@code iss} claim and the {@code .well-known/openid-configuration}
 * document — so they must honor the {@code FLOCI_HOSTNAME} override the same way SQS, SNS, S3, and
 * Lambda do. That means the service must be wired from {@link EmulatorConfig#effectiveBaseUrl()},
 * not the raw {@link EmulatorConfig#baseUrl()} (which ignores the hostname override).
 */
class CognitoServiceEffectiveBaseUrlTest {

    private CognitoService serviceWithEffectiveBaseUrl(String effectiveBaseUrl) {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(new InMemoryStorage<>());

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        when(config.effectiveBaseUrl()).thenReturn(effectiveBaseUrl);

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
    void issuerHonorsHostnameOverrideFromEffectiveBaseUrl() {
        // Mirrors FLOCI_HOSTNAME/FLOCI_BASE_URL set to a Docker service name: effectiveBaseUrl
        // resolves to the reachable host rather than localhost.
        CognitoService service = serviceWithEffectiveBaseUrl("http://floci:4566");

        UserPool pool = service.createUserPool(
                Map.of("PoolName", "PinnedPool", "UserPoolTags", Map.of(ReservedTags.OVERRIDE_ID_KEY, "custompool")),
                "us-east-1"
        );

        assertEquals("http://floci:4566/custompool", service.getIssuer(pool.getId()));
        assertEquals("http://floci:4566/custompool/.well-known/jwks.json", service.getJwksUri(pool.getId()));
        assertEquals("http://floci:4566/cognito-idp/oauth2/token", service.getTokenEndpoint());
        assertEquals("http://floci:4566/cognito-idp/oauth2/userInfo", service.getUserInfoEndpoint());
    }
}
