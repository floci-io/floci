package io.floci.aws.core.common;

import io.floci.aws.services.route53.Route53ChangeBatch;
import io.floci.aws.services.route53.Route53RecordName;
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
        List<Map<String, Object>> changes;
        try {
            changes = Route53ChangeBatch.parse(new String(body, StandardCharsets.UTF_8));
        } catch (AwsException invalidBatch) {
            // Preserve the buffered body: the controller rejects the same batch with its XML error.
            // No conditions are invented, and no mutation can pass the shared parser.
            return null;
        }
        List<String> actions = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<String> types = new ArrayList<>();
        for (Map<String, Object> change : changes) {
            actions.add((String) change.get("action"));
            ResourceRecordSet record = (ResourceRecordSet) change.get("rrs");
            names.add(normalizedName(record.getName()));
            types.add(record.getType().toUpperCase(Locale.ROOT));
        }
        return Map.of("route53:ChangeResourceRecordSetsActions", actions,
                "route53:ChangeResourceRecordSetsNormalizedRecordNames", names,
                "route53:ChangeResourceRecordSetsRecordTypes", types);
    }

    static String normalizedName(String name) {
        return Route53RecordName.conditionName(name);
    }
}
