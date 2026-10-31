package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.services.ses.SesRecommendations.Finding;
import io.github.hectorvent.floci.services.ses.model.Identity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.hectorvent.floci.services.ses.SesRecommendations.BIMI_NOT_FOUND;
import static io.github.hectorvent.floci.services.ses.SesRecommendations.DKIM_NOT_ENABLED;
import static io.github.hectorvent.floci.services.ses.SesRecommendations.DKIM_SHORT_KEY;
import static io.github.hectorvent.floci.services.ses.SesRecommendations.DMARC_NOT_FOUND;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SesRecommendationsTest {

    private static Identity signingDomain(String keyLength) {
        Identity identity = new Identity("example.com", "Domain");
        identity.setDkimEnabled(true);
        identity.setDkimCurrentSigningKeyLength(keyLength);
        return identity;
    }

    @Test
    void domainWithoutDnsRecordsGetsDmarcAndBimi() {
        assertEquals(List.of(DMARC_NOT_FOUND, BIMI_NOT_FOUND),
                SesRecommendations.findings(signingDomain("RSA_2048_BIT"), false, false));
    }

    @Test
    void domainWithBothRecordsAndStrongKeyGetsNothing() {
        assertEquals(List.of(), SesRecommendations.findings(signingDomain("RSA_2048_BIT"), true, true));
    }

    @Test
    void disabledSigningIsDkim1() {
        Identity identity = new Identity("example.com", "Domain");
        identity.setDkimEnabled(false);
        assertEquals(List.of(DKIM_NOT_ENABLED), SesRecommendations.findings(identity, true, true));
    }

    @Test
    void disabledSigningWithShortKeyIsDkim1Only() {
        Identity identity = signingDomain("RSA_1024_BIT");
        identity.setDkimEnabled(false);
        assertEquals(List.of(DKIM_NOT_ENABLED), SesRecommendations.findings(identity, true, true));
    }

    @Test
    void easyDkimWithShortKeyIsDkim3() {
        assertEquals(List.of(DKIM_SHORT_KEY), SesRecommendations.findings(signingDomain("RSA_1024_BIT"), true, true));
    }

    @Test
    void byoDkimKeyLengthIsNotJudged() {
        Identity identity = signingDomain("RSA_1024_BIT");
        identity.setDkimSigningAttributesOrigin("EXTERNAL");
        assertEquals(List.of(), SesRecommendations.findings(identity, true, true));
    }

    @Test
    void unverifiedDomainGetsNothing() {
        Identity identity = signingDomain("RSA_1024_BIT");
        identity.setVerificationStatus("Pending");
        assertEquals(List.of(), SesRecommendations.findings(identity, false, false));
    }

    @Test
    void emailAddressGetsNothing() {
        Identity identity = new Identity("alice@example.com", "EmailAddress");
        assertEquals(List.of(), SesRecommendations.findings(identity, false, false));
    }

    @Test
    void codesAndImpactsFollowTheProbe() {
        assertEquals(new Finding("DKIM", "DKIM1", "HIGH"), DKIM_NOT_ENABLED);
        assertEquals(new Finding("DKIM", "DKIM3", "LOW"), DKIM_SHORT_KEY);
        assertEquals(new Finding("DMARC", "DMARC1", "HIGH"), DMARC_NOT_FOUND);
        assertEquals(new Finding("BIMI", "BIMI1", "LOW"), BIMI_NOT_FOUND);
    }

    @Test
    void recordNamesAreTheDmarcAndDefaultBimiSelectors() {
        assertEquals("_dmarc.example.com", SesRecommendations.dmarcRecordName("example.com"));
        assertEquals("default._bimi.example.com", SesRecommendations.bimiRecordName("example.com"));
    }
}
