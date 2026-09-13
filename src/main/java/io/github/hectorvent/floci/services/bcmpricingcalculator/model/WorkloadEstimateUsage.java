package io.github.hectorvent.floci.services.bcmpricingcalculator.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class WorkloadEstimateUsage {
    private String id;
    private String workloadEstimateId;
    private String key;
    private String serviceCode;
    private String usageType;
    private String operation;
    private String usageAccountId;
    private String group;
    private double amount;
    private String unit;
    private double cost;
    private String currency;
    private String status;
    private String location;

    public WorkloadEstimateUsage() {}
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getWorkloadEstimateId() { return workloadEstimateId; }
    public void setWorkloadEstimateId(String workloadEstimateId) { this.workloadEstimateId = workloadEstimateId; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getServiceCode() { return serviceCode; }
    public void setServiceCode(String serviceCode) { this.serviceCode = serviceCode; }
    public String getUsageType() { return usageType; }
    public void setUsageType(String usageType) { this.usageType = usageType; }
    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }
    public String getUsageAccountId() { return usageAccountId; }
    public void setUsageAccountId(String usageAccountId) { this.usageAccountId = usageAccountId; }
    public String getGroup() { return group; }
    public void setGroup(String group) { this.group = group; }
    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public double getCost() { return cost; }
    public void setCost(double cost) { this.cost = cost; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
}
