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
        String name = stackName + "-" + logicalId + "-" + suffix;
        if (lowercase) {
            name = name.toLowerCase();
        }
        if (maxLength > 0 && name.length() > maxLength) {
            name = name.substring(0, maxLength);
        }
        return name;
    }
}
