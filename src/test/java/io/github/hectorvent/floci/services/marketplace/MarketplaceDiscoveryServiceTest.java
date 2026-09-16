package io.github.hectorvent.floci.services.marketplace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MarketplaceDiscoveryServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private AccountAwareStorageBackend<JsonNode> entities;
    private MarketplaceDiscoveryService service;

    @BeforeEach
    void setUp() {
        entities = AccountAwareStorageBackend.inMemory("000000000000");
        service = new MarketplaceDiscoveryService(mapper, entities);
    }

    @Test
    void getOfferTermsReturnsStoredCatalogTerms() throws Exception {
        entities.put("offer-1", mapper.readTree("""
                {"EntityType":"Offer@1.0","EntityId":"offer-1","DetailsDocument":{
                "OfferTerms":[{"type":"LegalTerm","id":"term-1"}]}}
                """));
        JsonNode response = service.getOfferTerms(
                mapper.readTree("{\"offerId\":\"offer-1\"}"), "us-east-1");
        assertEquals("term-1", response.path("offerTerms").get(0).path("id").asText());
    }

    @Test
    void ratingSortAndFacetsUseListingValues() throws Exception {
        entities.put("prod-low", product("prod-low", "Low", "1.0", "NOT_DEPLOYED", 2));
        entities.put("prod-high", product("prod-high", "High", "4.8", "DEPLOYED", 5));
        JsonNode sorted = service.searchListings(mapper.readTree(
                "{\"sortBy\":\"AVERAGE_CUSTOMER_RATING\",\"sortOrder\":\"DESCENDING\"}"), "us-east-1");
        assertEquals("prod-high", sorted.path("listingSummaries").get(0).path("listingId").asText());

        JsonNode facets = service.searchFacets(mapper.readTree(
                "{\"facetTypes\":[\"DEPLOYED_ON_AWS\",\"NUMBER_OF_PRODUCTS\"]}"), "us-east-1");
        assertEquals(2, facets.path("listingFacets").path("DEPLOYED_ON_AWS").size());
        assertEquals(2, facets.path("listingFacets").path("NUMBER_OF_PRODUCTS").size());
    }



    @Test
    void rejectsSearchFilterValueCountAboveAwsLimit() throws Exception {
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < 31; i++) {
            if (i > 0) {
                values.append(',');
            }
            values.append('\"').append("value").append(i).append('\"');
        }
        JsonNode request = mapper.readTree("{\"filters\":[{\"filterType\":\"CATEGORY\",\"filterValues\":["
                + values + "]}]}");

        assertThrows(RuntimeException.class, () -> service.searchListings(request, "us-east-1"));
    }

    @Test
    void rejectsPurchaseOptionFilterCountAboveAwsLimit() throws Exception {
        StringBuilder filters = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            if (i > 0) {
                filters.append(',');
            }
            filters.append("{\"filterType\":\"PRODUCT_ID\",\"filterValues\":[\"prod-")
                    .append(i).append("\"]}");
        }
        JsonNode request = mapper.readTree("{\"filters\":[" + filters + "]}");

        assertThrows(RuntimeException.class, () -> service.listPurchaseOptions(request, "us-east-1"));
    }

    @Test
    void searchTextIgnoresFacetOnlyMetadata() throws Exception {
        entities.put("prod-1", product("prod-1", "Visible Product", "4.0", "DEPLOYED", 3));

        JsonNode deployed = service.searchListings(
                mapper.readTree("{\"searchText\":\"DEPLOYED\"}"), "us-east-1");
        JsonNode count = service.searchListings(
                mapper.readTree("{\"searchText\":\"3\"}"), "us-east-1");
        JsonNode visible = service.searchListings(
                mapper.readTree("{\"searchText\":\"Visible Product\"}"), "us-east-1");

        assertEquals(0, deployed.path("listingSummaries").size());
        assertEquals(0, count.path("listingSummaries").size());
        assertEquals(1, visible.path("listingSummaries").size());
    }

    @Test
    void searchListingsDoesNotLeakFacetOnlyFields() throws Exception {
        entities.put("prod-1", product("prod-1", "Product", "4.0", "DEPLOYED", 3));

        JsonNode listing = service.searchListings(mapper.createObjectNode(), "us-east-1")
                .path("listingSummaries").get(0);

        assertFalse(listing.has("deployedOnAws"));
        assertFalse(listing.has("numberOfProducts"));
        assertFalse(listing.has("_deployedOnAws"));
        assertFalse(listing.has("_numberOfProducts"));
    }


    @Test
    void listingAssociationsWrapProductsInAwsShape() throws Exception {
        entities.put("prod-1", product("prod-1", "Product", "4.0", "DEPLOYED", 1));

        JsonNode listing = service.getListing(
                mapper.readTree("{\"listingId\":\"prod-1\"}"), "us-east-1");
        JsonNode summary = service.searchListings(mapper.createObjectNode(), "us-east-1")
                .path("listingSummaries").get(0);

        assertEquals("prod-1", listing.path("associatedEntities").get(0)
                .path("product").path("productId").asText());
        assertEquals("prod-1", summary.path("associatedEntities").get(0)
                .path("product").path("productId").asText());
    }

    @Test
    void offerSetPurchaseOptionsIncludeAssociatedProductsAndOffers() throws Exception {
        entities.put("prod-1", product("prod-1", "Product", "4.0", "DEPLOYED", 1));
        entities.put("offer-1", mapper.readTree("""
                {"EntityType":"Offer@1.0","EntityId":"offer-1","DetailsDocument":{
                "OfferName":"Offer","ProductId":"prod-1"}}
                """));
        entities.put("set-1", mapper.readTree("""
                {"EntityType":"OfferSet@1.0","EntityId":"set-1","DetailsDocument":{
                "OfferSetName":"Set","VisibilityScope":"PUBLIC",
                "Offers":[{"ProductId":"prod-1","OfferId":"offer-1"}]}}
                """));

        JsonNode response = service.listPurchaseOptions(mapper.readTree("""
                {"filters":[
                  {"filterType":"VISIBILITY_SCOPE","filterValues":["PUBLIC"]},
                  {"filterType":"PURCHASE_OPTION_TYPE","filterValues":["OFFERSET"]}
                ]}
                """), "us-east-1");
        JsonNode purchaseOption = response.path("purchaseOptions").get(0);

        assertEquals(1, response.path("purchaseOptions").size());
        assertEquals("set-1", purchaseOption.path("purchaseOptionId").asText());
        assertEquals("prod-1", purchaseOption.path("associatedEntities").get(0)
                .path("product").path("productId").asText());
        assertEquals("offer-1", purchaseOption.path("associatedEntities").get(0)
                .path("offer").path("offerId").asText());
    }

    @Test
    void clearResetsSharedMarketplaceEntityState() throws Exception {
        entities.put("prod-1", product("prod-1", "Product", "4.0", "DEPLOYED", 1));

        service.clear();

        assertEquals(0, entities.scan(key -> true).size());
    }

    private JsonNode product(String id, String title, String rating, String deployed, int count) throws Exception {
        return mapper.readTree("{\"EntityType\":\"SaaSProduct@1.0\",\"EntityId\":\"" + id
                + "\",\"DetailsDocument\":{\"ProductTitle\":\"" + title
                + "\",\"AverageCustomerRating\":\"" + rating + "\",\"DeployedOnAws\":\""
                + deployed + "\",\"NumberOfProducts\":" + count + "}}");
    }
}
