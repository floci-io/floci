package io.github.hectorvent.floci.services.cloudformation.provisioners;

import io.github.hectorvent.floci.services.cloudformation.model.StackResource;

import java.util.Map;

/**
 * Keeps a resource's resolved create-only inputs and the record a replacement displaced.
 * Provisioners own the input format and comparison; this helper preserves their attribute names.
 * {@link ReplacementCleanup} restores public attributes only, so a rollback restores this record
 * separately, even when deleting the replacement fails after its identity has been reverted.
 */
public final class CreateOnlyInputs {

    private final String currentAttribute;
    private final String priorAttribute;

    public CreateOnlyInputs(String currentAttribute, String priorAttribute) {
        this.currentAttribute = currentAttribute;
        this.priorAttribute = priorAttribute;
    }

    public String read(Map<String, String> attributes) {
        return attributes.get(currentAttribute);
    }

    /** Records the created entity's inputs, keeping the prior record until the update ends. */
    public void record(StackResource resource, ProvisionContext ctx, Map<String, String> attributesBefore,
                       String inputs) {
        if (ctx.isUpdate()) {
            resource.getAttributes().put(priorAttribute, attributesBefore.getOrDefault(currentAttribute, ""));
        }
        resource.getAttributes().put(currentAttribute, inputs);
    }

    public boolean hasPrior(StackResource resource) {
        return resource.getAttributes().containsKey(priorAttribute);
    }

    /** Restores the record, including its absence on a resource that predates input tracking. */
    public void restorePrior(StackResource resource) {
        String prior = resource.getAttributes().remove(priorAttribute);
        if (prior != null) {
            if (prior.isEmpty()) {
                resource.getAttributes().remove(currentAttribute);
            } else {
                resource.getAttributes().put(currentAttribute, prior);
            }
        }
    }

    public void clearPrior(StackResource resource) {
        resource.getAttributes().remove(priorAttribute);
    }
}
