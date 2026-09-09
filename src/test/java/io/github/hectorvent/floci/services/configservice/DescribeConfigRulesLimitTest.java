package io.github.hectorvent.floci.services.configservice;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DescribeConfigRules' ConfigRuleNames member is modeled with {@code Maximum 25 items}
 * (confirmed against botocore's config service-2.json). Every other array-size limit this
 * PR touches (Scope.ComplianceResourceTypes, PutEvaluations, PutRetentionConfiguration's
 * day range) is enforced; this one was not.
 */
class DescribeConfigRulesLimitTest {

    private static final String REGION = "us-east-1";

    @Test
    void rejectsMoreThanTwentyFiveRuleNames() {
        AwsConfigService service = new AwsConfigService(new RegionResolver(REGION, "000000000000"), null);
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 26; i++) {
            tooMany.add("rule-" + i);
        }

        AwsException ex = assertThrows(AwsException.class,
                () -> service.describeConfigRules(REGION, tooMany));
        assertEquals("InvalidParameterValueException", ex.getErrorCode());
    }
}
