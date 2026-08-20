package io.github.hectorvent.floci.services.cloudformation.model;

import java.util.List;

/**
 * Computed summary of a CloudFormation template, backing the GetTemplateSummary query action.
 * All list fields default to an empty list rather than null so callers never need a null check.
 */
public record TemplateSummary(
        String description,
        List<ParameterDeclaration> parameters,
        List<String> resourceTypes,
        String version,
        List<String> declaredTransforms,
        List<String> capabilities,
        String capabilitiesReason,
        String metadata) {

    /** One entry from the template's top level Parameters section. */
    public record ParameterDeclaration(
            String parameterKey,
            String defaultValue,
            boolean noEcho,
            String description,
            String parameterType) {
    }
}
