package io.floci.aws.services.paymentcryptography;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.services.paymentcryptography.model.PaymentCryptographyKey;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The builders the payment cryptography tests share.
 *
 * <p>A key and a request are built the same way whichever operation is under test, so
 * they are built here once. A change to how they are built is then a change in one
 * place.
 */
final class PaymentCryptographyTestFixtures {

    private PaymentCryptographyTestFixtures() {
    }

    /**
     * A key of the algorithm and the usage, with the modes of use the test names.
     *
     * @param service the service the key is made in
     * @param algorithm the key's algorithm
     * @param usage the key's usage
     * @param modes the modes of use the key allows
     * @return the key that was made
     */
    static PaymentCryptographyKey createKey(PaymentCryptographyService service, String algorithm,
                                            String usage, String... modes) {
        return key(service, algorithm, usage, true, modes);
    }

    /**
     * A key that was made disabled, and may therefore not be used.
     *
     * @param service the service the key is made in
     * @param algorithm the key's algorithm
     * @param usage the key's usage
     * @param modes the modes of use the key allows
     * @return the key that was made
     */
    static PaymentCryptographyKey createDisabledKey(PaymentCryptographyService service, String algorithm,
                                                     String usage, String... modes) {
        return key(service, algorithm, usage, false, modes);
    }

    private static PaymentCryptographyKey key(PaymentCryptographyService service, String algorithm,
                                               String usage, boolean enabled, String... modes) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("KeyAlgorithm", algorithm);
        attributes.put("KeyClass", "SYMMETRIC_KEY");
        attributes.put("KeyUsage", usage);
        Map<String, Boolean> declared = new LinkedHashMap<>();
        for (String mode : modes) {
            declared.put(mode, Boolean.TRUE);
        }
        return service.createKey("us-east-1", attributes, declared, true, enabled, Map.of());
    }

    /**
     * A request body of the members the test names.
     *
     * @param members the members of the request
     * @return the body
     */
    static JsonNode request(Map<String, Object> members) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = mapper.createObjectNode();
        members.forEach((name, value) -> {
            if (value instanceof JsonNode json) {
                node.set(name, json);
            } else if (value instanceof Number number) {
                node.put(name, number.intValue());
            } else {
                node.put(name, String.valueOf(value));
            }
        });
        return node;
    }
}
