package io.github.hectorvent.floci.services.cloudwatch.metrics.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class CompositeAlarm extends Alarm {
    private String alarmRule;
    private String actionsSuppressor;
    private Integer actionsSuppressorWaitPeriod;
    private Integer actionsSuppressorExtensionPeriod;
    /**
     * Not part of the AWS shape: the ARNs of the child alarms whose state changed since this
     * alarm last changed state, oldest first. On the next transition AWS names the ones that
     * agree with the new state in {@code StateReasonData}.
     */
    private List<String> changedChildren = new ArrayList<>();

    @Override
    public String getAlarmType() { return "CompositeAlarm"; }

    public String getAlarmRule() { return alarmRule; }
    public void setAlarmRule(String alarmRule) { this.alarmRule = alarmRule; }

    public String getActionsSuppressor() { return actionsSuppressor; }
    public void setActionsSuppressor(String actionsSuppressor) { this.actionsSuppressor = actionsSuppressor; }

    public Integer getActionsSuppressorWaitPeriod() { return actionsSuppressorWaitPeriod; }
    public void setActionsSuppressorWaitPeriod(Integer seconds) { this.actionsSuppressorWaitPeriod = seconds; }

    public Integer getActionsSuppressorExtensionPeriod() { return actionsSuppressorExtensionPeriod; }
    public void setActionsSuppressorExtensionPeriod(Integer seconds) { this.actionsSuppressorExtensionPeriod = seconds; }

    public List<String> getChangedChildren() { return changedChildren; }
    public void setChangedChildren(List<String> changedChildren) {
        this.changedChildren = changedChildren == null ? new ArrayList<>() : new ArrayList<>(changedChildren);
    }
}
