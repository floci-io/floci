package io.github.hectorvent.floci.services.cloudwatch.metrics.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A CloudWatch composite alarm. Floci records the {@code AlarmRule} but never evaluates it,
 * so the alarm reports {@code OK} from the first read instead of modelling a transition.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CompositeAlarm {

    public static final String STATE_OK = "OK";

    private String alarmName;
    private String alarmArn;
    private String alarmRule;
    private String alarmDescription;
    private boolean actionsEnabled = true;
    private List<String> okActions = new ArrayList<>();
    private List<String> alarmActions = new ArrayList<>();
    private List<String> insufficientDataActions = new ArrayList<>();
    private String stateValue = STATE_OK;
    private String stateReason = "Composite alarm rule is not evaluated by Floci";
    private String stateReasonData;
    private long stateUpdatedTimestamp;
    private long stateTransitionedTimestamp;
    private long alarmConfigurationUpdatedTimestamp;
    private String actionsSuppressor;
    private Integer actionsSuppressorWaitPeriod;
    private Integer actionsSuppressorExtensionPeriod;
    private Map<String, String> tags = new LinkedHashMap<>();

    public CompositeAlarm() {
        long now = Instant.now().getEpochSecond();
        this.stateUpdatedTimestamp = now;
        this.stateTransitionedTimestamp = now;
        this.alarmConfigurationUpdatedTimestamp = now;
    }

    public String getAlarmName() { return alarmName; }
    public void setAlarmName(String alarmName) { this.alarmName = alarmName; }

    public String getAlarmArn() { return alarmArn; }
    public void setAlarmArn(String alarmArn) { this.alarmArn = alarmArn; }

    public String getAlarmRule() { return alarmRule; }
    public void setAlarmRule(String alarmRule) { this.alarmRule = alarmRule; }

    public String getAlarmDescription() { return alarmDescription; }
    public void setAlarmDescription(String alarmDescription) { this.alarmDescription = alarmDescription; }

    public boolean isActionsEnabled() { return actionsEnabled; }
    public void setActionsEnabled(boolean actionsEnabled) { this.actionsEnabled = actionsEnabled; }

    public List<String> getOkActions() { return okActions; }
    public void setOkActions(List<String> okActions) { this.okActions = okActions; }

    public List<String> getAlarmActions() { return alarmActions; }
    public void setAlarmActions(List<String> alarmActions) { this.alarmActions = alarmActions; }

    public List<String> getInsufficientDataActions() { return insufficientDataActions; }
    public void setInsufficientDataActions(List<String> insufficientDataActions) {
        this.insufficientDataActions = insufficientDataActions;
    }

    public String getStateValue() { return stateValue; }
    public void setStateValue(String stateValue) { this.stateValue = stateValue; }

    public String getStateReason() { return stateReason; }
    public void setStateReason(String stateReason) { this.stateReason = stateReason; }

    public String getStateReasonData() { return stateReasonData; }
    public void setStateReasonData(String stateReasonData) { this.stateReasonData = stateReasonData; }

    public long getStateUpdatedTimestamp() { return stateUpdatedTimestamp; }
    public void setStateUpdatedTimestamp(long stateUpdatedTimestamp) { this.stateUpdatedTimestamp = stateUpdatedTimestamp; }

    public long getStateTransitionedTimestamp() { return stateTransitionedTimestamp; }
    public void setStateTransitionedTimestamp(long stateTransitionedTimestamp) {
        this.stateTransitionedTimestamp = stateTransitionedTimestamp;
    }

    public long getAlarmConfigurationUpdatedTimestamp() { return alarmConfigurationUpdatedTimestamp; }
    public void setAlarmConfigurationUpdatedTimestamp(long timestamp) { this.alarmConfigurationUpdatedTimestamp = timestamp; }

    public String getActionsSuppressor() { return actionsSuppressor; }
    public void setActionsSuppressor(String actionsSuppressor) { this.actionsSuppressor = actionsSuppressor; }

    public Integer getActionsSuppressorWaitPeriod() { return actionsSuppressorWaitPeriod; }
    public void setActionsSuppressorWaitPeriod(Integer period) { this.actionsSuppressorWaitPeriod = period; }

    public Integer getActionsSuppressorExtensionPeriod() { return actionsSuppressorExtensionPeriod; }
    public void setActionsSuppressorExtensionPeriod(Integer period) { this.actionsSuppressorExtensionPeriod = period; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
