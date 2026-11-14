package io.floci.aws.services.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The valid {@code PutContactInformation} request the primary contact tests start from. */
final class ContactInformationRequests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ContactInformationRequests() {
    }

    static ObjectNode contactInformationRequest() {
        ObjectNode contact = MAPPER.createObjectNode();
        contact.put("FullName", "Jane Doe");
        contact.put("AddressLine1", "1 Main St");
        contact.put("City", "Seattle");
        contact.put("StateOrRegion", "WA");
        contact.put("PostalCode", "98101");
        contact.put("CountryCode", "US");
        contact.put("PhoneNumber", "+12065550100");
        ObjectNode request = MAPPER.createObjectNode();
        request.set("ContactInformation", contact);
        return request;
    }

    static ObjectNode contactOf(ObjectNode request) {
        return (ObjectNode) request.get("ContactInformation");
    }
}
