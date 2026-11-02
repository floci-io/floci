package io.github.hectorvent.floci.services.lambdaweb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A resource policy as Lambda writes it back (measured, us-east-1). PutResourcePolicy answers and keeps the
 * document in its own compact form: the elements in a fixed order, a missing Version as {@code 2008-10-17}, a
 * statement object as a list of one, the effect capitalized, a list of one value written as the value, condition
 * values as text with numbers as written, an operator without keys left out, and the operators in the order a
 * {@link HashMap} filled in name order holds them. GetResourcePolicy also names an account id by its root ARN and
 * drops repeated principals. AWS orders the keys of a condition, and in GetResourcePolicy the principals, at random
 * on every call, so the keys stay as written and the principals as PutResourcePolicy writes them.
 */
final class LambdaWebPolicyText {

    private static final String DEFAULT_VERSION = "2008-10-17";
    private static final List<String> STATEMENT_KEYS = List.of(
            "Sid", "Effect", "Principal", "NotPrincipal", "Action", "NotAction", "Resource", "NotResource", "Condition");
    private static final List<String> PRINCIPAL_TYPES = List.of("AWS", "CanonicalUser", "Federated", "Service");

    private LambdaWebPolicyText() {
    }

    /** The policy as PutResourcePolicy answers and keeps it. {@code policy} has passed the policy checks. */
    static String kept(ObjectMapper mapper, String policy) {
        JsonNode document = parse(mapper.reader()
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES), policy);
        ObjectNode kept = mapper.createObjectNode();
        kept.set("Version", document.has("Version") ? document.get("Version") : TextNode.valueOf(DEFAULT_VERSION));
        if (document.has("Id")) {
            kept.set("Id", document.get("Id"));
        }
        JsonNode statements = document.get("Statement");
        ArrayNode keptStatements = kept.putArray("Statement");
        for (JsonNode statement : statements.isArray() ? statements : List.of(statements)) {
            keptStatements.add(statement(mapper, statement));
        }
        return write(mapper, kept);
    }

    /** The kept policy as GetResourcePolicy answers it. */
    static String read(ObjectMapper mapper, String kept, String partition) {
        JsonNode document = parse(mapper.reader(), kept);
        for (JsonNode statement : document.path("Statement")) {
            for (String member : List.of("Principal", "NotPrincipal")) {
                if (!(statement.get(member) instanceof ObjectNode principal)) {
                    continue;
                }
                Iterator<Map.Entry<String, JsonNode>> types = principal.fields();
                while (types.hasNext()) {
                    Map.Entry<String, JsonNode> type = types.next();
                    LinkedHashSet<String> distinct = new LinkedHashSet<>();
                    for (String value : texts(type.getValue())) {
                        boolean account = "AWS".equals(type.getKey())
                                && LambdaWebPolicyPrincipals.ACCOUNT_ID.matcher(value).matches();
                        distinct.add(account ? AwsArnUtils.Arn.global(partition, "iam", value, "root").toString() : value);
                    }
                    type.setValue(written(mapper, List.copyOf(distinct)));
                }
            }
        }
        return write(mapper, document);
    }

    private static ObjectNode statement(ObjectMapper mapper, JsonNode statement) {
        ObjectNode kept = mapper.createObjectNode();
        for (String key : STATEMENT_KEYS) {
            JsonNode value = statement.get(key);
            if (value == null) {
                continue;
            }
            kept.set(key, switch (key) {
                case "Effect" -> TextNode.valueOf(value.textValue().equalsIgnoreCase("Allow") ? "Allow" : "Deny");
                case "Principal", "NotPrincipal" -> principal(mapper, value);
                case "Condition" -> condition(mapper, value);
                default -> values(mapper, value);
            });
        }
        return kept;
    }

    private static JsonNode principal(ObjectMapper mapper, JsonNode principal) {
        if (!principal.isObject()) {
            return principal;
        }
        ObjectNode kept = mapper.createObjectNode();
        for (String type : PRINCIPAL_TYPES) {
            if (principal.has(type)) {
                kept.set(type, values(mapper, principal.get(type)));
            }
        }
        return kept;
    }

    private static JsonNode condition(ObjectMapper mapper, JsonNode condition) {
        if (!condition.isObject()) {
            return condition;
        }
        Map<String, JsonNode> byName = new TreeMap<>();
        condition.fields().forEachRemaining(operator -> byName.put(operator.getKey(), operator.getValue()));
        Map<String, ObjectNode> operators = new HashMap<>();
        byName.forEach((operator, keyValues) -> {
            if (keyValues.isObject() && !keyValues.isEmpty()) {
                ObjectNode keys = mapper.createObjectNode();
                keyValues.fields().forEachRemaining(key -> keys.set(key.getKey(), values(mapper, key.getValue())));
                operators.put(operator, keys);
            }
        });
        ObjectNode kept = mapper.createObjectNode();
        operators.forEach(kept::set);
        return kept;
    }

    /** A value or a list of values, kept as text, the list of one written as its value. */
    private static JsonNode values(ObjectMapper mapper, JsonNode node) {
        return node.isNull() ? node : written(mapper, texts(node));
    }

    private static JsonNode written(ObjectMapper mapper, List<String> values) {
        if (values.size() == 1) {
            return TextNode.valueOf(values.getFirst());
        }
        ArrayNode list = mapper.createArrayNode();
        values.forEach(list::add);
        return list;
    }

    private static List<String> texts(JsonNode node) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : node.isArray() ? node : List.of(node)) {
            values.add(item.asText());
        }
        return values;
    }

    private static JsonNode parse(ObjectReader reader, String policy) {
        try {
            return reader.readTree(policy);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A checked policy is JSON", e);
        }
    }

    private static String write(ObjectMapper mapper, JsonNode document) {
        try {
            return mapper.writeValueAsString(document);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A kept policy is written as JSON", e);
        }
    }
}
