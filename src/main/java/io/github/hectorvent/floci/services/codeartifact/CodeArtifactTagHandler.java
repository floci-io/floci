package io.github.hectorvent.floci.services.codeartifact;

import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.common.V1Tags;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/**
 * CodeArtifact's {@code ListTagsForResource}, which AWS puts on the bare
 * {@code /v1/tags} path with {@code resourceArn} as a query parameter rather than a path
 * segment. {@code TagResource}/{@code UntagResource} don't collide with anything and stay on
 * {@code CodeArtifactController}'s own {@code /v1/tag} and {@code /v1/untag} routes; only the
 * list operation is registered here so {@code V1TagsController} can resolve it.
 *
 * <p>A separate bean rather than {@code CodeArtifactService} implementing {@link TagHandler}
 * itself, for the same reason as {@code MskTagHandler}: a class-level {@link V1Tags} would
 * strip the service's {@code @Default} qualifier and break plain {@code @Inject
 * CodeArtifactService} injection points.
 *
 * <p>CodeArtifact is the one {@link TagHandler} on this dispatcher with a list-shaped tag body
 * using lowercase {@code key}/{@code value} entry members rather than the AWS-typical
 * capitalized {@code Key}/{@code Value}.
 */
@ApplicationScoped
@V1Tags
public class CodeArtifactTagHandler implements TagHandler {

    private final CodeArtifactService codeArtifactService;

    @Inject
    public CodeArtifactTagHandler(CodeArtifactService codeArtifactService) {
        this.codeArtifactService = codeArtifactService;
    }

    @Override
    public String serviceKey() {
        return "codeartifact";
    }

    @Override
    public boolean tagsBodyIsList() {
        return true;
    }

    @Override
    public String tagEntryKeyName() {
        return "key";
    }

    @Override
    public String tagEntryValueName() {
        return "value";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return codeArtifactService.listTagsForResource(arn);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        codeArtifactService.tagResource(arn, tags);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        codeArtifactService.untagResource(arn, tagKeys);
    }
}
