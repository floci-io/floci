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
 * whole carries the list. A required member the request leaves out used to read
 * {@code Value null at '<member>'} for want of a capture either way; one arrived on 2026-10-31,
 * answering an omitted {@code Status} as {@code Value at 'status' failed to satisfy constraint:
 * Member must not be null}. That capture covers {@code Status} alone. Dropping the word for every
 * other member this service leaves out is Floci's reading of it rather than a recorded answer, on
 * the grounds that an absent member has no value to carry, and {@link #notNull} is where that is
 * decided, so a capture that ever splits the two moves one place.
 */
final class IamValidationErrors {

    /** IAM's form, for a check that builds its own violations rather than throwing one. */
    static final SmithyValidationErrors.Violations MESSAGES =
            SmithyValidationErrors.Violations.WITHOUT_VALUES;

    private IamValidationErrors() {
    }

    /**
     * The 400 {@code ValidationError} for a required member the request leaves out, named with no
     * value and no {@code null} beside it. Captured from a real account on 2026-10-31 against the
     * {@code Status} the four credential operations share, and read here as the form every other
     * omitted member takes, which nothing captured covers.
     */
    static AwsException notNull(String path) {
        return SmithyValidationErrors.validationError(MESSAGES.notNull(path));
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
