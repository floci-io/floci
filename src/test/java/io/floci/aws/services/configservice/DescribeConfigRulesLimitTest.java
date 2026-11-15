package io.floci.aws.services.configservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DescribeConfigRules' ConfigRuleNames member is modeled with {@code Maximum 25 items}
 * (confirmed against botocore's config service-2.json), which ConfigRequestShapes checks
 * before the handler reads the names.
 */
class DescribeConfigRulesLimitTest {

    private static final String REGION = "us-east-1";

    @Test
    void rejectsMoreThanTwentyFiveRuleNames() {
        ObjectMapper mapper = new ObjectMapper();
        ConfigServiceJsonHandler handler = new ConfigServiceJsonHandler(
                new AwsConfigService(new RegionResolver(REGION, "000000000000"), null), mapper);
        ObjectNode request = mapper.createObjectNode();
        ArrayNode tooMany = request.putArray("ConfigRuleNames");
        for (int i = 0; i < 26; i++) {
            tooMany.add("rule-" + i);
        }

        AwsException ex = assertThrows(AwsException.class,
                () -> handler.handle("DescribeConfigRules", request, REGION));
        assertEquals("ValidationException", ex.getErrorCode());
    }
}
