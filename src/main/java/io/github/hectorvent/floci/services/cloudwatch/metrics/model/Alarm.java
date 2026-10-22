package io.github.hectorvent.floci.services.cloudwatch.metrics.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The members every kind of CloudWatch alarm has, whatever decides its state. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public abstract class Alarm {
    private String alarmName;
    private String alarmArn;
    private String alarmDescription;
    private long alarmConfigurationUpdatedTimestamp;
    private boolean actionsEnabled;
    private List<String> okActions = new ArrayList<>();
    private List<String> alarmActions = new ArrayList<>();
    private List<String> insufficientDataActions = new ArrayList<>();
    private String stateValue = "INSUFFICIENT_DATA";
    private String stateReason = "Unchecked";
    private String stateReasonData;
    private long stateUpdatedTimestamp;
    private Map<String, String> tags = new HashMap<>();
    private String region;

    protected Alarm() {
        long now = Instant.now().getEpochSecond();
        this.alarmConfigurationUpdatedTimestamp = now;
        this.stateUpdatedTimestamp = now;
    }

    public String getAlarmName() { return alarmName; }
    public void setAlarmName(String alarmName) { this.alarmName = alarmName; }

    public String getAlarmArn() { return alarmArn; }
    public void setAlarmArn(String alarmArn) { this.alarmArn = alarmArn; }

    public String getAlarmDescription() { return alarmDescription; }
    public void setAlarmDescription(String alarmDescription) { this.alarmDescription = alarmDescription; }

    public long getAlarmConfigurationUpdatedTimestamp() { return alarmConfigurationUpdatedTimestamp; }
    public void setAlarmConfigurationUpdatedTimestamp(long timestamp) { this.alarmConfigurationUpdatedTimestamp = timestamp; }

    public boolean isActionsEnabled() { return actionsEnabled; }
    public void setActionsEnabled(boolean actionsEnabled) { this.actionsEnabled = actionsEnabled; }

    public List<String> getOkActions() { return okActions; }
    public void setOkActions(List<String> okActions) { this.okActions = okActions; }

    public List<String> getAlarmActions() { return alarmActions; }
    public void setAlarmActions(List<String> alarmActions) { this.alarmActions = alarmActions; }

    public List<String> getInsufficientDataActions() { return insufficientDataActions; }
    public void setInsufficientDataActions(List<String> insufficientDataActions) { this.insufficientDataActions = insufficientDataActions; }

    public String getStateValue() { return stateValue; }
    public void setStateValue(String stateValue) { this.stateValue = stateValue; }

    public String getStateReason() { return stateReason; }
    public void setStateReason(String stateReason) { this.stateReason = stateReason; }

    public String getStateReasonData() { return stateReasonData; }
    public void setStateReasonData(String stateReasonData) { this.stateReasonData = stateReasonData; }

    public long getStateUpdatedTimestamp() { return stateUpdatedTimestamp; }
    public void setStateUpdatedTimestamp(long stateUpdatedTimestamp) { this.stateUpdatedTimestamp = stateUpdatedTimestamp; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    /** Not part of the AWS shape; set when the alarm is stored so a background evaluator
     * that only holds the alarm object still knows which region's data to query. */
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
}
