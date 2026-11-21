package io.floci.aws.services.bcmpricingcalculator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.bcmpricingcalculator.model.WorkloadEstimate;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** IAM's lookup of the tags Pricing Calculator holds on a workload estimate. */
class BcmPricingCalculatorStoredTagsTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String ESTIMATES = "arn:aws:bcm-pricing-calculator::000000000000:workload-estimate/";

    /**
     * The lookup reads the tags a workload estimate was created with, under the ARN its account and id give, which has
     * no Region, and nothing for another account's, a regional, another partition's, an unknown estimate's or another
     * service's ARN.
     */
    @Test
    void storedTagsAreTheTagsOfTheWorkloadEstimateTheArnNames() {
        BcmPricingCalculatorService service = service(new InMemoryStorageFactory(ACCOUNT));
        String id = service.create("stored-tags", null, null, Map.of("team", "data")).getId();

        assertEquals("bcm-pricing-calculator", service.namespace());
        assertEquals(Map.of("team", "data"), service.storedTags(ESTIMATES + id));
        assertTrue(service.storedTags((ESTIMATES + id).replace(ACCOUNT, "111122223333")).isEmpty());
        assertTrue(service.storedTags((ESTIMATES + id).replace("calculator::", "calculator:" + REGION + ":"))
                .isEmpty());
        assertTrue(service.storedTags((ESTIMATES + id).replace("arn:aws:", "arn:aws-cn:")).isEmpty());
        assertTrue(service.storedTags(ESTIMATES + "00000000-0000-0000-0000-000000000000").isEmpty());
        assertTrue(service.storedTags("arn:aws:ec2:us-east-1:000000000000:instance/i-0123456789abcdef0").isEmpty());
    }

    /** A workload estimate stored before storage was account-aware is read in place: the lookup migrates nothing. */
    @Test
    void storedTagsReadAPreAccountAwareEstimateInPlace() {
        InMemoryStorage<String, WorkloadEstimate> raw = new InMemoryStorage<>();
        BcmPricingCalculatorService service =
                service(new InMemoryStorageFactory(ACCOUNT).withStore("workload-estimates.json", raw));
        WorkloadEstimate legacy = new WorkloadEstimate();
        legacy.setId("legacy-estimate");
        legacy.setTags(Map.of("team", "data"));
        raw.put("legacy-estimate", legacy);

        assertEquals(Map.of("team", "data"), service.storedTags(ESTIMATES + "legacy-estimate"));
        assertEquals(Set.of("legacy-estimate"), raw.keys());
    }

    private BcmPricingCalculatorService service(InMemoryStorageFactory storage) {
        return new BcmPricingCalculatorService(storage, new RegionResolver(REGION, ACCOUNT), null, new ObjectMapper());
    }
}
