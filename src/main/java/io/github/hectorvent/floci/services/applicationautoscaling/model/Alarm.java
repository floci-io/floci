package io.github.hectorvent.floci.services.applicationautoscaling.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A CloudWatch alarm associated with a scaling policy.
 *
 * @see <a href="https://docs.aws.amazon.com/autoscaling/application/APIReference/API_Alarm.html">Alarm</a>
 */
@RegisterForReflection
public class Alarm {

    private String alarmName;
    private String alarmArn;

    public Alarm() {
    }

    public Alarm(String alarmName, String alarmArn) {
        this.alarmName = alarmName;
        this.alarmArn = alarmArn;
    }

    public String getAlarmName() { return alarmName; }
    public void setAlarmName(String alarmName) { this.alarmName = alarmName; }

    public String getAlarmArn() { return alarmArn; }
    public void setAlarmArn(String alarmArn) { this.alarmArn = alarmArn; }
}
