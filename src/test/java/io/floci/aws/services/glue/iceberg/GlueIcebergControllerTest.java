package io.floci.aws.services.glue.iceberg;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.services.glue.GlueService;
import io.floci.aws.services.s3.S3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/** Who the endpoint answers under IAM enforcement. */
class GlueIcebergControllerTest {

    private EmulatorConfig config;
    private RequestContext requestContext;
    private GlueIcebergController controller;

    @BeforeEach
    void setUp() {
        config = Mockito.mock(EmulatorConfig.class, Answers.RETURNS_DEEP_STUBS);
        requestContext = Mockito.mock(RequestContext.class);
        GlueIcebergCatalog catalog = new GlueIcebergCatalog(Mockito.mock(GlueService.class),
                Mockito.mock(S3Service.class), new RegionResolver("us-east-1", "000000000000"), new ObjectMapper(),
                null, null);
        controller = new GlueIcebergController(catalog, config, requestContext);
    }

    @Test
    void underIamEnforcementOnlyRootCredentialsAndFlocisOwnCallersReachTheCatalog() {
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        when(requestContext.getAccountId()).thenReturn("000000000000");

        when(requestContext.getAccessKeyId()).thenReturn("AKIAEXAMPLEUSER0001");
        IcebergRestException refused = assertThrows(IcebergRestException.class, () -> controller.getConfig(null));
        assertEquals(403, refused.code());
        assertEquals("ForbiddenException", refused.type());

        for (String rootOrInternal : new String[] {"test", "000000000000", null}) {
            when(requestContext.getAccessKeyId()).thenReturn(rootOrInternal);
            assertEquals("catalogs/:", controller.getConfig(null).path("overrides").path("prefix").asText());
        }
    }

    @Test
    void withoutEnforcementAnyCallerReachesTheCatalog() {
        when(config.services().iam().enforcementEnabled()).thenReturn(false);
        when(requestContext.getAccessKeyId()).thenReturn("AKIAEXAMPLEUSER0001");

        assertEquals("catalogs/:", controller.getConfig(null).path("overrides").path("prefix").asText());
    }
}
