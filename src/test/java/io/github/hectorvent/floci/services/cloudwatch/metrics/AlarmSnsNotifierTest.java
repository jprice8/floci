package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.CompositeAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import io.github.hectorvent.floci.services.sns.SnsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AlarmSnsNotifierTest {
    private static final String REGION = "us-east-1";
    private static final String TOPIC = "arn:aws:sns:us-east-1:000000000000:alarms";
    private final ObjectMapper mapper = new ObjectMapper();
    private SnsService sns;
    private CloudWatchMetricsService service;
    private MetricAlarm alarm;

    @BeforeEach
    void setUp() {
        sns = mock(SnsService.class);
        service = new CloudWatchMetricsService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver(REGION, "000000000000"), new AlarmSnsNotifier(sns, mapper));
        alarm = new MetricAlarm();
        alarm.setAlarmName("test-alarm");
        alarm.setActionsEnabled(true);
        alarm.setAlarmActions(List.of(TOPIC));
        alarm.setOkActions(List.of(TOPIC));
        alarm.setInsufficientDataActions(List.of(TOPIC));
        alarm.setEvaluationPeriods(3);
        alarm.setStatistic("Average");
        alarm.setMetricName("Errors");
        alarm.setNamespace("Local/PoC");
        service.putMetricAlarm(alarm, REGION);
    }

    @Test
    void publishesAwsPayloadForEachTransitionButNotRepeatedState() throws Exception {
        service.setAlarmState("test-alarm", "ALARM", "breached", null, REGION);
        service.setAlarmState("test-alarm", "ALARM", "still breached", null, REGION);
        service.setAlarmState("test-alarm", "OK", "recovered", null, REGION);
        service.setAlarmState("test-alarm", "INSUFFICIENT_DATA", "missing", null, REGION);
        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        verify(sns, times(3)).publish(eq(TOPIC), isNull(), messages.capture(), anyString(), eq(REGION));
        JsonNode first = mapper.readTree(messages.getAllValues().getFirst());
        assertEquals("test-alarm", first.path("AlarmName").asText());
        assertEquals("INSUFFICIENT_DATA", first.path("OldStateValue").asText());
        assertEquals("ALARM", first.path("NewStateValue").asText());
        assertEquals("000000000000", first.path("AWSAccountId").asText());
        assertEquals(alarm.getAlarmArn(), first.path("AlarmArn").asText());
        assertEquals("Errors", first.path("Trigger").path("MetricName").asText());
        assertFalse(first.path("Trigger").has("DatapointsToAlarm"));
        assertEquals("AVERAGE", first.path("Trigger").path("Statistic").asText());
        assertEquals("", first.path("Trigger").path("EvaluateLowSampleCountPercentile").asText());
        assertTrue(first.path("Trigger").path("EvaluateLowSampleCountPercentile").isTextual());
        assertNull(alarm.getDatapointsToAlarm());
        assertEquals("OK", mapper.readTree(messages.getAllValues().get(2)).path("OldStateValue").asText());
    }

    @Test
    void formatsBothTimestampsWithMillisAndUtcOffset() throws Exception {
        alarm.setAlarmConfigurationUpdatedTimestamp(1716280510L);
        service.setAlarmState("test-alarm", "ALARM", "breached", null, REGION);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(sns).publish(eq(TOPIC), isNull(), message.capture(), anyString(), eq(REGION));
        JsonNode payload = mapper.readTree(message.getValue());
        assertEquals("2024-05-21T08:35:10.000+0000",
                payload.path("AlarmConfigurationUpdatedTimestamp").asText());
        String stateChangeTime = payload.path("StateChangeTime").asText();
        assertTrue(stateChangeTime
                .matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}\\+0000"));
        assertEquals(alarm.getStateUpdatedTimestamp(),
                Instant.parse(stateChangeTime.replace("+0000", "Z")).getEpochSecond());
    }

    @Test
    void preservesExplicitDatapointsToAlarmInPayload() throws Exception {
        alarm.setDatapointsToAlarm(2);
        alarm.setStatistic("Maximum");
        alarm.setEvaluateLowSampleCountPercentile("ignore");
        service.setAlarmState("test-alarm", "ALARM", "breached", null, REGION);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(sns).publish(eq(TOPIC), isNull(), message.capture(), anyString(), eq(REGION));
        assertEquals(2, mapper.readTree(message.getValue()).path("Trigger").path("DatapointsToAlarm").intValue());
        JsonNode trigger = mapper.readTree(message.getValue()).path("Trigger");
        assertEquals("MAXIMUM", trigger.path("Statistic").asText());
        assertEquals("ignore", trigger.path("EvaluateLowSampleCountPercentile").asText());
        assertEquals(2, alarm.getDatapointsToAlarm());
    }

    @Test
    void disabledActionsDoNotPublish() {
        alarm.setActionsEnabled(false);
        service.setAlarmState("test-alarm", "ALARM", "breached", null, REGION);
        verifyNoInteractions(sns);
        assertEquals("ALARM", alarm.getStateValue());
    }

    @Test
    void failedTargetDoesNotPreventOtherTargetsOrStateChange() {
        String missing = "arn:aws:sns:us-east-1:000000000000:missing";
        alarm.setAlarmActions(List.of(missing, "arn:aws:autoscaling:us-east-1:000000000000:scalingPolicy:x", TOPIC));
        doThrow(new IllegalStateException("missing topic"))
                .when(sns).publish(eq(missing), isNull(), anyString(), anyString(), eq(REGION));
        assertDoesNotThrow(() -> service.setAlarmState("test-alarm", "ALARM", "breached", null, REGION));
        verify(sns).publish(eq(TOPIC), isNull(), anyString(), anyString(), eq(REGION));
        assertEquals("ALARM", alarm.getStateValue());
    }

    @Test
    void usesTopicRegionAndPreservesChinaPartition() {
        String topic = "arn:aws-cn:sns:cn-north-1:000000000000:alarms";
        alarm.setAlarmArn("arn:aws-cn:cloudwatch:cn-north-1:000000000000:alarm:test-alarm");
        alarm.setAlarmActions(List.of(topic));
        service.setAlarmState("test-alarm", "ALARM", "breached", null, REGION);
        verify(sns).publish(eq(topic), isNull(), contains("arn:aws-cn:cloudwatch:"), anyString(), eq("cn-north-1"));
    }

    @Test
    void compositeTransitionsPublishTheCompositeSchemaWithTheTriggeringChild() throws Exception {
        alarm.setActionsEnabled(false);
        CompositeAlarm composite = composite("watcher", "ALARM(\"test-alarm\")");
        service.setAlarmState("test-alarm", "ALARM", "breached", null, REGION);

        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> subjects = ArgumentCaptor.forClass(String.class);
        verify(sns, times(2)).publish(eq(TOPIC), isNull(), messages.capture(), subjects.capture(), eq(REGION));
        JsonNode created = mapper.readTree(messages.getAllValues().getFirst());
        assertEquals("INSUFFICIENT_DATA", created.path("OldStateValue").asText());
        assertEquals("OK", created.path("NewStateValue").asText());
        assertEquals(composite.getAlarmArn() + " was created and its alarm rule evaluates to OK",
                created.path("NewStateReason").asText());

        JsonNode payload = mapper.readTree(messages.getAllValues().getLast());
        assertEquals("ALARM: \"watcher\"", subjects.getAllValues().getLast());
        assertEquals("watcher", payload.path("AlarmName").asText());
        assertEquals("000000000000", payload.path("AWSAccountId").asText());
        assertEquals("OK", payload.path("OldStateValue").asText());
        assertEquals("ALARM", payload.path("NewStateValue").asText());
        assertTrue(payload.path("NewStateReason").asText()
                .startsWith(alarm.getAlarmArn() + " transitioned to ALARM at "), payload.toString());
        assertEquals("ALARM(\"test-alarm\")", payload.path("AlarmRule").asText());
        assertEquals(composite.getAlarmArn(), payload.path("AlarmArn").asText());
        assertEquals("US East (N. Virginia)", payload.path("Region").asText());
        assertEquals(1, payload.path("TriggeringChildren").size());
        JsonNode child = payload.path("TriggeringChildren").get(0);
        assertEquals(alarm.getAlarmArn(), child.path("Arn").asText());
        assertEquals("ALARM", child.path("State").path("Value").asText());
        assertTrue(child.path("State").path("Timestamp").asText()
                .matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}\\+0000"));
        assertFalse(payload.has("Trigger"));
        assertFalse(payload.has("AlarmConfigurationUpdatedTimestamp"));
    }

    @Test
    void aCompositeWithActionsDisabledOrAManualStateWithoutDataStillBehaves() throws Exception {
        CompositeAlarm quiet = composite("quiet", "ALARM(\"test-alarm\")");
        quiet.setActionsEnabled(false);
        CompositeAlarm loud = composite("loud", "TRUE");
        clearInvocations(sns);
        service.setAlarmState("test-alarm", "ALARM", "breached", null, REGION);
        service.setAlarmState("loud", "OK", "manual", null, REGION);

        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        verify(sns, times(2)).publish(eq(TOPIC), isNull(), messages.capture(), anyString(), eq(REGION));
        assertEquals("test-alarm", mapper.readTree(messages.getAllValues().getFirst()).path("AlarmName").asText());
        JsonNode manual = mapper.readTree(messages.getAllValues().getLast());
        assertEquals(loud.getAlarmName(), manual.path("AlarmName").asText());
        assertEquals("manual", manual.path("NewStateReason").asText());
        assertEquals(0, manual.path("TriggeringChildren").size());
        assertEquals("ALARM", service.describeCompositeAlarms(List.of("quiet"), null, REGION).getFirst().getStateValue());
    }

    @Test
    void aBlockedPublishDoesNotHoldUpOtherAlarmChanges() throws Exception {
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            if (call.<String>getArgument(3).startsWith("ALARM:")) {
                publishing.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return "message-id";
        }).when(sns).publish(eq(TOPIC), isNull(), anyString(), anyString(), eq(REGION));

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> transition = executor.submit(() ->
                    service.setAlarmState("test-alarm", "ALARM", "breached", null, REGION));
            try {
                assertTrue(publishing.await(2, TimeUnit.SECONDS));
                Future<?> other = executor.submit(() -> {
                    composite("unrelated", "FALSE");
                    service.setAlarmState("test-alarm", "OK", "recovered", null, REGION);
                });
                other.get(2, TimeUnit.SECONDS);
                assertEquals("OK", alarm.getStateValue());
            } finally {
                release.countDown();
            }
            transition.get(2, TimeUnit.SECONDS);
        }
        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        verify(sns, times(3)).publish(eq(TOPIC), isNull(), messages.capture(), anyString(), eq(REGION));
        List<String> states = messages.getAllValues().stream()
                .map(message -> {
                    try {
                        return mapper.readTree(message).path("AlarmName").asText() + " "
                                + mapper.readTree(message).path("NewStateValue").asText();
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                }).toList();
        assertEquals(List.of("test-alarm ALARM", "unrelated OK", "test-alarm OK"), states);
    }

    private CompositeAlarm composite(String name, String rule) {
        CompositeAlarm composite = new CompositeAlarm();
        composite.setAlarmName(name);
        composite.setAlarmRule(rule);
        composite.setActionsEnabled(true);
        composite.setAlarmActions(List.of(TOPIC));
        composite.setOkActions(List.of(TOPIC));
        service.putCompositeAlarm(composite, REGION);
        return composite;
    }
}
