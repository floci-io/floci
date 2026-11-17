package io.floci.aws.services.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The {@code PutAccountName} request the account information tests send. */
final class AccountNameRequests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AccountNameRequests() {
    }

    static ObjectNode accountNameRequest(String name) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("AccountName", name);
        return request;
    }

    static ObjectNode accountNameRequest(String accountId, String name) {
        return accountNameRequest(name).put("AccountId", accountId);
    }
}
