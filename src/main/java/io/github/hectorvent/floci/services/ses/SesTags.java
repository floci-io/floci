package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ses.model.Tag;

/**
 * Shared SES resource-tag validation. Lives outside the facade so the extracted domain services can
 * validate tags without depending back on {@link SesService}.
 */
final class SesTags {

    private SesTags() {}

    static void validate(Tag tag) {
        if (tag == null) {
            throw new AwsException("InvalidParameterValue", "Tag must not be null.", 400);
        }
        String key = tag.key();
        if (key == null || key.isEmpty()) {
            throw new AwsException("InvalidParameterValue", "Tag Key is required.", 400);
        }
        if (key.length() > 128) {
            throw new AwsException("InvalidParameterValue",
                    "Tag Key must be 1-128 characters.", 400);
        }
        String value = tag.value();
        if (value != null && value.length() > 256) {
            throw new AwsException("InvalidParameterValue",
                    "Tag Value must be 0-256 characters.", 400);
        }
    }
}
