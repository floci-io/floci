package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ses.model.AccountVdmAttributes;
import io.github.hectorvent.floci.services.ses.model.SentEmail;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the parts of {@link SesInsightsController} the integration test cannot pin down:
 * that the Virtual Deliverability Manager gate runs before the store is touched at all, and that a
 * message stored before insights existed still renders.
 */
class SesInsightsControllerTest {

    private static final String REGION = "eu-north-1";

    private final SesSentEmailService sentEmailService = mock(SesSentEmailService.class);
    private final SesAccountService accountService = mock(SesAccountService.class);
    private final RegionResolver regionResolver = mock(RegionResolver.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SesInsightsController controller = new SesInsightsController(
            sentEmailService, accountService, regionResolver, objectMapper);

    @BeforeEach
    void stubRegion() {
        when(regionResolver.resolveRegion(any())).thenReturn(REGION);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
    }

    private void vdmEnabled(boolean enabled) {
        if (enabled) {
            return;
        }
        doThrow(new AwsException("NotFoundException",
                "To use this feature you must enable Virtual Deliverability Manager", 404))
                .when(accountService).requireVdmEnabled(REGION);
    }

    @Test
    void vdmGateRunsBeforeTheStoreIsTouched() {
        vdmEnabled(false);

        AwsException thrown = assertThrows(AwsException.class,
                () -> controller.getMessageInsights(null, "any-id"));

        assertEquals("NotFoundException", thrown.getErrorCode());
        assertTrue(thrown.getMessage().contains("Virtual Deliverability Manager"), thrown.getMessage());
        // The integration test can only infer the ordering from the response; this pins it. What
        // the gate itself decides is SesAccountServiceTest's business.
        verifyNoInteractions(sentEmailService);
    }

    @Test
    void unknownMessageNamesTheResolvedAccount() {
        vdmEnabled(true);
        when(sentEmailService.find(anyString(), anyString())).thenReturn(Optional.empty());

        AwsException thrown = assertThrows(AwsException.class,
                () -> controller.getMessageInsights(null, "missing"));

        assertEquals(404, thrown.getHttpStatus());
        assertEquals("Message <missing> not found for account <000000000000>", thrown.getMessage());
    }

    @Test
    void aMessageStoredBeforeInsightsExistedStillRenders() {
        // Records written by an older Floci carry no Insights and no EmailTags. The message is
        // still a real message, so it is served as an empty timeline rather than reported missing.
        SentEmail legacy = new SentEmail("legacy-id", REGION, "sender@example.com",
                List.of("user@example.com"), List.of(), List.of(), List.of(), "old", "hi", null);
        legacy.setInsights(null);
        legacy.setEmailTags(null);
        vdmEnabled(true);
        when(sentEmailService.find(REGION, "legacy-id")).thenReturn(Optional.of(legacy));

        Response response = controller.getMessageInsights(null, "legacy-id");

        assertEquals(200, response.getStatus());
        ObjectNode body = (ObjectNode) response.getEntity();
        assertEquals("legacy-id", body.get("MessageId").asText());
        assertEquals("sender@example.com", body.get("FromEmailAddress").asText());
        assertTrue(body.get("Insights").isEmpty(), "Insights should be an empty array");
        assertTrue(body.get("EmailTags").isEmpty(), "EmailTags should be an empty array");
    }
}
