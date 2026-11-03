package io.github.hectorvent.floci.services.controltower;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * {@link TagHandler} implementation for Control Tower landing zones and enabled controls.
 *
 * <p>ARN formats: {@code arn:<partition>:controltower:<region>:<account>:landingzone/<id>} and
 * {@code arn:<partition>:controltower:<region>:<account>:enabledcontrol/<id>}. The wire shape
 * matches the {@code TagHandler} defaults. Validation is strict because the model requires
 * {@code tags} on TagResource and {@code tagKeys} on UntagResource. Resources are looked up in the
 * caller's account and Region, so an ARN naming another one is not found.
 */
@ApplicationScoped
public class ControlTowerTagHandler implements TagHandler {

    private final ControlTowerService landingZones;
    private final ControlTowerControlService controls;
    private final RequestContext requestContext;

    @Inject
    public ControlTowerTagHandler(ControlTowerService landingZones, ControlTowerControlService controls,
                                  RequestContext requestContext) {
        this.landingZones = landingZones;
        this.controls = controls;
        this.requestContext = requestContext;
    }

    @Override
    public String serviceKey() {
        return "controltower";
    }

    @Override
    public boolean strictTagValidation() {
        return true;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return isLandingZone(arn)
                ? landingZones.listLandingZoneTags(requestContext.getAccountId(), region, arn)
                : controls.listTags(region, arn);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        if (isLandingZone(arn)) {
            landingZones.tagLandingZone(requestContext.getAccountId(), region, arn, tags);
        } else {
            controls.tagResource(region, arn, tags);
        }
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        if (isLandingZone(arn)) {
            landingZones.untagLandingZone(requestContext.getAccountId(), region, arn, tagKeys);
        } else {
            controls.untagResource(region, arn, tagKeys);
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
