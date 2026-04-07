package io.github.hectorvent.floci.services.scheduler.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
public class Schedule {

    private String name;
    private String arn;
    private String groupName;
    private String state;
    private String scheduleExpression;
    private String scheduleExpressionTimezone;
    private FlexibleTimeWindow flexibleTimeWindow;
    private Target target;
    private String description;
    private String actionAfterCompletion;
    private Instant startDate;
    private Instant endDate;
    private String kmsKeyArn;
    private Instant creationDate;
    private Instant lastModificationDate;

    public Schedule() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getScheduleExpression() { return scheduleExpression; }
    public void setScheduleExpression(String scheduleExpression) { this.scheduleExpression = scheduleExpression; }

    public String getScheduleExpressionTimezone() { return scheduleExpressionTimezone; }
    public void setScheduleExpressionTimezone(String scheduleExpressionTimezone) { this.scheduleExpressionTimezone = scheduleExpressionTimezone; }

    public FlexibleTimeWindow getFlexibleTimeWindow() { return flexibleTimeWindow; }
    public void setFlexibleTimeWindow(FlexibleTimeWindow flexibleTimeWindow) { this.flexibleTimeWindow = flexibleTimeWindow; }

    public Target getTarget() { return target; }
    public void setTarget(Target target) { this.target = target; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getActionAfterCompletion() { return actionAfterCompletion; }
    public void setActionAfterCompletion(String actionAfterCompletion) { this.actionAfterCompletion = actionAfterCompletion; }

    public Instant getStartDate() { return startDate; }
    public void setStartDate(Instant startDate) { this.startDate = startDate; }

    public Instant getEndDate() { return endDate; }
    public void setEndDate(Instant endDate) { this.endDate = endDate; }

    public String getKmsKeyArn() { return kmsKeyArn; }
    public void setKmsKeyArn(String kmsKeyArn) { this.kmsKeyArn = kmsKeyArn; }

    public Instant getCreationDate() { return creationDate; }
    public void setCreationDate(Instant creationDate) { this.creationDate = creationDate; }

    public Instant getLastModificationDate() { return lastModificationDate; }
    public void setLastModificationDate(Instant lastModificationDate) { this.lastModificationDate = lastModificationDate; }
}
