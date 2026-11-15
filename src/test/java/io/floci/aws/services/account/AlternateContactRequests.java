package io.floci.aws.services.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The valid {@code PutAlternateContact} request the alternate contact tests start from. */
final class AlternateContactRequests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AlternateContactRequests() {
    }

    static ObjectNode alternateContactRequest() {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("AlternateContactType", "SECURITY");
        request.put("Name", "Security Team");
        request.put("Title", "Security");
        request.put("EmailAddress", "security@example.com");
        request.put("PhoneNumber", "+1 555 0100");
        return request;
    }
}
