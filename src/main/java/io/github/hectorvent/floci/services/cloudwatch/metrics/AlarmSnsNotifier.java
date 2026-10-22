package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsPartition;
import io.github.hectorvent.floci.core.common.AwsPartitions;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Alarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.CompositeAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import io.github.hectorvent.floci.services.sns.SnsService;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Publishes SNS actions once per state transition, independently of scaling action retries.
 * {@link #prepareTransition} builds the messages while the caller still holds the alarm state
 * it describes; {@link #publish} sends them once the caller has let go of it.
 */
@ApplicationScoped
public class AlarmSnsNotifier {

    private static final Logger LOG = Logger.getLogger(AlarmSnsNotifier.class);
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ROOT).withZone(ZoneOffset.UTC);
    private final SnsService snsService;
    private final ObjectMapper objectMapper;

    public AlarmSnsNotifier(SnsService snsService, ObjectMapper objectMapper) {
        this.snsService = snsService;
        this.objectMapper = objectMapper;
    }

    /** A message for one SNS action, built when the alarm changed state and published later. */
    record Notification(String topicArn, String message, String subject, String region) {}

    List<Notification> prepareTransition(Alarm alarm, String oldState) {
        String state = alarm.getStateValue();
        if (!alarm.isActionsEnabled() || state == null || state.equals(oldState)) {
            return List.of();
        }
        List<String> actions = switch (state) {
            case "ALARM" -> alarm.getAlarmActions();
            case "OK" -> alarm.getOkActions();
            case "INSUFFICIENT_DATA" -> alarm.getInsufficientDataActions();
            default -> List.of();
        };
        if (actions == null) {
            return List.of();
        }
        List<Notification> notifications = new ArrayList<>();
        for (String action : actions) {
            if (!AwsArnUtils.isArnFor(action, "sns")) {
                continue;
            }
            try {
                AwsArnUtils.Arn topic = AwsArnUtils.parse(action);
                String subject = state + ": \"" + alarm.getAlarmName() + "\"";
                if (subject.length() > 100) {
                    subject = subject.substring(0, 100);
                }
                notifications.add(new Notification(action, message(alarm, oldState), subject, topic.region()));
            } catch (Exception e) {
                LOG.warnv(e, "Unable to prepare alarm {0} state {1} for SNS action {2}",
                        alarm.getAlarmName(), state, action);
            }
        }
        return notifications;
    }

    void publish(List<Notification> notifications) {
        for (Notification notification : notifications) {
            try {
                snsService.publish(notification.topicArn(), null, notification.message(),
                        notification.subject(), notification.region());
            } catch (Exception e) {
                LOG.warnv(e, "Unable to publish alarm transition {0} to SNS action {1}",
                        notification.subject(), notification.topicArn());
            }
        }
    }

    private String message(Alarm alarm, String oldState) throws JsonProcessingException {
        return switch (alarm) {
            case MetricAlarm metric -> metricMessage(metric, oldState);
            case CompositeAlarm composite -> compositeMessage(composite, oldState);
            default -> throw new IllegalArgumentException("Unknown alarm type " + alarm.getAlarmType());
        };
    }

    private String metricMessage(MetricAlarm alarm, String oldState) throws JsonProcessingException {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(alarm.getAlarmArn());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("AlarmName", alarm.getAlarmName());
        payload.put("AlarmDescription", alarm.getAlarmDescription());
        payload.put("AWSAccountId", arn.accountId());
        payload.put("AlarmConfigurationUpdatedTimestamp", timestamp(alarm.getAlarmConfigurationUpdatedTimestamp()));
        payload.put("NewStateValue", alarm.getStateValue());
        payload.put("NewStateReason", alarm.getStateReason());
        payload.put("StateChangeTime", timestamp(alarm.getStateUpdatedTimestamp()));
        payload.put("Region", regionName(arn));
        payload.put("AlarmArn", alarm.getAlarmArn());
        payload.put("OldStateValue", oldState);
        payload.put("OKActions", alarm.getOkActions());
        payload.put("AlarmActions", alarm.getAlarmActions());
        payload.put("InsufficientDataActions", alarm.getInsufficientDataActions());
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("MetricName", alarm.getMetricName());
        trigger.put("Namespace", alarm.getNamespace());
        trigger.put("StatisticType", "Statistic");
        trigger.put("Statistic", alarm.getStatistic() != null
                ? alarm.getStatistic().toUpperCase(Locale.ROOT) : null);
        trigger.put("Unit", alarm.getUnit());
        trigger.put("Dimensions", alarm.getDimensions());
        trigger.put("Period", alarm.getPeriod());
        trigger.put("EvaluationPeriods", alarm.getEvaluationPeriods());
        if (alarm.getDatapointsToAlarm() != null) {
            trigger.put("DatapointsToAlarm", alarm.getDatapointsToAlarm());
        }
        trigger.put("ComparisonOperator", alarm.getComparisonOperator());
        trigger.put("Threshold", alarm.getThreshold());
        trigger.put("TreatMissingData", alarm.getTreatMissingData());
        trigger.put("EvaluateLowSampleCountPercentile", alarm.getEvaluateLowSampleCountPercentile() != null
                ? alarm.getEvaluateLowSampleCountPercentile() : "");
        payload.put("Trigger", trigger);
        return objectMapper.writeValueAsString(payload);
    }

    /**
     * AWS's composite alarm schema: no configuration timestamp and no {@code Trigger}, but the
     * rule and the child alarms that triggered the transition, taken from its StateReasonData.
     */
    private String compositeMessage(CompositeAlarm alarm, String oldState) throws JsonProcessingException {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(alarm.getAlarmArn());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("AlarmName", alarm.getAlarmName());
        payload.put("AlarmDescription", alarm.getAlarmDescription());
        payload.put("AWSAccountId", arn.accountId());
        payload.put("NewStateValue", alarm.getStateValue());
        payload.put("NewStateReason", alarm.getStateReason());
        payload.put("StateChangeTime", timestamp(alarm.getStateUpdatedTimestamp()));
        payload.put("Region", regionName(arn));
        payload.put("AlarmArn", alarm.getAlarmArn());
        payload.put("OKActions", alarm.getOkActions());
        payload.put("AlarmActions", alarm.getAlarmActions());
        payload.put("InsufficientDataActions", alarm.getInsufficientDataActions());
        payload.put("OldStateValue", oldState);
        payload.put("AlarmRule", alarm.getAlarmRule());
        List<Map<String, Object>> children = new ArrayList<>();
        for (JsonNode child : triggeringAlarms(alarm.getStateReasonData())) {
            Map<String, Object> childState = new LinkedHashMap<>();
            childState.put("Value", child.path("state").path("value").asText(null));
            childState.put("Timestamp", child.path("state").path("timestamp").asText(null));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("Arn", child.path("arn").asText(null));
            entry.put("State", childState);
            children.add(entry);
        }
        payload.put("TriggeringChildren", children);
        return objectMapper.writeValueAsString(payload);
    }

    /** The {@code triggeringAlarms} of reason data CloudWatch wrote; data from SetAlarmState may have none. */
    private JsonNode triggeringAlarms(String reasonData) {
        if (reasonData == null) {
            return objectMapper.createArrayNode();
        }
        try {
            return objectMapper.readTree(reasonData).path("triggeringAlarms");
        } catch (JsonProcessingException e) {
            LOG.debugv("Composite alarm reason data is not JSON, so no triggering children: {0}", e.getOriginalMessage());
            return objectMapper.createArrayNode();
        }
    }

    private static String regionName(AwsArnUtils.Arn arn) {
        return AwsPartitions.forRegion(arn.region()).stream()
                .flatMap(partition -> partition.regions().stream())
                .filter(region -> region.id().equals(arn.region()))
                .map(AwsPartition.Region::description).findFirst().orElse(arn.region());
    }

    private String timestamp(long epochSeconds) {
        return TIMESTAMP_FORMAT.format(Instant.ofEpochSecond(epochSeconds));
    }
}
