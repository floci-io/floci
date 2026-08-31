package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * Account-level provisioning details, set by PutAccountDetails and surfaced under GetAccount's
 * {@code Details}. Per region and present-when-set: GetAccount omits the block entirely until
 * PutAccountDetails is called for the region.
 *
 * <p>{@code reviewStatus}/{@code caseId} back the AWS {@code ReviewDetails} sub-structure. AWS runs a
 * real production-access review; Floci has no sandbox, so it reports a granted review.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountDetails(String mailType,
                             String websiteUrl,
                             String contactLanguage,
                             String useCaseDescription,
                             List<String> additionalContactEmailAddresses,
                             boolean productionAccessEnabled,
                             String reviewStatus,
                             String caseId) {
}
