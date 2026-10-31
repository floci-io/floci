package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;

/**
 * The form IAM's constraint violations take, which is Smithy's wording with the offending value
 * left out: {@code Value at 'userName' failed to satisfy constraint: Member must have length less
 * than or equal to 64}. Every capture of an IAM validation message since 2025-03 reads that way,
 * where the older ones quoted the value, and STS still quotes it, so this names IAM's side of that
 * split in one place. {@code docs/services/iam.md} records the reading and the captures behind it.
 *
 * <p>A message AWS itself writes with the value is not forced through here: a list checked as a
 * whole carries the list, and a required member the request leaves out reads
 * {@code Value null at '<member>'}, which no capture here covers either way.
 */
final class IamValidationErrors {

    /** IAM's form, for a check that builds its own violations rather than throwing one. */
    static final SmithyValidationErrors.Violations MESSAGES =
            SmithyValidationErrors.Violations.WITHOUT_VALUES;

    private IamValidationErrors() {
    }

    /**
     * The 400 {@code ValidationError} for one violation of {@code constraint} by {@code value} at
     * {@code path}. The value is taken even though IAM's form leaves it out, so that the one place
     * that decides the form is the only place that changes if a capture ever moves it back.
     */
    static AwsException validationError(String path, String value, String constraint) {
        return SmithyValidationErrors.validationError(MESSAGES.constraint(path, value, constraint));
    }
}
