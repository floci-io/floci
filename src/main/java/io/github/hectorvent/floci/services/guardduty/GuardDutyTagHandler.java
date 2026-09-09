package io.github.hectorvent.floci.services.guardduty;

import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * {@link TagHandler} implementation for GuardDuty.
 *
 * <p>ARN format: {@code arn:aws:guardduty:<region>:<account>:detector/<detectorId>}. GuardDuty's
 * tag wire shape matches every {@code TagHandler} default: a {@code tags} map in the body and a
 * {@code tagKeys} query parameter on untag.
 */
@ApplicationScoped
public class GuardDutyTagHandler implements TagHandler {

    private final GuardDutyService service;

    @Inject
    public GuardDutyTagHandler(GuardDutyService service) {
        this.service = service;
    }

    @Override
    public String serviceKey() {
        return "guardduty";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return service.listTags(arn);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        service.tagResource(arn, tags);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        service.untagResource(arn, tagKeys);
    }
}
