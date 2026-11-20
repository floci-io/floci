package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.dataformat.cbor.CBORParser;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RpcV2CborCodecTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper CBOR = new ObjectMapper(new CBORFactory());
    private static final String MODEL = """
            {
              "aliases":{"TestService":"test","TestAlias":"test"},
              "services":{"test":{
                "operations":{"RoundTrip":{"input":"Payload","output":"Payload","hasOutput":true}},
                "shapes":{
                  "Payload":{"type":"structure","members":{
                    "Body":{"shape":"Blob"},"Nested":{"shape":"Nested"},
                    "CreatedDate":{"shape":"Time"},
                    "IsoCreated":{"shape":"Time","timestampFormat":"iso8601"},
                    "DefaultIso":{"shape":"IsoTime"},
                    "EpochOverride":{"shape":"IsoTime","timestampFormat":"unixTimestamp"},
                    "UserStrings":{"shape":"Strings"},"UserNumbers":{"shape":"Numbers"}}},
                  "Nested":{"type":"structure","members":{
                    "Items":{"shape":"Blobs"},"Lookup":{"shape":"BlobMap"}}},
                  "Blob":{"type":"blob"},"Time":{"type":"timestamp"},
                  "IsoTime":{"type":"timestamp","timestampFormat":"iso8601"},
                  "Blobs":{"type":"list","member":"Blob"},
                  "BlobMap":{"type":"map","value":"Blob"},
                  "Strings":{"type":"map","value":"Text"},
                  "Numbers":{"type":"map","value":"Number"},
                  "Text":{"type":"string"},"Number":{"type":"double"}
                }
              }}
            }
            """;

    @Test
    void blobsRoundTripThroughStructuresListsAndMapsWithoutMutatingPayloads() throws Exception {
        RpcV2CborCodec codec = RpcV2CborCodec.parse(JSON.readTree(MODEL));
        JsonNode original = JSON.readTree("""
                {"Body":"AP8B","Nested":{"Items":["AQID","BAU="],"Lookup":{"Data":"Bgc="}},
                 "UserStrings":{"Data":"AQID","Timestamp":"1700000000"}}
                """);
        JsonNode snapshot = original.deepCopy();

        JsonNode wire = CBOR.readTree(codec.encodeResponse("TestService", "RoundTrip", original));

        assertTrue(wire.path("Body").isBinary());
        assertArrayEquals(new byte[]{0, (byte) 255, 1}, wire.path("Body").binaryValue());
        assertArrayEquals(new byte[]{1, 2, 3}, wire.at("/Nested/Items/0").binaryValue());
        assertArrayEquals(new byte[]{4, 5}, wire.at("/Nested/Items/1").binaryValue());
        assertArrayEquals(new byte[]{6, 7}, wire.at("/Nested/Lookup/Data").binaryValue());
        assertTrue(wire.at("/UserStrings/Data").isTextual());
        assertEquals(snapshot, original, "Encoding must not rewrite the JSON handler's payload");
        JsonNode wireSnapshot = wire.deepCopy();
        assertEquals(original, codec.decodeRequest("TestAlias", "RoundTrip", wire));
        assertEquals(wireSnapshot, wire, "Decoding must not rewrite the caller's CBOR tree");
    }

    @Test
    void modelTimestampsUseEpochSecondsTagOneIncludingIsoFormatOverrides() throws Exception {
        RpcV2CborCodec codec = RpcV2CborCodec.parse(JSON.readTree(MODEL));
        JsonNode response = JSON.readTree("""
                {"CreatedDate":1700000000.125,"IsoCreated":"2023-11-14T22:13:20.125Z",
                 "DefaultIso":"2023-11-14T22:13:20.125Z","EpochOverride":1700000000.125,
                 "UserNumbers":{"Timestamp":1700000000},"UserStrings":{"Data":"AQID"}}
                """);
        JsonNode snapshot = response.deepCopy();

        byte[] encoded = codec.encodeResponse("TestAlias", "RoundTrip", response);
        JsonNode wire = CBOR.readTree(encoded);

        for (String member : new String[]{"CreatedDate", "IsoCreated", "DefaultIso", "EpochOverride"}) {
            assertEquals(1, tagOf(encoded, member), member + " must carry CBOR tag 1");
            assertEquals(0, new BigDecimal("1700000000.125").compareTo(wire.path(member).decimalValue()));
        }
        assertEquals(-1, tagOf(encoded, "Timestamp"), "A user map key must not imply a timestamp shape");
        assertTrue(wire.at("/UserStrings/Data").isTextual());
        assertEquals(snapshot, response);
    }

    @Test
    void requestTimestampsAdaptToTheJsonModelsFormatWithoutLosingFractionalSeconds() throws Exception {
        RpcV2CborCodec codec = RpcV2CborCodec.parse(JSON.readTree(MODEL));
        ObjectNode request = JSON.createObjectNode();
        for (String member : new String[]{"CreatedDate", "IsoCreated", "DefaultIso", "EpochOverride"}) {
            request.put(member, 1700000000.125);
        }
        JsonNode snapshot = request.deepCopy();

        JsonNode decoded = codec.decodeRequest("TestService", "RoundTrip", request);

        assertEquals("2023-11-14T22:13:20.125Z", decoded.path("IsoCreated").asText());
        assertEquals("2023-11-14T22:13:20.125Z", decoded.path("DefaultIso").asText());
        assertEquals(1700000000.125, decoded.path("CreatedDate").asDouble());
        assertEquals(1700000000.125, decoded.path("EpochOverride").asDouble());
        assertEquals(snapshot, request);
    }

    private static int tagOf(byte[] bytes, String member) throws Exception {
        try (CBORParser parser = (CBORParser) CBOR.getFactory().createParser(bytes)) {
            while (parser.nextToken() != null) {
                if (member.equals(parser.currentName()) && parser.currentToken().isScalarValue()) {
                    return parser.getCurrentTag();
                }
            }
        }
        throw new AssertionError("Missing CBOR member: " + member);
    }
}
