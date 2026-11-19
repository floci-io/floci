package io.floci.aws.services.paymentcryptography;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How the members of a request are read.
 *
 * <p>The control plane and the data plane read the members of a request the same way, so
 * they read them through one converter, and the converter is what this holds to.
 */
class PaymentCryptographyMembersTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aMemberThatIsNullIsAMemberTheRequestDoesNotCarry() throws JsonProcessingException {
        // The request carries the member as a null, which is the member it does not carry,
        // and not a member that carries the text "null".
        JsonNode request = mapper.readTree("""
                {"KeyCheckValueAlgorithm": null}""");

        Map<String, Object> members = PaymentCryptographyMembers.members(request);

        assertTrue(members.containsKey("KeyCheckValueAlgorithm"));
        assertNull(members.get("KeyCheckValueAlgorithm"));
    }

    @Test
    void aBooleanIsABooleanAndNotItsText() throws JsonProcessingException {
        JsonNode request = mapper.readTree("""
                {"Enabled": true}""");

        Map<String, Object> members = PaymentCryptographyMembers.members(request);

        assertEquals(Boolean.TRUE, members.get("Enabled"));
    }

    @Test
    void aNumberIsItsText() throws JsonProcessingException {
        JsonNode request = mapper.readTree("""
                {"KeySerialNumber": 42}""");

        Map<String, Object> members = PaymentCryptographyMembers.members(request);

        assertEquals("42", members.get("KeySerialNumber"));
    }

    @Test
    void anObjectIsAMapOfItsOwn() throws JsonProcessingException {
        JsonNode request = mapper.readTree("""
                {"DerivationData": {"SharedInformation": "0011"}}""");

        Map<String, Object> members = PaymentCryptographyMembers.members(request);

        assertEquals(Map.of("SharedInformation", "0011"), members.get("DerivationData"));
    }

    @Test
    void aListIsAListOfItsOwn() throws JsonProcessingException {
        JsonNode request = mapper.readTree("""
                {"ReplicationRegions": ["us-east-1", "eu-west-1"]}""");

        Map<String, Object> members = PaymentCryptographyMembers.members(request);

        assertEquals(List.of("us-east-1", "eu-west-1"), members.get("ReplicationRegions"));
    }

    @Test
    void aNodeThatIsNotAnObjectHasNoMembers() {
        assertTrue(PaymentCryptographyMembers.members(null).isEmpty());
        assertTrue(PaymentCryptographyMembers.members(
                mapper.getNodeFactory().textNode("not an object")).isEmpty());
    }
}
