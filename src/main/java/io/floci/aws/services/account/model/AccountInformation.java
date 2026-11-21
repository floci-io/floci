package io.floci.aws.services.account.model;

import java.time.Instant;

/** What {@code GetAccountInformation} answers for an account; a member Floci does not know is null. */
public record AccountInformation(String accountId, String accountName, Instant accountCreatedDate,
                                 String accountState) {
}
