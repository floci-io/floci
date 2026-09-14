package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ses.model.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the request-parsing helpers shared by the SES v2 controllers. Each helper's
 * accept / reject split and its wording were previously pinned only through the controller
 * integration tests; these pin them directly so the controllers can move without re-proving them.
 */
class SesV2JsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static AwsException assertAws(String code, int status, Executable call) {
        AwsException e = assertThrows(AwsException.class, call);
        assertEquals(code, e.getErrorCode());
        assertEquals(status, e.getHttpStatus());
        return e;
    }

    @Test
    void remapV1Exception_mapsEachV1FamilyAndPassesOthersThrough() {
        AwsException bad = SesV2Json.remapV1Exception(
                new AwsException("InvalidParameterValue", "m", 400));
        assertEquals("BadRequestException", bad.getErrorCode());
        assertEquals("m", bad.getMessage());
        assertEquals(400, bad.getHttpStatus());
        assertEquals("BadRequestException", SesV2Json.remapV1Exception(
                new AwsException("MissingRenderingAttribute", "m", 400)).getErrorCode());

        AwsException notFound = SesV2Json.remapV1Exception(
                new AwsException("ConfigurationSetDoesNotExist", "m", 400));
        assertEquals("NotFoundException", notFound.getErrorCode());
        assertEquals(404, notFound.getHttpStatus());

        assertEquals("AlreadyExistsException", SesV2Json.remapV1Exception(new AwsException(
                "CustomVerificationEmailTemplateAlreadyExists", "m", 400)).getErrorCode());
        assertEquals("SendingPausedException", SesV2Json.remapV1Exception(new AwsException(
                "ConfigurationSetSendingPausedException", "m", 400)).getErrorCode());

        AwsException untouched = new AwsException("NotFoundException", "already v2", 404);
        assertSame(untouched, SesV2Json.remapV1Exception(untouched));
    }

    @Test
    void requireJsonObject_rejectsNullAndNonObjects() {
        assertEquals("Request body must be a JSON object.", assertAws("BadRequestException", 400,
                () -> SesV2Json.requireJsonObject(null)).getMessage());
        assertAws("BadRequestException", 400, () -> SesV2Json.requireJsonObject(json("[]")));
        SesV2Json.requireJsonObject(json("{}"));
    }

    @Test
    void requireObjectOrAbsent_returnsChild_rejectsScalars() {
        assertTrue(SesV2Json.requireObjectOrAbsent(json("{}"), "Options").isMissingNode());
        assertTrue(SesV2Json.requireObjectOrAbsent(json("{\"Options\":null}"), "Options").isNull());
        JsonNode nested = json("{\"Options\":{\"a\":\"x\"}}");
        assertEquals("x", SesV2Json.requireObjectOrAbsent(nested, "Options").path("a").asText());
        assertEquals("Options must be a JSON object.",
                assertAws("BadRequestException", 400, () -> SesV2Json.requireObjectOrAbsent(
                        json("{\"Options\":1}"), "Options")).getMessage());
    }

    @Test
    void parseTagsArray_absentIsNull_shapeErrorsAreWireErrors() {
        assertNull(SesV2Json.parseTagsArray(json("{}").path("Tags")));
        assertNull(SesV2Json.parseTagsArray(json("{\"Tags\":null}").path("Tags")));
        assertEquals("Tags must be an array.", assertAws("BadRequestException", 400,
                () -> SesV2Json.parseTagsArray(json("{\"Tags\":{}}").path("Tags"))).getMessage());
        assertAws("SerializationException", 400,
                () -> SesV2Json.parseTagsArray(json("[\"k=v\"]")));
        assertAws("SerializationException", 400,
                () -> SesV2Json.parseTagsArray(json("[{\"Key\":1,\"Value\":\"v\"}]")));
        assertAws("SerializationException", 400,
                () -> SesV2Json.parseTagsArray(json("[{\"Key\":\"k\",\"Value\":[]}]")));

        List<Tag> tags = SesV2Json.parseTagsArray(
                json("[{\"Key\":\"k\",\"Value\":\"v\"},{\"Key\":\"only\"}]"));
        assertEquals(2, tags.size());
        assertEquals("k", tags.get(0).key());
        assertEquals("v", tags.get(0).value());
        // A missing member is left to the downstream tag validation rather than rejected here.
        assertEquals("only", tags.get(1).key());
        assertNull(tags.get(1).value());
    }

    @Test
    void readOptionBody_blankIsEmptyObject_badShapesAre400() {
        assertTrue(SesV2Json.readOptionBody(MAPPER, null).isObject());
        assertTrue(SesV2Json.readOptionBody(MAPPER, "  ").isObject());
        assertEquals("REQUIRE", SesV2Json.readOptionBody(MAPPER, "{\"HttpsPolicy\":\"REQUIRE\"}")
                .path("HttpsPolicy").asText());
        assertEquals("Request body must be a JSON object.", assertAws("BadRequestException", 400,
                () -> SesV2Json.readOptionBody(MAPPER, "[1]")).getMessage());
        assertAws("BadRequestException", 400, () -> SesV2Json.readOptionBody(MAPPER, "{not json"));
    }

    @Test
    void parseOptionString_absentIsNull_nonStringIs400() {
        JsonNode node = json("{\"TlsPolicy\":\"REQUIRE\",\"Bad\":true}");
        assertNull(SesV2Json.parseOptionString(node.path("Missing"), "Missing"));
        assertEquals("REQUIRE", SesV2Json.parseOptionString(node.path("TlsPolicy"), "TlsPolicy"));
        assertEquals("Bad must be a JSON string.", assertAws("BadRequestException", 400,
                () -> SesV2Json.parseOptionString(node.path("Bad"), "Bad")).getMessage());
    }

    @Test
    void coerceBoolean_matchesAwsJacksonCoercion() {
        JsonNode node = json(
                "{\"t\":true,\"f\":false,\"s\":\"no\",\"z\":null,\"n\":0,\"a\":[],\"o\":{}}");
        assertTrue(SesV2Json.coerceBoolean(node.path("t")));
        assertFalse(SesV2Json.coerceBoolean(node.path("f")));
        // A JSON string coerces to true regardless of its text (probe-confirmed).
        assertTrue(SesV2Json.coerceBoolean(node.path("s")));
        assertNull(assertAws("SerializationException", 400,
                () -> SesV2Json.coerceBoolean(node.path("z"))).getMessage());
        assertEquals("NUMBER_VALUE can not be converted to a Boolean",
                assertAws("SerializationException", 400,
                        () -> SesV2Json.coerceBoolean(node.path("n"))).getMessage());
        assertEquals("Start of list found where not expected",
                assertAws("SerializationException", 400,
                        () -> SesV2Json.coerceBoolean(node.path("a"))).getMessage());
        assertEquals("Start of structure or map found where not expected.",
                assertAws("SerializationException", 400,
                        () -> SesV2Json.coerceBoolean(node.path("o"))).getMessage());
    }

    @Test
    void unexpectedStartError_distinguishesListFromStructure() {
        assertEquals("Start of list found where not expected",
                SesV2Json.unexpectedStartError(json("[]")).getMessage());
        assertEquals("Start of structure or map found where not expected.",
                SesV2Json.unexpectedStartError(json("{}")).getMessage());
        assertEquals("Start of structure or map found where not expected.",
                SesV2Json.unexpectedStartError(json("1")).getMessage());
    }

    @Test
    void stringMemberOrAbsent_absentAndNullAreNull_wrongTypeIsWireError() {
        JsonNode node = json("{\"s\":\"x\",\"z\":null,\"n\":1}");
        assertEquals("x", SesV2Json.stringMemberOrAbsent(node, "s"));
        assertNull(SesV2Json.stringMemberOrAbsent(node, "z"));
        assertNull(SesV2Json.stringMemberOrAbsent(node, "missing"));
        assertAws("SerializationException", 400, () -> SesV2Json.stringMemberOrAbsent(node, "n"));
    }

    @Test
    void stringArrayOrAbsent_keepsEmptyListDistinctFromAbsent() {
        JsonNode node = json("{\"a\":[\"x\",\"y\"],\"e\":[],\"z\":null,\"s\":\"x\",\"m\":[1]}");
        assertEquals(List.of("x", "y"), SesV2Json.stringArrayOrAbsent(node, "a"));
        assertEquals(List.of(), SesV2Json.stringArrayOrAbsent(node, "e"));
        assertNull(SesV2Json.stringArrayOrAbsent(node, "z"));
        assertNull(SesV2Json.stringArrayOrAbsent(node, "missing"));
        assertAws("SerializationException", 400, () -> SesV2Json.stringArrayOrAbsent(node, "s"));
        assertAws("SerializationException", 400, () -> SesV2Json.stringArrayOrAbsent(node, "m"));
    }

    @Test
    void intMemberOrAbsent_rejectsNonIntegralAndOutOfRange() {
        JsonNode node = json("{\"i\":5,\"f\":1.5,\"big\":1099511627776,\"s\":\"5\",\"z\":null}");
        assertEquals(5, SesV2Json.intMemberOrAbsent(node, "i"));
        assertNull(SesV2Json.intMemberOrAbsent(node, "z"));
        assertNull(SesV2Json.intMemberOrAbsent(node, "missing"));
        assertAws("SerializationException", 400, () -> SesV2Json.intMemberOrAbsent(node, "f"));
        assertAws("SerializationException", 400, () -> SesV2Json.intMemberOrAbsent(node, "big"));
        assertAws("SerializationException", 400, () -> SesV2Json.intMemberOrAbsent(node, "s"));
    }

    @Test
    void readRequiredStringField_missingNullOrNonStringIsRequiredError() {
        JsonNode node = json("{\"PoolName\":\"p\",\"Bad\":1,\"Nil\":null}");
        assertEquals("p", SesV2Json.readRequiredStringField(node, "PoolName"));
        for (String field : List.of("Missing", "Bad", "Nil")) {
            assertEquals(field + " is required.", assertAws("BadRequestException", 400,
                    () -> SesV2Json.readRequiredStringField(node, field)).getMessage());
        }
    }
}
