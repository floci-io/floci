package io.github.hectorvent.floci.services.marketplace.model;

import java.util.List;

/** Marketplace proposal metadata resolved from the shared local Catalog state. */
public record AgreementProposalContext(
        String offerId,
        String offerSetId,
        List<ResourceReference> resources) {

    public AgreementProposalContext {
        resources = resources == null ? List.of() : List.copyOf(resources);
    }

    public record ResourceReference(String id, String type) {}
}
