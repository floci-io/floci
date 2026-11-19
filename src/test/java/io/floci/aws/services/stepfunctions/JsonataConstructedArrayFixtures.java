package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class JsonataConstructedArrayFixtures {

    private JsonataConstructedArrayFixtures() {
    }

    static ObjectNode input(ObjectMapper mapper, int count) {
        ObjectNode input = mapper.createObjectNode();
        ArrayNode items = input.putArray("Items");
        for (int i = 0; i < count; i++) {
            items.addObject();
        }
        return input;
    }

    static ObjectNode states(ObjectMapper mapper, int count) {
        return mapper.createObjectNode().set("input", input(mapper, count));
    }
}
