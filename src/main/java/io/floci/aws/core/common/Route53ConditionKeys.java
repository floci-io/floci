package io.floci.aws.core.common;

import io.floci.aws.services.route53.Route53ChangeBatch;
import io.floci.aws.services.route53.model.ResourceRecordSet;
import jakarta.ws.rs.container.ContainerRequestContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Multi-valued conditions for the exact batch the Route 53 controller applies. */
final class Route53ConditionKeys {

    private Route53ConditionKeys() {}

    static Map<String, List<String>> resolve(String action, ContainerRequestContext ctx) {
        if (!"route53:ChangeResourceRecordSets".equals(action)) {
            return null;
        }
        byte[] body = IamConditionContextResolver.bufferEntity(ctx);
        if (body == null) {
            return null;
        }
        List<String> actions = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> types = new ArrayList<>();
        for (Map<String, Object> change : Route53ChangeBatch.parse(new String(body, StandardCharsets.UTF_8))) {
            actions.add((String) change.get("action"));
            ResourceRecordSet record = (ResourceRecordSet) change.get("rrs");
            names.add(normalizedName(record.getName()));
            types.add(record.getType().toUpperCase(Locale.ROOT));
        }
        return Map.of("route53:ChangeResourceRecordSetsActions", actions,
                "route53:ChangeResourceRecordSetsNormalizedRecordNames", names,
                "route53:ChangeResourceRecordSetsRecordTypes", types);
    }

    // Route 53 Developer Guide: specifying-conditions-route53.html and DomainNameFormat.html.
    static String normalizedName(String name) {
        String source = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
        StringBuilder normalized = new StringBuilder();
        for (int index = 0; index < source.length(); index++) {
            char value = source.charAt(index);
            boolean escaped = value == '\\' && index + 3 < source.length()
                    && source.substring(index + 1, index + 4).chars().allMatch(c -> c >= '0' && c <= '7');
            if (escaped) {
                value = (char) Integer.parseInt(source.substring(index + 1, index + 4), 8);
                index += 3;
            }
            if (value >= 'A' && value <= 'Z') {
                value = (char) (value + ('a' - 'A'));
            }
            if (value >= 'a' && value <= 'z' || value >= '0' && value <= '9' || value == '-'
                    || value == '_' || value == '.' && !escaped) {
                normalized.append(value);
            } else {
                normalized.append(String.format(Locale.ROOT, "\\%03o", (int) value));
            }
        }
        return normalized.toString();
    }
}
