package io.floci.aws.services.controltower;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What IAM asks Control Tower of the resources a request names: the tags of a landing zone, which
 * {@link ControlTowerService} holds, or of an enabled control, which {@link ControlTowerControlService} holds. One bean
 * answers the {@code controltower} namespace, so the lookup is here and each service reads its own records.
 */
@ApplicationScoped
public class ControlTowerStoredResourceArns implements StoredResourceArns {

    private static final String LANDING_ZONE_RESOURCE = "landingzone/";
    private static final String ENABLED_CONTROL_RESOURCE = "enabledcontrol/";

    private final RegionResolver regionResolver;
    private final ControlTowerService landingZones;
    private final ControlTowerControlService controls;

    @Inject
    public ControlTowerStoredResourceArns(RegionResolver regionResolver, ControlTowerService landingZones,
                                          ControlTowerControlService controls) {
        this.regionResolver = regionResolver;
        this.landingZones = landingZones;
        this.controls = controls;
    }

    @Override
    public String namespace() {
        return "controltower";
    }

    /** A request names a landing zone or an enabled control by its ARN or the id it ends in: nothing to find. */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return Optional.empty();
    }

    /**
     * The tags of the landing zone or the enabled control whose ARN is {@code arn}, in the request's account, where
     * the tag handlers read: an ARN of another account names none. An enabled baseline holds no tags in Floci, so its
     * ARN reads none. Tags are only ever replaced by a new map, so the read is of one whole set.
     */
    @Override
    public Map<String, String> storedTags(String arn) {
        if (!AwsArnUtils.isArnFor(arn, "controltower")) {
            return Map.of();
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        String account = regionResolver.getAccountId();
        if (!account.equals(parsed.accountId()) || parsed.region().isEmpty()) {
            return Map.of();
        }
        if (parsed.resource().startsWith(LANDING_ZONE_RESOURCE)) {
            return landingZones.storedLandingZoneTags(account, parsed.region(), arn);
        }
        if (parsed.resource().startsWith(ENABLED_CONTROL_RESOURCE)) {
            return controls.storedTags(parsed.region(), arn);
        }
        return Map.of();
    }
}
