package io.github.hectorvent.floci.services.redshiftserverless.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A Redshift Serverless usage limit. Floci stores and echoes it but does not meter usage, so
 * {@code breachAction} never fires.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class UsageLimit {

    private String usageLimitId;
    private String usageLimitArn;
    private String resourceArn;
    private String usageType;
    private long amount;
    private String period;
    private String breachAction;

    public UsageLimit() {
    }

    public UsageLimit(UsageLimit other) {
        this.usageLimitId = other.usageLimitId;
        this.usageLimitArn = other.usageLimitArn;
        this.resourceArn = other.resourceArn;
        this.usageType = other.usageType;
        this.amount = other.amount;
        this.period = other.period;
        this.breachAction = other.breachAction;
    }

    public String getUsageLimitId() {
        return usageLimitId;
    }

    public void setUsageLimitId(String usageLimitId) {
        this.usageLimitId = usageLimitId;
    }

    public String getUsageLimitArn() {
        return usageLimitArn;
    }

    public void setUsageLimitArn(String usageLimitArn) {
        this.usageLimitArn = usageLimitArn;
    }

    public String getResourceArn() {
        return resourceArn;
    }

    public void setResourceArn(String resourceArn) {
        this.resourceArn = resourceArn;
    }

    public String getUsageType() {
        return usageType;
    }

    public void setUsageType(String usageType) {
        this.usageType = usageType;
    }

    public long getAmount() {
        return amount;
    }

    public void setAmount(long amount) {
        this.amount = amount;
    }

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

    public String getBreachAction() {
        return breachAction;
    }

    public void setBreachAction(String breachAction) {
        this.breachAction = breachAction;
    }
}
