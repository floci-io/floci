package io.github.hectorvent.floci.services.apigateway.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Per-method settings on an API Gateway v1 Stage, addressed by the
 * <code>{resourcePath}/{httpMethod}</code> key in
 * {@link Stage#getMethodSettings()}.
 *
 * <p>Mirrors the fields documented at
 * <a href="https://docs.aws.amazon.com/apigateway/latest/api/API_MethodSetting.html">AWS
 * MethodSetting</a>; the defaults match the values real API Gateway returns
 * when the method has never been configured (throttling limits {@code -1}
 * mean "no override").
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@RegisterForReflection
public class MethodSetting {

    private static final int UNSET_THROTTLING_BURST_LIMIT = -1;
    private static final double UNSET_THROTTLING_RATE_LIMIT = -1.0d;
    private static final int UNSET_CACHE_TTL_SECONDS = 0;

    private boolean metricsEnabled;
    private String loggingLevel = "OFF";
    private boolean dataTraceEnabled;
    private int throttlingBurstLimit = UNSET_THROTTLING_BURST_LIMIT;
    private double throttlingRateLimit = UNSET_THROTTLING_RATE_LIMIT;
    private boolean cachingEnabled;
    private int cacheTtlInSeconds = UNSET_CACHE_TTL_SECONDS;
    private boolean cacheDataEncrypted;
    private boolean requireAuthorizationForCacheControl;
    private String unauthorizedCacheControlHeaderStrategy = "SUCCEED_WITH_RESPONSE_HEADER";

    public boolean isMetricsEnabled() { return metricsEnabled; }
    public void setMetricsEnabled(boolean v) { this.metricsEnabled = v; }

    public String getLoggingLevel() { return loggingLevel; }
    public void setLoggingLevel(String v) { this.loggingLevel = v; }

    public boolean isDataTraceEnabled() { return dataTraceEnabled; }
    public void setDataTraceEnabled(boolean v) { this.dataTraceEnabled = v; }

    public int getThrottlingBurstLimit() { return throttlingBurstLimit; }
    public void setThrottlingBurstLimit(int v) { this.throttlingBurstLimit = v; }

    public double getThrottlingRateLimit() { return throttlingRateLimit; }
    public void setThrottlingRateLimit(double v) { this.throttlingRateLimit = v; }

    public boolean isCachingEnabled() { return cachingEnabled; }
    public void setCachingEnabled(boolean v) { this.cachingEnabled = v; }

    public int getCacheTtlInSeconds() { return cacheTtlInSeconds; }
    public void setCacheTtlInSeconds(int v) { this.cacheTtlInSeconds = v; }

    public boolean isCacheDataEncrypted() { return cacheDataEncrypted; }
    public void setCacheDataEncrypted(boolean v) { this.cacheDataEncrypted = v; }

    public boolean isRequireAuthorizationForCacheControl() { return requireAuthorizationForCacheControl; }
    public void setRequireAuthorizationForCacheControl(boolean v) { this.requireAuthorizationForCacheControl = v; }

    public String getUnauthorizedCacheControlHeaderStrategy() { return unauthorizedCacheControlHeaderStrategy; }
    public void setUnauthorizedCacheControlHeaderStrategy(String v) { this.unauthorizedCacheControlHeaderStrategy = v; }
}
