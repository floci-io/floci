package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;

import java.util.UUID;

/**
 * The per-provision context every resource handler drew from: the template engine (for resolving
 * intrinsic functions in properties) plus the region/account/stack it is being created in. The
 * two helpers are lifted verbatim from {@code CloudFormationResourceProvisioner}'s private methods
 * so extracted provisioners produce byte-identical physical ids and resolved values.
 */
public record ProvisionContext(CloudFormationTemplateEngine engine, String region,
                               String accountId, String stackName) {

    /** Resolves an optional property through the engine, or null when absent/explicitly null. */
    public String resolveOptional(JsonNode props, String name) {
        if (props == null || !props.has(name) || props.get(name).isNull()) {
            return null;
        }
        return engine.resolve(props.get(name));
    }

    /** Generates a CloudFormation-style physical name: {@code <stack>-<logicalId>-<suffix>}. */
    public String generatePhysicalName(String logicalId, int maxLength, boolean lowercase) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String base = stackName + "-" + logicalId;
        if (lowercase) {
            base = base.toLowerCase();
        }
        String name = base + "-" + suffix;
        if (maxLength > 0 && name.length() > maxLength) {
            // Truncate the descriptive prefix but always keep the trailing uniqueness token. When a
            // stack's name approaches the length limit, distinct logical resources still get distinct
            // physical names — CloudFormation preserves the random suffix when it shortens a generated
            // name. Truncating the whole string (suffix included) would collapse every such resource
            // onto one name and break Ref/GetAtt-based lookup (e.g. a custom resource's ServiceToken
            // resolving to the wrong Lambda).
            int keep = Math.max(0, maxLength - suffix.length() - 1);
            String prefix = base.length() > keep ? base.substring(0, keep) : base;
            while (prefix.endsWith("-")) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
            name = prefix.isEmpty() ? suffix : prefix + "-" + suffix;
        }
        return name;
    }
}
