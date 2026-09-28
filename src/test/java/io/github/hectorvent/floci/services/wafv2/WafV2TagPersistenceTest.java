package io.github.hectorvent.floci.services.wafv2;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.wafv2.model.RegexPatternSet;
import io.github.hectorvent.floci.services.wafv2.model.RuleGroup;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WafV2TagPersistenceTest {

    @Test
    void regexPatternSetAndRuleGroupTagChangesWriteBackToTheirStores() {
        AccountAwareStorageBackend<RegexPatternSet> regexStore = spy(AccountAwareStorageBackend.inMemory("000000000000"));
        AccountAwareStorageBackend<RuleGroup> ruleGroupStore = spy(AccountAwareStorageBackend.inMemory("000000000000"));
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(invocation ->
                switch (invocation.getArgument(1, String.class)) {
                    case "wafv2-regex-sets.json" -> regexStore;
                    case "wafv2-rule-groups.json" -> ruleGroupStore;
                    default -> AccountAwareStorageBackend.inMemory("000000000000");
                });
        WafV2Service service = new WafV2Service(storageFactory, mock(RegionResolver.class), new ObjectMapper());

        RegexPatternSet regex = new RegexPatternSet();
        regex.setId("regex-id");
        regex.setScope("REGIONAL");
        regex.setArn("arn:aws:wafv2:us-east-1:000000000000:regional/regexpatternset/regex/regex-id");
        regexStore.put("REGIONAL:regex-id", regex);
        assertTagWrites(service, regexStore, "REGIONAL:regex-id", regex, regex.getArn());

        RuleGroup ruleGroup = new RuleGroup();
        ruleGroup.setId("rule-group-id");
        ruleGroup.setScope("REGIONAL");
        ruleGroup.setArn("arn:aws:wafv2:us-east-1:000000000000:regional/rulegroup/rules/rule-group-id");
        ruleGroupStore.put("REGIONAL:rule-group-id", ruleGroup);
        assertTagWrites(service, ruleGroupStore, "REGIONAL:rule-group-id", ruleGroup, ruleGroup.getArn());
    }

    private static <T> void assertTagWrites(WafV2Service service, StorageBackend<String, T> store,
                                            String key, T resource, String arn) {
        clearInvocations(store);
        service.tagResource(arn, Map.of("team", "waf"));
        assertEquals(Map.of("team", "waf"), service.listTagsForResource(arn));
        verify(store).put(key, resource);

        clearInvocations(store);
        service.untagResource(arn, List.of("team"));
        assertTrue(service.listTagsForResource(arn).isEmpty());
        verify(store).put(key, resource);
    }
}
