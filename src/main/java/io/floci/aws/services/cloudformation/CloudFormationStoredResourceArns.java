package io.floci.aws.services.cloudformation;

import io.floci.aws.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What IAM asks CloudFormation of the resources a request names: the ARN of the stack or the stack set a name names,
 * whose id CloudFormation minted, so a request naming one by its name is checked on the ARN AWS checks; and the tags
 * of a stack, a stack set or a change set, which a resource-tag condition reads.
 */
@ApplicationScoped
public class CloudFormationStoredResourceArns implements StoredResourceArns {

    private final CloudFormationService stacks;
    private final StackSetService stackSets;

    @Inject
    public CloudFormationStoredResourceArns(CloudFormationService stacks, StackSetService stackSets) {
        this.stacks = stacks;
        this.stackSets = stackSets;
    }

    @Override
    public String namespace() {
        return "cloudformation";
    }

    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        if (names.size() != 1) {
            return Optional.empty();
        }
        return switch (type) {
            case "stack" -> stacks.stackArn(names.getFirst(), region);
            case "stackset" -> stackSets.stackSetArn(names.getFirst(), region);
            default -> Optional.empty();
        };
    }

    /** The tags of the stack, stack set or change set {@code arn} names, which a resource-tag condition reads. */
    @Override
    public Map<String, String> storedTags(String arn) {
        return arn.contains(":stackset/") ? stackSets.storedTags(arn) : stacks.storedTags(arn);
    }
}
