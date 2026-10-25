package io.github.hectorvent.floci.services.cloudwatch.metrics.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class CompositeAlarm extends Alarm {
    private String alarmRule;

    @Override
    public String getAlarmType() { return "CompositeAlarm"; }

    public String getAlarmRule() { return alarmRule; }
    public void setAlarmRule(String alarmRule) { this.alarmRule = alarmRule; }
}
