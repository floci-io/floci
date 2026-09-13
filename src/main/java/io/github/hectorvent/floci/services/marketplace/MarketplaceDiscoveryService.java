package io.github.hectorvent.floci.services.marketplace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class MarketplaceDiscoveryService implements Resettable {
    private static final Set<String> REGIONS = Set.of("us-east-1", "us-west-2", "eu-west-1");
    private static final Set<String> FACET_TYPES = Set.of("AVERAGE_CUSTOMER_RATING", "CATEGORY", "PUBLISHER", "FULFILLMENT_OPTION_TYPE", "PRICING_MODEL", "PRICING_UNIT", "DEPLOYED_ON_AWS", "NUMBER_OF_PRODUCTS");
    private static final Set<String> SEARCH_FILTER_TYPES = Set.of("MIN_AVERAGE_CUSTOMER_RATING", "MAX_AVERAGE_CUSTOMER_RATING", "CATEGORY", "PUBLISHER", "FULFILLMENT_OPTION_TYPE", "PRICING_MODEL", "PRICING_UNIT", "DEPLOYED_ON_AWS", "NUMBER_OF_PRODUCTS");
    private final ObjectMapper mapper;
    private final AccountAwareStorageBackend<JsonNode> entities;

    @Inject
    public MarketplaceDiscoveryService(ObjectMapper mapper, StorageFactory factory) {
        this(mapper, factory.create("marketplace", "marketplace-entities.json",
                new TypeReference<Map<String, JsonNode>>() {}));
    }

    MarketplaceDiscoveryService(ObjectMapper mapper, AccountAwareStorageBackend<JsonNode> entities) {
        this.mapper = mapper;
        this.entities = entities;
    }

    public ObjectNode getProduct(JsonNode request, String region) {
        validateRegion(region); String id = id(request, "productId"); JsonNode entity = find(region, id, "Product");
        return product(entity);
    }

    public ObjectNode getListing(JsonNode request, String region) {
        validateRegion(region); String id = id(request, "listingId"); JsonNode entity = find(region, id, "Product");
        ObjectNode details = details(entity); ObjectNode out = mapper.createObjectNode();
        out.put("listingId", id); out.put("listingName", title(entity)); out.put("catalog", "AWSMarketplace");
        out.put("shortDescription", string(details, "ShortDescription", "Local AWS Marketplace listing"));
        out.put("longDescription", string(details, "LongDescription", out.path("shortDescription").asText()));
        out.put("logoThumbnailUrl", string(details, "LogoUrl", "https://example.invalid/marketplace/" + id + ".png"));
        out.set("publisher", seller(details)); out.set("associatedEntities", array(productAssociation(entity)));
        out.set("badges", mapper.createArrayNode()); out.set("categories", categories(details)); out.set("fulfillmentOptionSummaries", fulfillmentSummaries(details));
        out.set("highlights", stringArray(details.get("Highlights"))); out.set("pricingModels", pricingModels(details)); out.set("pricingUnits", pricingUnits(details));
        out.set("promotionalMedia", mapper.createArrayNode()); out.set("resources", mapper.createArrayNode()); out.set("sellerEngagements", mapper.createArrayNode()); out.set("useCases", mapper.createArrayNode());
        out.set("reviewSummary", mapper.createObjectNode().set("reviewSourceSummaries", mapper.createArrayNode())); return out;
    }

    public ObjectNode getOffer(JsonNode request, String region) {
        validateRegion(region); String id=id(request,"offerId"); JsonNode e=find(region,id,"Offer"); ObjectNode d=details(e); ObjectNode out=mapper.createObjectNode();
        out.put("offerId",id); out.put("catalog","AWSMarketplace"); out.put("offerName",title(e)); out.put("agreementProposalId",string(d,"AgreementProposalId",id));
        out.set("sellerOfRecord",seller(d)); out.set("associatedEntities", offerAssociations(d, region)); out.set("pricingModel", pricingModel(d)); out.set("badges",mapper.createArrayNode()); return out;
    }

    public ObjectNode getOfferSet(JsonNode request, String region) {
        validateRegion(region);
        String id = id(request, "offerSetId");
        JsonNode entity = find(region, id, "OfferSet");
        ObjectNode details = details(entity);
        ObjectNode out = mapper.createObjectNode();
        out.put("offerSetId", id);
        out.put("catalog", "AWSMarketplace");
        out.put("offerSetName", title(entity));
        out.set("sellerOfRecord", seller(details));
        out.set("badges", mapper.createArrayNode());
        out.set("associatedEntities", offerSetAssociations(details, region));
        return out;
    }

    public ObjectNode getOfferTerms(JsonNode request,String region){
        validateRegion(region);
        JsonNode offer = find(region, id(request,"offerId"), "Offer");
        ObjectNode details = details(offer);
        JsonNode terms = details.has("OfferTerms") ? details.get("OfferTerms") : details.get("Terms");
        return page("offerTerms", nodes(terms), request, 25, 25);
    }
    public ObjectNode listFulfillmentOptions(JsonNode request,String region){
        validateRegion(region); JsonNode e=find(region,id(request,"productId"),"Product"); ObjectNode d=details(e);
        List<JsonNode> options = new ArrayList<>();
        if ("SAAS".equals(d.path("FulfillmentType").asText("SAAS"))) {
            ObjectNode saas = mapper.createObjectNode();
            ObjectNode value = saas.putObject("saasFulfillmentOption");
            value.put("fulfillmentOptionId", "fo-" + e.path("EntityId").asText());
            value.put("fulfillmentOptionType", "SAAS");
            value.put("fulfillmentOptionDisplayName", "SaaS");
            if (d.hasNonNull("FulfillmentUrl")) {
                value.put("fulfillmentUrl", d.path("FulfillmentUrl").asText());
            }
            if (d.hasNonNull("UsageInstructions")) {
                value.put("usageInstructions", d.path("UsageInstructions").asText());
            }
            options.add(saas);
        }
        return page("fulfillmentOptions", options, request, 50, 50);
    }

    public ObjectNode listPurchaseOptions(JsonNode request,String region){
        validateRegion(region);
        JsonNode filters = request.get("filters");
        if (filters != null && (!filters.isArray() || filters.isEmpty() || filters.size() > 10)) {
            throw validation("filters must contain between 1 and 10 values.");
        }
        if (!hasFilter(filters, "PRODUCT_ID") && !hasFilter(filters, "VISIBILITY_SCOPE")) {
            throw validation("At least one PRODUCT_ID or VISIBILITY_SCOPE filter is required.");
        }
        List<JsonNode> out=new ArrayList<>(); for(JsonNode e:entities.scan(key -> true)){String type=e.path("EntityType").asText();if(type.contains("Offer")&&!type.contains("OfferSet")&&matchesPurchase(e,filters,region)){
            out.add(purchaseSummary(e,region));
        }else if(type.contains("OfferSet")&&matchesPurchase(e,filters,region)){
            out.add(purchaseSummary(e,region));
        }}
        return page("purchaseOptions",out,request,50,100);
    }

    public ObjectNode searchListings(JsonNode request,String region){
        validateRegion(region);
        validateSearchRequest(request);
        List<JsonNode> list = matchingListings(request, region);
        List<JsonNode> publicListings = new ArrayList<>();
        for (JsonNode listing : list) {
            ObjectNode publicListing = (ObjectNode) listing.deepCopy();
            publicListing.remove(List.of("_deployedOnAws", "_numberOfProducts"));
            publicListings.add(publicListing);
        }
        ObjectNode response = page("listingSummaries", publicListings, request, 20, 100);
        response.put("totalResults", list.size());
        return response;
    }

    public ObjectNode searchFacets(JsonNode request,String region){
        validateRegion(region); validateSearchRequest(request); List<JsonNode> found=matchingListings(request, region);
        Set<String> requested=new LinkedHashSet<>();JsonNode types=request.get("facetTypes");
        if(types!=null){if(!types.isArray()||types.size()>30){
            throw validation("facetTypes must contain at most 30 values.");
        }for(JsonNode n:types){String type=n.asText();if(!FACET_TYPES.contains(type)){
            throw validation("Unsupported facet type: "+type);
        }requested.add(type);}}
        if(requested.isEmpty()){
            requested.addAll(FACET_TYPES);
        }
        List<FacetValue> allFacets = new ArrayList<>();
        for (String type : requested) {
            for (JsonNode value : facets(type, found)) {
                allFacets.add(new FacetValue(type, value));
            }
        }
        int offset = token(request.path("nextToken").asText(null));
        if (offset > allFacets.size()) {
            throw validation("nextToken is invalid.");
        }
        int end = Math.min(allFacets.size(), offset + 100);
        ObjectNode map = mapper.createObjectNode();
        for (int i = offset; i < end; i++) {
            FacetValue facet = allFacets.get(i);
            ArrayNode values = map.withArray(facet.type());
            values.add(facet.value().deepCopy());
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("totalResults", found.size());
        out.set("listingFacets", map);
        if (end < allFacets.size()) {
            out.put("nextToken", Integer.toString(end));
        }
        return out;
    }

    private List<JsonNode> matchingListings(JsonNode request, String region) {
        String text=request.path("searchText").asText("").toLowerCase(Locale.ROOT);List<JsonNode> list=new ArrayList<>();
        for(JsonNode e:entities.scan(key -> true)){if(!isProduct(e)){
            continue;
        }ObjectNode summary=listingSummary(e);
        ObjectNode entityDetails = details(e);
        if(!text.isBlank()&&!summary.toString().toLowerCase(Locale.ROOT).contains(text)){
            continue;
        }if(!matchesSearch(summary, entityDetails, request.get("filters"))){
            continue;
        }
        summary.put("_deployedOnAws", entityDetails.path("DeployedOnAws").asText("DEPLOYED"));
        summary.put("_numberOfProducts", entityDetails.path("NumberOfProducts").asInt(1));
        list.add(summary);}
        String sortBy = request.path("sortBy").asText("RELEVANCE");
        Comparator<JsonNode> comparator = "AVERAGE_CUSTOMER_RATING".equals(sortBy)
                ? Comparator.comparingDouble(MarketplaceDiscoveryService::averageRating)
                : Comparator.comparing(n -> n.path("listingName").asText());
        list.sort(comparator);
        String sortOrder = request.path("sortOrder").asText(
                "AVERAGE_CUSTOMER_RATING".equals(sortBy) ? "DESCENDING" : "ASCENDING");
        if ("DESCENDING".equals(sortOrder)) {
            java.util.Collections.reverse(list);
        }
        return list;
    }

    private ObjectNode product(JsonNode entity) {
        ObjectNode details = details(entity);
        ObjectNode out = mapper.createObjectNode();
        out.put("productId", entity.path("EntityId").asText());
        out.put("catalog", "AWSMarketplace");
        out.put("productName", title(entity));
        out.set("manufacturer", seller(details));
        out.put("deployedOnAws", details.path("DeployedOnAws").asText("DEPLOYED"));
        out.put("shortDescription", string(details, "ShortDescription", "Local AWS Marketplace product"));
        out.put("longDescription", string(details, "LongDescription", out.path("shortDescription").asText()));
        out.put("logoThumbnailUrl", string(details, "LogoUrl",
                "https://example.invalid/marketplace/" + entity.path("EntityId").asText() + ".png"));
        out.set("fulfillmentOptionSummaries", fulfillmentSummaries(details));
        out.set("categories", categories(details));
        out.set("highlights", stringArray(details.get("Highlights")));
        out.set("promotionalMedia", mapper.createArrayNode());
        out.set("resources", mapper.createArrayNode());
        out.set("sellerEngagements", mapper.createArrayNode());
        return out;
    }
    private ObjectNode listingSummary(JsonNode e){ObjectNode p=product(e);ObjectNode d=details(e);ObjectNode out=mapper.createObjectNode();out.put("listingId",p.path("productId").asText());out.put("listingName",p.path("productName").asText());out.set("publisher",p.path("manufacturer").deepCopy());out.set("fulfillmentOptionSummaries",p.path("fulfillmentOptionSummaries").deepCopy());out.put("catalog","AWSMarketplace");out.put("shortDescription",p.path("shortDescription").asText());out.put("logoThumbnailUrl",p.path("logoThumbnailUrl").asText());out.set("categories",p.path("categories").deepCopy());out.set("badges",mapper.createArrayNode());ObjectNode review=mapper.createObjectNode();ArrayNode sources=review.putArray("reviewSourceSummaries");if(d.hasNonNull("AverageCustomerRating")){sources.addObject().put("sourceName","AWS Marketplace").put("sourceId","AWS_MARKETPLACE").put("averageRating",d.path("AverageCustomerRating").asText()).put("totalReviews",d.path("TotalReviews").asInt(0));}out.set("reviewSummary",review);out.set("pricingModels",pricingModels(d));out.set("pricingUnits",pricingUnits(d));out.set("associatedEntities",array(productAssociation(e)));return out;}
    private ObjectNode purchaseSummary(JsonNode e,String region){ObjectNode d=details(e);boolean set=e.path("EntityType").asText().contains("OfferSet");ObjectNode out=mapper.createObjectNode().put("purchaseOptionId",e.path("EntityId").asText()).put("catalog","AWSMarketplace").put("purchaseOptionType",set?"OFFERSET":"OFFER").put("purchaseOptionName",title(e));out.set("sellerOfRecord",seller(d));out.set("badges",mapper.createArrayNode());ArrayNode a=set?offerSetAssociations(d,region):mapper.createArrayNode();if(!set){String productId=d.path("ProductId").asText(null);if(productId!=null){ObjectNode rel=mapper.createObjectNode();rel.set("product",productInfo(find(region,productId,"Product")));rel.set("offer",offerInfo(e));a.add(rel);}}out.set("associatedEntities",a);return out;}
    private ArrayNode offerAssociations(JsonNode d,String region){ArrayNode a=mapper.createArrayNode();String pid=d.path("ProductId").asText(null);if(pid!=null){ObjectNode r=mapper.createObjectNode();r.set("product",productInfo(find(region,pid,"Product")));a.add(r);}return a;}
    private ArrayNode offerSetAssociations(JsonNode d,String region){ArrayNode out=mapper.createArrayNode();JsonNode pairs=d.get("Offers");if(pairs!=null&&pairs.isArray()){
        for(JsonNode pair:pairs){String pid=pair.path("ProductId").asText(null),oid=pair.path("OfferId").asText(null);if(pid!=null&&oid!=null){ObjectNode r=mapper.createObjectNode();r.set("product",productInfo(find(region,pid,"Product")));r.set("offer",offerInfo(find(region,oid,"Offer")));out.add(r);}}
    }return out;}
    private ObjectNode productAssociation(JsonNode entity) {
        ObjectNode association = mapper.createObjectNode();
        association.set("product", productInfo(entity));
        return association;
    }

    private ObjectNode productInfo(JsonNode e){ObjectNode d=details(e);ObjectNode o=mapper.createObjectNode().put("productId",e.path("EntityId").asText()).put("productName",title(e));o.set("manufacturer",seller(d));return o;}
    private ObjectNode offerInfo(JsonNode e){ObjectNode d=details(e);ObjectNode o=mapper.createObjectNode().put("offerId",e.path("EntityId").asText()).put("offerName",title(e));o.set("sellerOfRecord",seller(d));return o;}
    private ObjectNode seller(JsonNode d){String name=string(d,"SellerName",string(d,"Manufacturer","Local seller"));return mapper.createObjectNode().put("sellerProfileId",string(d,"SellerProfileId","seller-local")).put("displayName",name);}
    private ArrayNode fulfillmentSummaries(JsonNode d){ArrayNode a=mapper.createArrayNode();JsonNode existing=d.get("FulfillmentOptionSummaries");if(existing!=null&&existing.isArray()){
        return (ArrayNode)existing.deepCopy();
    }a.addObject().put("fulfillmentOptionType",d.path("FulfillmentType").asText("SAAS")).put("displayName",d.path("FulfillmentType").asText("SaaS"));return a;}
    private ArrayNode categories(JsonNode d){ArrayNode a=mapper.createArrayNode();JsonNode n=d.get("Categories");if(n!=null&&n.isArray()){
        for(JsonNode v:n){if(v.isObject()){
    a.add(v.deepCopy());
}else {
    a.addObject().put("categoryId",v.asText()).put("displayName",v.asText());
}}
    }return a;}
    private ArrayNode pricingModels(JsonNode d){JsonNode n=d.get("PricingModels");if(n!=null&&n.isArray()){
        return (ArrayNode)n.deepCopy();
    }ArrayNode a=mapper.createArrayNode();a.addObject().put("pricingModelType",d.path("PricingModel").asText("FREE")).put("displayName",d.path("PricingModel").asText("Free"));return a;}
    private ArrayNode pricingUnits(JsonNode d){JsonNode n=d.get("PricingUnits");return n!=null&&n.isArray()?(ArrayNode)n.deepCopy():mapper.createArrayNode();}
    private ObjectNode pricingModel(JsonNode d){JsonNode n=d.get("PricingModel");if(n!=null&&n.isObject()){
        return (ObjectNode)n.deepCopy();
    }String value=n!=null&&n.isTextual()?n.asText():"FREE";return mapper.createObjectNode().put("pricingModelType",value).put("displayName",value);}
    private ArrayNode facets(String type,List<JsonNode> listings){Map<String,Integer> counts=new LinkedHashMap<>();for(JsonNode l:listings){if("PUBLISHER".equals(type)){
        counts.merge(l.path("publisher").path("displayName").asText("Local seller"),1,Integer::sum);
    }else if("CATEGORY".equals(type)){
        l.path("categories").forEach(v->counts.merge(v.path("categoryId").asText(),1,Integer::sum));
    }else if("FULFILLMENT_OPTION_TYPE".equals(type)){
        l.path("fulfillmentOptionSummaries").forEach(v->counts.merge(v.path("fulfillmentOptionType").asText(),1,Integer::sum));
    }else if("PRICING_MODEL".equals(type)){
        l.path("pricingModels").forEach(v->counts.merge(v.path("pricingModelType").asText(),1,Integer::sum));
    }else if("PRICING_UNIT".equals(type)){
        l.path("pricingUnits").forEach(v->counts.merge(v.path("pricingUnitType").asText(),1,Integer::sum));
    }else if("DEPLOYED_ON_AWS".equals(type)){
        counts.merge(l.path("_deployedOnAws").asText("DEPLOYED"),1,Integer::sum);
    }else if("NUMBER_OF_PRODUCTS".equals(type)){
        counts.merge(l.path("_numberOfProducts").asText("1"),1,Integer::sum);
    }}ArrayNode a=mapper.createArrayNode();counts.forEach((v,c)->a.addObject().put("value",v).put("displayName",v).put("count",c));return a;}
    private void validateSearchRequest(JsonNode request) {
        String searchText = request.path("searchText").asText(null);
        if (searchText != null && (searchText.isBlank() || searchText.length() > 512)) {
            throw validation("searchText must contain between 1 and 512 characters.");
        }
        String sortBy = request.path("sortBy").asText(null); if (sortBy != null && !Set.of("RELEVANCE", "AVERAGE_CUSTOMER_RATING").contains(sortBy)) {
            throw validation("Unsupported sortBy value.");
        }
        String sortOrder = request.path("sortOrder").asText(null); if (sortOrder != null && !Set.of("ASCENDING", "DESCENDING").contains(sortOrder)) {
            throw validation("Unsupported sortOrder value.");
        }
        JsonNode filters=request.get("filters"); if(filters!=null){if(!filters.isArray()||filters.isEmpty()||filters.size()>30){
            throw validation("filters must contain between 1 and 30 values.");
        }for(JsonNode f:filters){String type=f.path("filterType").asText();if(!SEARCH_FILTER_TYPES.contains(type)){
            throw validation("Unsupported search filter: "+type);
        }
            JsonNode values = f.get("filterValues");
            if (values == null || !values.isArray() || values.isEmpty() || values.size() > 30) {
                throw validation("filterValues must contain between 1 and 30 values.");
            }
            for (JsonNode value : values) {
                if (!value.isTextual() || value.asText().length() < 1 || value.asText().length() > 128
                        || !value.asText().matches("[\\w\\-.]+")) {
                    throw validation("filterValues entries must match [\\w\\-.]+ and be 1 to 128 characters.");
                }
            }
        }}
    }

    private boolean matchesSearch(JsonNode s, JsonNode details, JsonNode filters){if(filters==null||!filters.isArray()){
        return true;
    }for(JsonNode f:filters){String t=f.path("filterType").asText();JsonNode vals=f.path("filterValues");boolean ok=false;if("MIN_AVERAGE_CUSTOMER_RATING".equals(t)||"MAX_AVERAGE_CUSTOMER_RATING".equals(t)){if(vals.size()!=1){
        throw validation("Rating filters accept exactly one value.");
    }double wanted=rating(vals.get(0));double actual=averageRating(s);ok="MIN_AVERAGE_CUSTOMER_RATING".equals(t)?actual>=wanted:actual<=wanted;}else{for(JsonNode v:vals){String w=v.asText();if("CATEGORY".equals(t)){
        for (JsonNode c : s.path("categories")) {
                ok |= w.equals(c.path("categoryId").asText());
            }
    }else if("PUBLISHER".equals(t)){
        ok|=w.equals(s.path("publisher").path("displayName").asText());
    }else if("PRICING_MODEL".equals(t)){
        for (JsonNode p : s.path("pricingModels")) {
                ok |= w.equals(p.path("pricingModelType").asText());
            }
    }else if("PRICING_UNIT".equals(t)){
        for (JsonNode p : s.path("pricingUnits")) {
                ok |= w.equals(p.path("pricingUnitType").asText());
            }
    }else if("FULFILLMENT_OPTION_TYPE".equals(t)){
        for (JsonNode p : s.path("fulfillmentOptionSummaries")) {
                ok |= w.equals(p.path("fulfillmentOptionType").asText());
            }
    }else if("DEPLOYED_ON_AWS".equals(t)){
        ok |= w.equals(details.path("DeployedOnAws").asText("DEPLOYED"));
    }else if("NUMBER_OF_PRODUCTS".equals(t)){
        ok |= w.equals(details.path("NumberOfProducts").asText("1"));
    }}}if(!ok){
        return false;
    }}return true;}
    private static double rating(JsonNode value){try{double rating=Double.parseDouble(value.asText());if(rating<0.0d||rating>5.0d){
        throw new NumberFormatException();
    }return rating;}catch(NumberFormatException e){throw validation("Customer rating filters must be between 0.0 and 5.0.");}}
    private static double averageRating(JsonNode listing){JsonNode sources=listing.path("reviewSummary").path("reviewSourceSummaries");if(!sources.isArray()||sources.isEmpty()){
        return 0.0d;
    }try{return Double.parseDouble(sources.get(0).path("averageRating").asText("0"));}catch(NumberFormatException e){return 0.0d;}}
    private boolean matchesPurchase(JsonNode entity, JsonNode filters, String region) {
        if (filters == null || !filters.isArray()) {
            return true;
        }
        ObjectNode details = details(entity);
        boolean offerSet = entity.path("EntityType").asText().contains("OfferSet");
        for (JsonNode filter : filters) {
            String type = filter.path("filterType").asText();
            JsonNode values = filter.path("filterValues");
            if (values == null || !values.isArray() || values.isEmpty() || values.size() > 10) {
                throw validation("Purchase option filterValues must contain between 1 and 10 values.");
            }
            for (JsonNode value : values) {
                if (!value.isTextual() || value.asText().length() < 1 || value.asText().length() > 255
                        || !value.asText().matches("[\\w\\-]+")) {
                    throw validation(
                            "Purchase option filterValues entries must match [\\w\\-]+ and be 1 to 255 characters.");
                }
            }
            String actual = switch (type) {
                case "PRODUCT_ID" -> details.path("ProductId").asText();
                case "SELLER_OF_RECORD_PROFILE_ID" -> seller(details).path("sellerProfileId").asText();
                case "PURCHASE_OPTION_TYPE" -> offerSet ? "OFFERSET" : "OFFER";
                case "VISIBILITY_SCOPE" -> details.path("VisibilityScope").asText("PUBLIC");
                case "AVAILABILITY_STATUS" -> details.path("AvailabilityStatus").asText("AVAILABLE");
                default -> throw validation("Unsupported purchase option filter: " + type);
            };
            boolean matches = false;
            for (JsonNode value : values) {
                matches |= value.asText().equals(actual);
            }
            if (!matches) {
                return false;
            }
        }
        return true;
    }
    private static boolean hasFilter(JsonNode filters,String name){if(filters!=null&&filters.isArray()){
        for (JsonNode f : filters) {
            if (name.equals(f.path("filterType").asText())) {
                return true;
            }
        }
    }return false;}
    private JsonNode find(String region,String id,String kind){for(JsonNode e:entities.scan(key -> true)){String t=e.path("EntityType").asText();boolean match=switch(kind){case "Product"->isProduct(e);case "OfferSet"->t.contains("OfferSet");case "Offer"->t.contains("Offer")&&!t.contains("OfferSet");default->false;};if(match&&id.equals(e.path("EntityId").asText())){
        return e;
    }}throw notFound(kind,id);}
    private static boolean isProduct(JsonNode e){String t=e.path("EntityType").asText();return t.contains("Product")&&!t.contains("Offer");}
    private ObjectNode details(JsonNode e){JsonNode d=e.get("DetailsDocument");return d!=null&&d.isObject()?(ObjectNode)d.deepCopy():mapper.createObjectNode();}
    private static String title(JsonNode e){JsonNode d=e.path("DetailsDocument");for (String k : List.of("ProductTitle", "Name", "Title", "OfferName", "OfferSetName")) {
        if (d.hasNonNull(k) && !d.path(k).asText().isBlank()) {
            return d.path(k).asText();
        }
    }return e.path("EntityIdentifier").asText(e.path("EntityId").asText());}
    private static String string(JsonNode n,String field,String fallback){return n.hasNonNull(field)&&!n.path(field).asText().isBlank()?n.path(field).asText():fallback;}
    private ArrayNode stringArray(JsonNode n){if(n!=null&&n.isArray()){
        return (ArrayNode)n.deepCopy();
    }return mapper.createArrayNode();}
    private ArrayNode array(JsonNode n){ArrayNode a=mapper.createArrayNode();a.add(n);return a;}
    private static List<JsonNode> nodes(JsonNode n){List<JsonNode> out=new ArrayList<>();if(n!=null&&n.isArray()){
        n.forEach(out::add);
    }return out;}
    private ObjectNode page(String field,List<JsonNode> values,JsonNode request,int def,int maxAllowed){int max=request.path("maxResults").isInt()?request.path("maxResults").asInt():def;if(max<1||max>maxAllowed){
        throw validation("maxResults must be between 1 and "+maxAllowed+".");
    }int off=token(request.path("nextToken").asText(null));if(off>values.size()){
        throw validation("nextToken is invalid.");
    }int end=Math.min(values.size(),off+max);ObjectNode out=mapper.createObjectNode();ArrayNode a=out.putArray(field);values.subList(off,end).forEach(v->a.add(v.deepCopy()));if(end<values.size()){
        out.put("nextToken",Integer.toString(end));
    }return out;}
    private static int token(String v){if(v==null||v.isBlank()){
        return 0;
    }try{int n=Integer.parseInt(v);if(n<0){
        throw new NumberFormatException();
    }return n;}catch(NumberFormatException e){throw validation("nextToken is invalid.");}}
    private static String id(JsonNode r,String f){String v=r.path(f).asText(null);if(v==null||v.isBlank()||v.length()>255||!v.matches("[\\w-]+")){
        throw validation(f+" is required and must match [\\w-]+.");
    }return v;}
    private static void validateRegion(String region){if(!REGIONS.contains(region)){
        throw validation("Marketplace Discovery is available only in us-east-1, us-west-2, and eu-west-1.");
    }}
    private static AwsException validation(String m){return new AwsException("ValidationException",m,400);}
    private static AwsException notFound(String k,String id){return new AwsException("ResourceNotFoundException",k+" "+id+" was not found.",404);}

    private record FacetValue(String type, JsonNode value) {}

    @Override
    public void clear() {
        entities.clear();
    }
}
