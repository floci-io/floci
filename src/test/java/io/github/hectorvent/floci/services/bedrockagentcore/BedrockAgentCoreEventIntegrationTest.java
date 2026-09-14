package io.github.hectorvent.floci.services.bedrockagentcore;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * AgentCore Memory events.
 *
 * <p>Every expectation here was measured against real AgentCore in us-west-2. The ones worth
 * naming, because they are easy to assume wrongly: a malformed memory id is a
 * {@code ValidationException} while a well-formed but unknown one is
 * {@code ResourceNotFoundException}; an unknown actor or session is an empty list rather than an
 * error; events come back newest first; {@code sessionId} is optional and generated when omitted;
 * and {@code includePayloads=false} drops the key rather than emptying it.
 */
@QuarkusTest
class BedrockAgentCoreEventIntegrationTest {

    private static String createMemory(String name) {
        return given().contentType(ContentType.JSON)
                .body("{\"name\":\"" + name + "\",\"eventExpiryDuration\":7}")
                .when().post("/memories/create")
                .then().statusCode(202)
                .extract().path("memory.id");
    }

    private static String createEvent(String memoryId, String actorId, String sessionId,
                                      double timestamp, String text) {
        String session = sessionId == null ? "" : "\"sessionId\":\"" + sessionId + "\",";
        return given().contentType(ContentType.JSON)
                .body("{\"actorId\":\"" + actorId + "\"," + session
                        + "\"eventTimestamp\":" + timestamp + ","
                        + "\"payload\":[{\"conversational\":{\"role\":\"USER\","
                        + "\"content\":{\"text\":\"" + text + "\"}}}]}")
                .when().post("/memories/" + memoryId + "/events")
                .then().statusCode(201)
                .extract().path("event.eventId");
    }

    @Test
    void createdEventIsListedWithItsPayload() {
        String memoryId = createMemory("evtList");
        createEvent(memoryId, "actor1", "sess1", 1789300800d, "hello");

        given().contentType(ContentType.JSON).body("{}")
                .when().post("/memories/" + memoryId + "/actor/actor1/sessions/sess1")
                .then().statusCode(200)
                .body("events", hasSize(1))
                .body("events[0].payload[0].conversational.content.text", equalTo("hello"))
                .body("events[0].payload[0].conversational.role", equalTo("USER"))
                // AgentCore puts a new event on the main branch when the caller names none.
                .body("events[0].branch.name", equalTo("main"));
    }

    @Test
    void eventsAreListedNewestFirst() {
        String memoryId = createMemory("evtOrder");
        createEvent(memoryId, "actor1", "sess1", 1789300800d, "older");
        createEvent(memoryId, "actor1", "sess1", 1789301400d, "newer");

        given().contentType(ContentType.JSON).body("{}")
                .when().post("/memories/" + memoryId + "/actor/actor1/sessions/sess1")
                .then().statusCode(200)
                .body("events", hasSize(2))
                .body("events[0].payload[0].conversational.content.text", equalTo("newer"))
                .body("events[1].payload[0].conversational.content.text", equalTo("older"));
    }

    @Test
    void excludingPayloadsOmitsTheKeyEntirely() {
        String memoryId = createMemory("evtNoPayload");
        createEvent(memoryId, "actor1", "sess1", 1789300800d, "hello");

        given().contentType(ContentType.JSON).body("{\"includePayloads\":false}")
                .when().post("/memories/" + memoryId + "/actor/actor1/sessions/sess1")
                .then().statusCode(200)
                .body("events", hasSize(1))
                .body("events[0].eventId", notNullValue())
                .body("events[0].payload", nullValue());
    }

    @Test
    void aSessionIdIsGeneratedWhenTheCallerOmitsIt() {
        String memoryId = createMemory("evtNoSession");

        given().contentType(ContentType.JSON)
                .body("{\"actorId\":\"actor1\",\"eventTimestamp\":1789300800,\"payload\":[]}")
                .when().post("/memories/" + memoryId + "/events")
                .then().statusCode(201)
                // Measured: AgentCore assigns a UUID rather than rejecting the request.
                .body("event.sessionId", notNullValue())
                .body("event.eventId", notNullValue());
    }

    @Test
    void anEmptyPayloadIsAccepted() {
        String memoryId = createMemory("evtEmptyPayload");

        given().contentType(ContentType.JSON)
                .body("{\"actorId\":\"a\",\"sessionId\":\"s\",\"eventTimestamp\":1789300800,\"payload\":[]}")
                .when().post("/memories/" + memoryId + "/events")
                .then().statusCode(201);
    }

    @Test
    void anUnknownActorOrSessionIsAnEmptyListRatherThanAnError() {
        String memoryId = createMemory("evtUnknownActor");

        given().contentType(ContentType.JSON).body("{}")
                .when().post("/memories/" + memoryId + "/actor/nobody/sessions/nosuch")
                .then().statusCode(200)
                .body("events", hasSize(0));
    }

    @Test
    void aMalformedMemoryIdIsAValidationError() {
        // The suffix must be exactly ten alphanumerics; this never reaches a lookup.
        given().contentType(ContentType.JSON).body("{}")
                .when().post("/memories/nope-1234/actor/a/sessions/s")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", startsWith("Invalid memoryId"));
    }

    @Test
    void aWellFormedButUnknownMemoryIdIsNotFound() {
        given().contentType(ContentType.JSON).body("{}")
                .when().post("/memories/absent-AAAAAAAAAA/actor/a/sessions/s")
                .then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("message", startsWith("Memory not found"));
    }

    @Test
    void maxResultsAboveTheLimitIsRejected() {
        String memoryId = createMemory("evtMaxResults");

        given().contentType(ContentType.JSON).body("{\"maxResults\":101}")
                .when().post("/memories/" + memoryId + "/actor/a/sessions/s")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("1 validation error detected: Value '101' at 'maxResults' "
                        + "failed to satisfy constraint: Member must have value less than or equal to 100"));
    }

    @Test
    void maxResultsBelowOneIsRejectedWithTheLowerBound() {
        String memoryId = createMemory("evtMaxResultsLow");

        given().contentType(ContentType.JSON).body("{\"maxResults\":0}")
                .when().post("/memories/" + memoryId + "/actor/a/sessions/s")
                .then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("1 validation error detected: Value '0' at 'maxResults' "
                        + "failed to satisfy constraint: Member must have value greater than or equal to 1"));
    }

    @Test
    void gettingAnUnknownEventIsNotFound() {
        String memoryId = createMemory("evtGetMissing");

        given()
                .when().get("/memories/" + memoryId + "/actor/a/sessions/s/events/0000000000000000000#deadbeef")
                .then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void deletingAnEventEchoesItsIdAndRemovesIt() {
        String memoryId = createMemory("evtDelete");
        String eventId = createEvent(memoryId, "actor1", "sess1", 1789300800d, "hello");

        given()
                .when().delete("/memories/" + memoryId + "/actor/actor1/sessions/sess1/events/" + eventId)
                .then().statusCode(200)
                // DeleteEvent echoes the id rather than answering with an empty body.
                .body("eventId", equalTo(eventId));

        given().contentType(ContentType.JSON).body("{}")
                .when().post("/memories/" + memoryId + "/actor/actor1/sessions/sess1")
                .then().statusCode(200)
                .body("events", hasSize(0));
    }

    @Test
    void createEventAnswersWith201() {
        String memoryId = createMemory("evtCreated");

        given().contentType(ContentType.JSON)
                .body("{\"actorId\":\"a\",\"sessionId\":\"s\",\"eventTimestamp\":1789300800,\"payload\":[]}")
                .when().post("/memories/" + memoryId + "/events")
                .then().statusCode(201)
                .body("event.eventId", notNullValue());
    }

    @Test
    void anOmittedPayloadIsRejectedEvenThoughAnEmptyOneIsValid() {
        String memoryId = createMemory("evtNoPayload2");

        // payload is a required member, so its absence is an error while [] is accepted.
        given().contentType(ContentType.JSON)
                .body("{\"actorId\":\"a\",\"sessionId\":\"s\",\"eventTimestamp\":1789300800}")
                .when().post("/memories/" + memoryId + "/events")
                .then().statusCode(400)
                .body("message", containsString("payload"));
    }

    @Test
    void listingPagesThroughWithANextToken() {
        String memoryId = createMemory("evtPaged");
        for (int i = 0; i < 3; i++) {
            createEvent(memoryId, "actor1", "sess1", 1789300800d + (i * 60), "m" + i);
        }

        String firstPage = given().contentType(ContentType.JSON).body("{\"maxResults\":2}")
                .when().post("/memories/" + memoryId + "/actor/actor1/sessions/sess1")
                .then().statusCode(200)
                .body("events", hasSize(2))
                .body("nextToken", notNullValue())
                .extract().path("nextToken");

        given().contentType(ContentType.JSON)
                .body("{\"maxResults\":2,\"nextToken\":\"" + firstPage + "\"}")
                .when().post("/memories/" + memoryId + "/actor/actor1/sessions/sess1")
                .then().statusCode(200)
                .body("events", hasSize(1))
                // No token on the last page: that is what ends a caller's loop.
                .body("nextToken", nullValue());
    }

    @Test
    void anInvalidNextTokenIsRejected() {
        String memoryId = createMemory("evtBadToken");
        createEvent(memoryId, "actor1", "sess1", 1789300800d, "hello");

        given().contentType(ContentType.JSON).body("{\"nextToken\":\"not-a-real-token\"}")
                .when().post("/memories/" + memoryId + "/actor/actor1/sessions/sess1")
                .then().statusCode(400);
    }
}
