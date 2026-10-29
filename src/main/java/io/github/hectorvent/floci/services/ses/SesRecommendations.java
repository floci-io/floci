package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.services.ses.model.Identity;

import java.util.ArrayList;
import java.util.List;

/**
 * The Virtual Deliverability Manager advisor findings SES raises for a sending domain, derived from
 * the identity and from whether its DMARC and BIMI TXT records exist. SES describes each finding by a
 * code rather than a sentence. The codes, their impact and the condition behind each were
 * probe-confirmed against real SES (2026-10-27, us-east-1) by comparing the recommendations of
 * verified domains with their identity attributes and public DNS:
 *
 * <ul>
 *   <li>{@code DKIM1}, HIGH: DKIM signing is disabled.</li>
 *   <li>{@code DKIM3}, LOW: Easy DKIM signs with a 1024-bit key; a domain that does not sign gets
 *       none, whatever its next key length.</li>
 *   <li>{@code DMARC1}, HIGH: no {@code _dmarc} TXT record.</li>
 *   <li>{@code BIMI1}, LOW: no {@code default._bimi} TXT record.</li>
 * </ul>
 *
 * <p>{@code DKIM2} (DKIM verification failed) needs a FAILED DKIM status, which Floci never reaches,
 * and {@code DMARC5} appeared on a domain whose DNS matched one without it, so neither is raised.
 */
final class SesRecommendations {

    record Finding(String type, String description, String impact) {}

    static final Finding DKIM_NOT_ENABLED = new Finding("DKIM", "DKIM1", "HIGH");
    static final Finding DKIM_SHORT_KEY = new Finding("DKIM", "DKIM3", "LOW");
    static final Finding DMARC_NOT_FOUND = new Finding("DMARC", "DMARC1", "HIGH");
    static final Finding BIMI_NOT_FOUND = new Finding("BIMI", "BIMI1", "LOW");

    private SesRecommendations() {
    }

    /** Every finding the probe observed was on a verified domain. */
    static boolean isAdvised(Identity identity) {
        return "Domain".equals(identity.getIdentityType()) && "Success".equals(identity.getVerificationStatus());
    }

    static List<Finding> findings(Identity identity, boolean hasDmarcRecord, boolean hasBimiRecord) {
        if (!isAdvised(identity)) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        if (!identity.isDkimEnabled()) {
            findings.add(DKIM_NOT_ENABLED);
        } else if ("AWS_SES".equals(identity.getDkimSigningAttributesOrigin())
                && "RSA_1024_BIT".equals(identity.getDkimCurrentSigningKeyLength())) {
            findings.add(DKIM_SHORT_KEY);
        }
        if (!hasDmarcRecord) {
            findings.add(DMARC_NOT_FOUND);
        }
        if (!hasBimiRecord) {
            findings.add(BIMI_NOT_FOUND);
        }
        return findings;
    }

    static String dmarcRecordName(String domain) {
        return "_dmarc." + domain;
    }

    static String bimiRecordName(String domain) {
        return "default._bimi." + domain;
    }
}
