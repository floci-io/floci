package io.github.hectorvent.floci.services.lambda.durable;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Set;

/** Swaps the Lambda container stack for {@link ScriptedDurableFunctionInvoker}. */
public class LambdaDurableScriptedProfile implements QuarkusTestProfile {

    @Override
    public Set<Class<?>> getEnabledAlternatives() {
        return Set.of(ScriptedDurableFunctionInvoker.class);
    }
}
