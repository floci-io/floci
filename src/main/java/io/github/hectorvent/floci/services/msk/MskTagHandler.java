package io.github.hectorvent.floci.services.msk;

import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.common.V1Tags;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * MSK's tag endpoints, which AWS puts on {@code /v1/tags/{resourceArn}}.
 *
 * <p>A separate bean rather than {@code MskService} implementing {@link TagHandler} itself:
 * a class-level {@link V1Tags} on the service would strip its {@code @Default} qualifier and
 * break every plain {@code @Inject MskService} injection point, while adding {@code @Default}
 * back would make MSK reachable on {@code /tags/{arn}} as well, which AWS does not define.
 *
 * <p>MSK's wire shape matches every {@link TagHandler} default - a lowercase {@code tags} map,
 * POST for TagResource, {@code tagKeys} for UntagResource, 204 on both - so nothing is
 * overridden here.
 */
@ApplicationScoped
@V1Tags
public class MskTagHandler implements TagHandler {

    private final MskService mskService;

    @Inject
    public MskTagHandler(MskService mskService) {
        this.mskService = mskService;
    }

    @Override
    public String serviceKey() {
        return "kafka";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return mskService.listTagsForResource(arn);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        mskService.tagResource(arn, tags);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        mskService.untagResource(arn, tagKeys);
    }
}
