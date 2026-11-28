package io.floci.aws.services.codegurureviewer;

import io.floci.aws.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** What IAM asks CodeGuru Reviewer of the resources a request names: the tags of the association an ARN names. */
@ApplicationScoped
public class CodeGuruReviewerStoredResourceArns implements StoredResourceArns {

    private final CodeGuruReviewerService codeGuruReviewer;

    @Inject
    public CodeGuruReviewerStoredResourceArns(CodeGuruReviewerService codeGuruReviewer) {
        this.codeGuruReviewer = codeGuruReviewer;
    }

    @Override
    public String namespace() {
        return "codeguru-reviewer";
    }

    /** A request names an association by its ARN, so there is nothing to find. */
    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return Optional.empty();
    }

    @Override
    public Map<String, String> storedTags(String arn) {
        return StoredResourceArns.withEmptyValues(codeGuruReviewer.storedTags(arn));
    }
}
