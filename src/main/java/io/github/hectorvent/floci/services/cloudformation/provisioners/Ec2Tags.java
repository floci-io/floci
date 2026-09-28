package io.github.hectorvent.floci.services.cloudformation.provisioners;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tag reconciliation shared by the EC2 provisioners ({@code Ec2InstanceCfnProvisioner} and
 * {@code Ec2SecurityGroupCfnProvisioner}). Both drive it from the desired tags resolved via
 * {@link ProvisionContext#resolveTags}; the reconcile itself is identical.
 */
final class Ec2Tags {

    private Ec2Tags() {
    }

    /**
     * Brings a resource's tags to {@code desired}: removes the keys AWS currently has that the
     * template no longer declares, then creates or overwrites the declared ones.
     */
    static void reconcile(Ec2Service ec2Service, String region, String resourceId, Map<String, String> desired) {
        Map<String, String> current = new LinkedHashMap<>();
        for (Map<String, String> entry : ec2Service.describeTags(region, Map.of("resource-id", List.of(resourceId)))) {
            current.put(entry.get("key"), entry.get("value"));
        }
        List<String> stale = ProvisionContext.staleTagKeys(current, desired);
        if (!stale.isEmpty()) {
            List<Tag> remove = new ArrayList<>();
            for (String key : stale) {
                remove.add(new Tag(key, current.get(key)));
            }
            ec2Service.deleteTags(region, List.of(resourceId), remove);
        }
        if (!desired.isEmpty()) {
            List<Tag> add = new ArrayList<>();
            for (Map.Entry<String, String> entry : desired.entrySet()) {
                add.add(new Tag(entry.getKey(), entry.getValue()));
            }
            ec2Service.createTags(region, List.of(resourceId), add);
        }
    }
}
