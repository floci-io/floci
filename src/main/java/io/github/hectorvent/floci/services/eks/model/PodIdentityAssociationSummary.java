package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PodIdentityAssociationSummary(
        String clusterName,
        String namespace,
        String serviceAccount,
        String associationArn,
        String associationId,
        String ownerArn
) {
    public static PodIdentityAssociationSummary from(PodIdentityAssociation association) {
        return new PodIdentityAssociationSummary(
                association.clusterName(),
                association.namespace(),
                association.serviceAccount(),
                association.associationArn(),
                association.associationId(),
                association.ownerArn()
        );
    }
}
