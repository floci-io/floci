package io.github.hectorvent.floci.services.cloudformation.provisioners;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tag reconciliation shared by the EC2 provisioners ({@code Ec2InstanceCfnProvisioner},
 * {@code Ec2SecurityGroupCfnProvisioner} and {@code Ec2VpcCfnProvisioner}). They drive it from the
 * desired tags resolved via {@link ProvisionContext#resolveTags}; the reconcile itself is identical.
 */
final class Ec2Tags {

    private Ec2Tags() {
    }

    static List<Tag> validatedTags(Map<String, String> desired) {
        List<Tag> tags = desired.entrySet().stream()
                .map(entry -> new Tag(entry.getKey(), entry.getValue())).toList();
        Ec2Service.validateUserTagKeys(tags);
        return tags;
    }

    /**
     * The user tags EC2 currently holds for the resource, keyed by tag key in DescribeTags order.
     * Reserved {@code aws:} keys are never part of the user tag set, so neither {@link #reconcile}
     * nor a rollback snapshot taken from this map can try to write one.
     */
    static Map<String, String> current(Ec2Service ec2Service, String region, String resourceId) {
        Map<String, String> current = new LinkedHashMap<>();
        for (Map<String, String> entry : ec2Service.describeTags(region, Map.of("resource-id", List.of(resourceId)))) {
            if (!Ec2Service.isAwsTagKey(entry.get("key"))) {
                current.put(entry.get("key"), entry.get("value"));
            }
        }
        return current;
    }

    /**
     * Brings a resource's tags to {@code desired}: removes the keys AWS currently has that the
     * template no longer declares, then creates or overwrites the declared ones.
     */
    static void reconcile(Ec2Service ec2Service, String region, String resourceId, Map<String, String> desired) {
        List<Tag> add = validatedTags(desired);
        Map<String, String> current = current(ec2Service, region, resourceId);
        List<String> stale = ProvisionContext.staleTagKeys(current, desired);
        if (!stale.isEmpty()) {
            List<Tag> remove = new ArrayList<>();
            for (String key : stale) {
                remove.add(new Tag(key, current.get(key)));
            }
            ec2Service.deleteTags(region, List.of(resourceId), remove);
        }
        if (!add.isEmpty()) {
            ec2Service.createTags(region, List.of(resourceId), add);
        }
    }
}
