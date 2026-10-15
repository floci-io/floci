package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.redshift.RedshiftIamDbUserResolver;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

/**
 * Covers the metadata-only Redshift Serverless actions through the handler, so both the stored
 * behavior and the response shape the SDKs deserialize are checked.
 */
class RedshiftServerlessAdministrationHandlerTest {
    private static final String REGION = "us-east-1";

    private final ObjectMapper mapper = new ObjectMapper();
    private RedshiftServerlessJsonHandler handler;

    @BeforeEach
    void setUp() {
        handler = new RedshiftServerlessJsonHandler(mock(RedshiftServerlessService.class), mapper,
                mock(RedshiftIamDbUserResolver.class), mock(RedshiftServerlessUsageLimitService.class),
                new RedshiftServerlessAdminJsonHandler(mapper));
    }

    @Test
    void tracksAreTheTwoAwsReleaseTracks() {
        JsonNode track = ok("GetTrack", "{\"trackName\":\"trailing\"}").get("track");
        assertEquals("trailing", track.get("trackName").textValue());
        assertEquals(0, track.get("updateTargets").size());
        assertEquals("ResourceNotFoundException", errorType("GetTrack", "{\"trackName\":\"nightly\"}"));
        assertEquals("ResourceNotFoundException", errorType("GetTrack", "{\"trackName\":\"preview\"}"));
        assertEquals("ValidationException", errorType("GetTrack", "{\"trackName\":\"not valid!\"}"));
        assertEquals("ValidationException", errorType("GetTrack", "{}"));

        assertEquals(2, ok("ListTracks", "{}").get("tracks").size());
        JsonNode page = ok("ListTracks", "{\"maxResults\":1}");
        assertEquals("current", page.get("tracks").get(0).get("trackName").textValue());
        String next = page.get("nextToken").textValue();
        JsonNode rest = ok("ListTracks", "{\"nextToken\":\"" + next + "\"}");
        assertEquals(1, rest.get("tracks").size());
        assertEquals("trailing", rest.get("tracks").get(0).get("trackName").textValue());
        assertFalse(rest.has("nextToken"));
        assertEquals("ValidationException", errorType("ListTracks", "{\"maxResults\":\"x\"}"));
        assertEquals("ValidationException", errorType("ListTracks", "{\"maxResults\":0}"));
        assertEquals("ValidationException", errorType("ListTracks", "{\"maxResults\":101}"));
        assertEquals("ValidationException", errorType("ListTracks", "{\"nextToken\":\"bad-token\"}"));
    }

    @Test
    void managedWorkgroupsAndReservationsAreEmptyBecauseFlociModelsNone() {
        assertEquals(0, ok("ListManagedWorkgroups", "{}").get("managedWorkgroups").size());
        assertEquals("ValidationException", errorType("ListManagedWorkgroups", "{\"sourceArn\":\"nope\"}"));
        assertEquals(0, ok("ListManagedWorkgroups", "{\"sourceArn\":\"arn:aws:glue:us-east-1:123456789012:"
                + "database/db\"}").get("managedWorkgroups").size());
        assertEquals("ValidationException", errorType("ListManagedWorkgroups", "{\"maxResults\":0}"));
        assertEquals("ValidationException", errorType("ListManagedWorkgroups", "{\"maxResults\":101}"));

        assertEquals(0, ok("ListReservations", "{}").get("reservationsList").size());
        assertEquals("ValidationException", errorType("ListReservations", "{\"maxResults\":0}"));
        assertEquals("ValidationException", errorType("ListReservations", "{\"maxResults\":101}"));

        assertEquals(0, ok("ListReservationOfferings", "{}").get("reservationOfferingsList").size());
        assertEquals("ValidationException", errorType("ListReservationOfferings", "{\"maxResults\":0}"));
        assertEquals("ValidationException", errorType("ListReservationOfferings", "{\"maxResults\":101}"));

        assertEquals("ValidationException",
                errorType("ListManagedWorkgroups", "{\"nextToken\":\"bad-token\"}"));
        assertEquals("ValidationException",
                errorType("ListReservations", "{\"nextToken\":\"bad-token\"}"));
        assertEquals("ValidationException",
                errorType("ListReservationOfferings", "{\"nextToken\":\"bad-token\"}"));

        assertEquals("ResourceNotFoundException", errorType("GetReservation", "{\"reservationId\":\"r-1\"}"));
        assertEquals("ValidationException", errorType("GetReservation", "{}"));
        assertEquals("ValidationException", errorType("GetReservation", "{\"reservationId\":\"\"}"));
        assertEquals("ResourceNotFoundException", errorType("GetReservationOffering", "{\"offeringId\":\"o-1\"}"));
        assertEquals("ValidationException", errorType("GetReservationOffering", "{}"));
        assertEquals("ValidationException", errorType("GetReservationOffering", "{\"offeringId\":\"\"}"));
        assertEquals("ResourceNotFoundException",
                errorType("CreateReservation", "{\"capacity\":128,\"offeringId\":\"o-1\"}"));
        assertEquals("ValidationException", errorType("CreateReservation", "{\"offeringId\":\"o-1\"}"));
        assertEquals("ValidationException",
                errorType("CreateReservation", "{\"capacity\":\"nope\",\"offeringId\":\"o-1\"}"));
        assertEquals("ValidationException",
                errorType("CreateReservation", "{\"capacity\":128,\"offeringId\":\"o-1\",\"clientToken\":123}"));
    }

    @Test
    void anUnknownActionIsStillRejected() {
        assertEquals("UnknownOperationException", errorType("NoSuchOperation", "{}"));
    }

    private JsonNode ok(String action, String json) {
        Response response = invoke(action, json);
        assertEquals(200, response.getStatus(), action + " failed: " + response.getEntity());
        return mapper.valueToTree(response.getEntity());
    }

    private String errorType(String action, String json) {
        Response response = invoke(action, json);
        assertEquals(400, response.getStatus(), action + " should be rejected: " + response.getEntity());
        return mapper.valueToTree(response.getEntity()).path("__type").asText(null);
    }

    private Response invoke(String action, String json) {
        try {
            return handler.handle(action, mapper.readTree(json), REGION);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
