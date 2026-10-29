package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.BadRequestException;
import software.amazon.awssdk.services.sesv2.model.ListRecommendationsFilterKey;
import software.amazon.awssdk.services.sesv2.model.ListRecommendationsResponse;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;
import software.amazon.awssdk.services.sesv2.model.PutAccountVdmAttributesRequest;
import software.amazon.awssdk.services.sesv2.model.Recommendation;
import software.amazon.awssdk.services.sesv2.model.RecommendationImpact;
import software.amazon.awssdk.services.sesv2.model.RecommendationStatus;
import software.amazon.awssdk.services.sesv2.model.RecommendationType;
import software.amazon.awssdk.services.sesv2.model.VdmAttributes;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SDK compatibility test for SES v2 ListRecommendations. With Virtual Deliverability Manager enabled, a
 * verified domain without DMARC or BIMI records gets a recommendation for each, described by SES's code
 * rather than a sentence.
 */
@DisplayName("SES v2 Recommendations")
class SesRecommendationTest {

    private static final String DOMAIN = TestFixtures.uniqueName("compat-advisor") + ".example.com";

    private static SesV2Client sesV2;
    private static VdmAttributes originalVdm;

    @BeforeAll
    static void setup() {
        sesV2 = TestFixtures.sesV2Client();
        originalVdm = sesV2.getAccount(b -> { }).vdmAttributes();
        setVdmEnabled(true);
        TestFixtures.verifySesDomainIdentityViaRoute53(sesV2, DOMAIN);
    }

    @AfterAll
    static void cleanup() {
        if (sesV2 == null) {
            return;
        }
        try {
            sesV2.deleteEmailIdentity(b -> b.emailIdentity(DOMAIN));
        } catch (NotFoundException expected) {
            // Setup failed before the identity existed; anything else (auth, connectivity) must surface.
        } finally {
            restoreVdm();
        }
    }

    private static void setVdmEnabled(boolean enabled) {
        sesV2.putAccountVdmAttributes(PutAccountVdmAttributesRequest.builder()
                .vdmAttributes(VdmAttributes.builder()
                        .vdmEnabled(enabled ? "ENABLED" : "DISABLED")
                        .build())
                .build());
    }

    private static void restoreVdm() {
        if (originalVdm == null || originalVdm.vdmEnabled() == null) {
            setVdmEnabled(false);
            return;
        }
        sesV2.putAccountVdmAttributes(PutAccountVdmAttributesRequest.builder()
                .vdmAttributes(originalVdm)
                .build());
    }

    private static List<Recommendation> forDomain(ListRecommendationsResponse response) {
        return response.recommendations().stream()
                .filter(r -> r.resourceArn().endsWith(":identity/" + DOMAIN))
                .toList();
    }

    @Test
    @DisplayName("A domain without DMARC and BIMI records gets both recommendations")
    void listsDmarcAndBimi() {
        List<Recommendation> recommendations = forDomain(sesV2.listRecommendations(r -> r.pageSize(1000)));

        assertThat(recommendations).extracting(Recommendation::type)
                .containsExactly(RecommendationType.BIMI, RecommendationType.DMARC);
        assertThat(recommendations).extracting(Recommendation::description).containsExactly("BIMI1", "DMARC1");
        assertThat(recommendations).extracting(Recommendation::impact)
                .containsExactly(RecommendationImpact.LOW, RecommendationImpact.HIGH);
        assertThat(recommendations).extracting(Recommendation::status).containsOnly(RecommendationStatus.OPEN);
        assertThat(recommendations).allSatisfy(r -> {
            assertThat(r.createdTimestamp()).isNotNull();
            assertThat(r.lastUpdatedTimestamp()).isNotNull();
        });
    }

    @Test
    @DisplayName("A TYPE filter narrows the list")
    void filtersByType() {
        List<Recommendation> recommendations = forDomain(sesV2.listRecommendations(r -> r
                .filter(Map.of(ListRecommendationsFilterKey.TYPE, "DMARC"))
                .pageSize(1000)));

        assertThat(recommendations).extracting(Recommendation::description).containsExactly("DMARC1");
    }

    @Test
    @DisplayName("An unknown TYPE value is refused")
    void refusesUnknownType() {
        assertThatThrownBy(() -> sesV2.listRecommendations(r -> r
                .filter(Map.of(ListRecommendationsFilterKey.TYPE, "NOPE"))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid recommendation TYPE specified");
    }
}
