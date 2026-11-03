package io.github.hectorvent.floci.services.controltower;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * {@link TagHandler} implementation for Control Tower landing zones and enabled controls.
 *
 * <p>ARN formats: {@code arn:<partition>:controltower:<region>:<account>:landingzone/<id>} and
 * {@code arn:<partition>:controltower:<region>:<account>:enabledcontrol/<id>}. Control Tower's tag wire
 * shape matches every {@code TagHandler} default: a {@code tags} map in the body and a
 * {@code tagKeys} query parameter on untag.
 */
@ApplicationScoped
public class ControlTowerTagHandler implements TagHandler {

    private final ControlTowerService landingZones;
    private final ControlTowerControlService controls;

    @Inject
    public ControlTowerTagHandler(ControlTowerService landingZones, ControlTowerControlService controls) {
        this.landingZones = landingZones;
        this.controls = controls;
    }

    @Override
    public String serviceKey() {
        return "controltower";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return isLandingZone(arn) ? landingZones.listLandingZoneTags(arn) : controls.listTags(arn);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        if (isLandingZone(arn)) {
            landingZones.tagLandingZone(arn, tags);
        } else {
            controls.tagResource(arn, tags);
        }
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        if (isLandingZone(arn)) {
            landingZones.untagLandingZone(arn, tagKeys);
        } else {
            controls.untagResource(arn, tagKeys);
        }
    }

    private static boolean isLandingZone(String arn) {
        String resource = AwsArnUtils.parse(arn).resource();
        if (resource.startsWith("landingzone/")) {
            return true;
        }
        if (resource.startsWith("enabledcontrol/")) {
            return false;
        }
        throw new AwsException("ResourceNotFoundException", "The requested resource does not exist: " + arn, 404);
    }
}
