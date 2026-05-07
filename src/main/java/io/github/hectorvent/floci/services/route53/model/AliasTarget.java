package io.github.hectorvent.floci.services.route53.model;

public class AliasTarget {

    private String hostedZoneId;
    private String dnsName;
    private boolean evaluateTargetHealth;

    public AliasTarget() {}

    public String getHostedZoneId() { return hostedZoneId; }
    public void setHostedZoneId(String hostedZoneId) { this.hostedZoneId = hostedZoneId; }

    public String getDnsName() { return dnsName; }
    public void setDnsName(String dnsName) { this.dnsName = dnsName; }

    public boolean isEvaluateTargetHealth() { return evaluateTargetHealth; }
    public void setEvaluateTargetHealth(boolean evaluateTargetHealth) {
        this.evaluateTargetHealth = evaluateTargetHealth;
    }
}
