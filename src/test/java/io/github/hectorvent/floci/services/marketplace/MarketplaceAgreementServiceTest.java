package io.github.hectorvent.floci.services.marketplace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MarketplaceAgreementServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private AccountAwareStorageBackend<JsonNode> agreements;
    private AccountAwareStorageBackend<JsonNode> cancellations;
    private AccountAwareStorageBackend<JsonNode> payments;
    private AccountAwareStorageBackend<JsonNode> billing;
    private AccountAwareStorageBackend<JsonNode> catalog;
    private MarketplaceAgreementService service;

    @BeforeEach
    void setUp() {
        agreements = AccountAwareStorageBackend.inMemory("000000000000");
        cancellations = AccountAwareStorageBackend.inMemory("000000000000");
        payments = AccountAwareStorageBackend.inMemory("000000000000");
        billing = AccountAwareStorageBackend.inMemory("000000000000");
        catalog = AccountAwareStorageBackend.inMemory("000000000000");
        service = new MarketplaceAgreementService(
                mapper,
                agreements,
                AccountAwareStorageBackend.inMemory("000000000000"),
                cancellations,
                payments,
                billing,
                catalog,
                null);
    }

    @Test
    void resourceIdentifierSearchUsesCatalogProposalMetadata() throws Exception {
        catalog.put("offer-1", mapper.readTree("""
                {"EntityType":"Offer@1.0","EntityId":"offer-1","DetailsDocument":{
                "AgreementProposalId":"ap-Example123","ProductId":"prod-1","ProductType":"SaaSProduct"}}
                """));
        String requestId = service.handle("CreateAgreementRequest", mapper.readTree("""
                {"intent":"NEW","agreementProposalIdentifier":"ap-Example123","requestedTerms":[{"id":"term-1"}]}
                """), "us-east-1").path("agreementRequestId").asText();
        String agreementId = service.handle("AcceptAgreementRequest",
                mapper.readTree("{\"agreementRequestId\":\"" + requestId + "\"}"), "us-east-1")
                .path("agreementId").asText();

        JsonNode response = service.handle("SearchAgreements", mapper.readTree("""
                {"filters":[{"name":"AgreementType","values":["PurchaseAgreement"]},{"name":"ResourceIdentifier","values":["prod-1"]}]}
                """), "us-east-1");
        assertEquals(1, response.path("agreementViewSummaries").size());
        assertEquals(agreementId, response.path("agreementViewSummaries").get(0).path("agreementId").asText());
        assertEquals("offer-1", response.path("agreementViewSummaries").get(0)
                .path("proposalSummary").path("offerId").asText());
    }

    @Test
    void listPaymentRequestsAppliesAgreementAndStatusFilters() throws Exception {
        payments.put("pr-1", mapper.readTree("""
                {"paymentRequestId":"pr-1","agreementId":"agr-1","agreementType":"PurchaseAgreement",
                "catalog":"AWSMarketplace","status":"APPROVED","createdAt":2}
                """));
        payments.put("pr-2", mapper.readTree("""
                {"paymentRequestId":"pr-2","agreementId":"agr-2","agreementType":"PurchaseAgreement",
                "catalog":"AWSMarketplace","status":"REJECTED","createdAt":1}
                """));
        JsonNode response = service.handle("ListAgreementPaymentRequests", mapper.readTree("""
                {"partyType":"Acceptor","agreementId":"agr-1","status":"APPROVED"}
                """), "us-east-1");
        assertEquals(1, response.path("items").size());
        assertEquals("pr-1", response.path("items").get(0).path("paymentRequestId").asText());
    }

    @Test
    void listAgreementChargesUsesAwsFieldNames() throws Exception {
        payments.put("pr-1", mapper.readTree("""
                {"paymentRequestId":"pr-1","chargeId":"ch-1","agreementId":"agr-1",
                "agreementType":"PurchaseAgreement","catalog":"AWSMarketplace","status":"APPROVED",
                "chargeAmount":"12.50","currencyCode":"USD","createdAt":1,"updatedAt":2}
                """));
        JsonNode response = service.handle("ListAgreementCharges",
                mapper.readTree("{\"agreementId\":\"agr-1\"}"), "us-east-1");
        JsonNode charge = response.path("items").get(0);
        assertEquals("ch-1", charge.path("id").asText());
        assertEquals("12.50", charge.path("amount").asText());
        assertEquals(true, charge.path("chargeId").isMissingNode());
        assertEquals(true, charge.path("chargeAmount").isMissingNode());
    }

    @Test
    void searchAgreementsDefaultsToEndTimeDescending() throws Exception {
        agreements.put("agr-early", mapper.readTree("""
                {"agreementId":"agr-early","agreementType":"PurchaseAgreement","status":"ACTIVE",
                "startTime":1,"endTime":10,"lastUpdateTime":2}
                """));
        agreements.put("agr-late", mapper.readTree("""
                {"agreementId":"agr-late","agreementType":"PurchaseAgreement","status":"ACTIVE",
                "startTime":2,"endTime":20,"lastUpdateTime":3}
                """));
        JsonNode response = service.handle("SearchAgreements", mapper.readTree("{\"filters\":[{\"name\":\"AgreementType\",\"values\":[\"PurchaseAgreement\"]}]}"), "us-east-1");
        assertEquals("agr-late", response.path("agreementViewSummaries").get(0).path("agreementId").asText());
    }

    @Test
    void searchAgreementsRequiresAgreementTypeAndEnforcesProposerOnlySorts() throws Exception {
        AwsException missing = assertThrows(AwsException.class,
                () -> service.handle("SearchAgreements", mapper.createObjectNode(), "us-east-1"));
        assertEquals("ValidationException", missing.getErrorCode());

        AwsException invalidSort = assertThrows(AwsException.class, () -> service.handle("SearchAgreements",
                mapper.readTree("""
                {"filters":[{"name":"AgreementType","values":["PurchaseAgreement"]},
                {"name":"PartyType","values":["Acceptor"]}],
                "sort":{"sortBy":"StartTime","sortOrder":"ASCENDING"}}
                """), "us-east-1"));
        assertEquals("ValidationException", invalidSort.getErrorCode());
    }

    @Test
    void searchAgreementsSupportsDocumentedTimeChainBehaviorAndLicenseFilters() throws Exception {
        agreements.put("agr-1", mapper.readTree("""
                {"agreementId":"agr-1","agreementType":"PurchaseAgreement","status":"ACTIVE",
                "startTime":100,"endTime":300,"lastUpdateTime":200,"initialAgreementId":"agr-root",
                "endTimeBehaviorType":"EXPIRE","endTimeBehaviorReasonCode":"NO_RENEWAL_TERM",
                "entitlements":[{"licenseArn":"arn:aws:license-manager::000000000000:license/lic-1"}]}
                """));
        JsonNode response = service.handle("SearchAgreements", mapper.readTree("""
                {"filters":[
                {"name":"AgreementType","values":["PurchaseAgreement"]},
                {"name":"InitialAgreementId","values":["agr-root"]},
                {"name":"EndTimeBehaviorType","values":["EXPIRE"]},
                {"name":"EndTimeBehaviorReasonCode","values":["NO_RENEWAL_TERM"]},
                {"name":"LicenseArn","values":["arn:aws:license-manager::000000000000:license/lic-1"]}]}
                """), "us-east-1");
        assertEquals(1, response.path("agreementViewSummaries").size());
    }

    @Test
    void regionOutsideUsEastOneIsRejected() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.handle("SearchAgreements", mapper.readTree("{\"filters\":[{\"name\":\"AgreementType\",\"values\":[\"PurchaseAgreement\"]}]}"), "us-west-2"));
        assertEquals("ValidationException", error.getErrorCode());
    }
}
