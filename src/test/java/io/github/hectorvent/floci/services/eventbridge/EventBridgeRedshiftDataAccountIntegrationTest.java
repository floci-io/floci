package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.eventbridge.model.RedshiftDataParameters;
import io.github.hectorvent.floci.services.eventbridge.model.Rule;
import io.github.hectorvent.floci.services.eventbridge.model.RuleState;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import io.github.hectorvent.floci.services.redshiftdata.RedshiftDataService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@QuarkusTest
class EventBridgeRedshiftDataAccountIntegrationTest {

    @Inject
    RuleScheduler scheduler;

    @Inject
    RegionResolver regionResolver;

    @InjectMock
    RedshiftDataService redshiftDataService;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void scheduledSqlUsesRuleAccountWithoutAnHttpRequest(boolean batch) {
        String previousAccount = regionResolver.getAccountId();
        AtomicReference<String> executionAccount = new AtomicReference<>();
        if (batch) {
            when(redshiftDataService.batchExecuteStatement(any(), any(), any())).thenAnswer(invocation -> {
                executionAccount.set(regionResolver.getAccountId());
                return JsonNodeFactory.instance.objectNode();
            });
        } else {
            when(redshiftDataService.executeStatement(any(), any(), any())).thenAnswer(invocation -> {
                executionAccount.set(regionResolver.getAccountId());
                return JsonNodeFactory.instance.objectNode();
            });
        }

        Rule rule = new Rule();
        rule.setName("scheduled-redshift-account");
        rule.setArn("arn:aws:events:us-east-1:111122223333:rule/scheduled-redshift-account");
        rule.setAccountId("111122223333");
        rule.setState(RuleState.ENABLED);
        Target target = new Target("sql", "arn:aws:redshift:us-east-1:999999999999:cluster:warehouse", null, null);
        target.setRoleArn("arn:aws:iam::111122223333:role/EventBridgeRedshiftRole");
        RedshiftDataParameters parameters = new RedshiftDataParameters();
        parameters.setDatabase("dev");
        if (batch) {
            parameters.setSqls(List.of("select 1"));
        } else {
            parameters.setSql("select 1");
        }
        target.setRedshiftDataParameters(parameters);

        try {
            scheduler.startScheduler(rule.getArn(), "rate(1 minute)",
                    () -> new RuleScheduler.ScheduleData(rule, List.of(target)));
            assertEquals("111122223333", executionAccount.get());
            assertEquals(previousAccount, regionResolver.getAccountId());
        } finally {
            scheduler.stopScheduler(rule.getArn());
        }
    }
}
