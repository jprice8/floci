package com.floci.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.AlarmType;
import software.amazon.awssdk.services.cloudwatch.model.CloudWatchException;
import software.amazon.awssdk.services.cloudwatch.model.ComparisonOperator;
import software.amazon.awssdk.services.cloudwatch.model.CompositeAlarm;
import software.amazon.awssdk.services.cloudwatch.model.DescribeAlarmsResponse;
import software.amazon.awssdk.services.cloudwatch.model.StateValue;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;
import software.amazon.awssdk.services.cloudwatch.model.Tag;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CloudWatch - Composite alarms")
class CloudWatchCompositeAlarmTest {

    private CloudWatchClient cloudWatch;
    private final List<String> composites = new ArrayList<>();
    private final List<String> metricAlarms = new ArrayList<>();

    @BeforeEach
    void setup() {
        cloudWatch = TestFixtures.cloudWatchClient();
    }

    @AfterEach
    void cleanup() {
        try {
            // Parents first: AWS refuses to delete an alarm a composite alarm still references.
            for (int index = composites.size() - 1; index >= 0; index--) {
                String name = composites.get(index);
                cloudWatch.deleteAlarms(request -> request.alarmNames(name));
            }
            if (!metricAlarms.isEmpty()) {
                cloudWatch.deleteAlarms(request -> request.alarmNames(metricAlarms));
            }
        } finally {
            cloudWatch.close();
        }
    }

    @Test
    @DisplayName("Composite alarms follow their children and appear only when asked for")
    void compositeAlarmsFollowTheirChildren() {
        String a = metricAlarm("sdk-composite-a");
        String b = metricAlarm("sdk-composite-b");
        String any = composite("sdk-composite-any", "ALARM(\"" + a + "\") OR ALARM(\"" + b + "\")");
        String both = composite("sdk-composite-both", "AT_LEAST(2, ALARM, (" + a + ", " + b + "))");

        assertThat(cloudWatch.describeAlarms(request -> request.alarmNames(any)).compositeAlarms()).isEmpty();
        CompositeAlarm created = describe(any);
        assertThat(created.alarmRule()).isEqualTo("ALARM(\"" + a + "\") OR ALARM(\"" + b + "\")");
        assertThat(created.stateValue()).isEqualTo(StateValue.OK);
        assertThat(created.stateReason()).isEqualTo(created.alarmArn() + " was created and its alarm rule evaluates to OK");
        assertThat(created.stateReasonData()).contains("\"triggeringAlarms\"");
        assertThat(created.stateTransitionedTimestamp()).isNotNull();
        assertThat(created.actionsEnabled()).isFalse();
        assertThat(cloudWatch.listTagsForResource(request -> request.resourceARN(created.alarmArn())).tags())
                .containsExactly(Tag.builder().key("suite").value("compat").build());

        cloudWatch.setAlarmState(request -> request.alarmName(a).stateValue(StateValue.ALARM).stateReason("test"));
        assertThat(describe(any).stateValue()).isEqualTo(StateValue.ALARM);
        assertThat(describe(any).stateReason()).startsWith(describeMetricArn(a) + " transitioned to ALARM at ");
        assertThat(describe(both).stateValue()).isEqualTo(StateValue.OK);

        cloudWatch.setAlarmState(request -> request.alarmName(b).stateValue(StateValue.ALARM).stateReason("test"));
        assertThat(describe(both).stateValue()).isEqualTo(StateValue.ALARM);

        DescribeAlarmsResponse mixed = cloudWatch.describeAlarms(request -> request.alarmNames(a, any)
                .alarmTypes(AlarmType.METRIC_ALARM, AlarmType.COMPOSITE_ALARM));
        assertThat(mixed.metricAlarms()).extracting("alarmName").containsExactly(a);
        assertThat(mixed.compositeAlarms()).extracting("alarmName").containsExactly(any);
    }

    @Test
    @DisplayName("Invalid rules, unknown children and deleting a referenced alarm fail as on AWS")
    void validationErrorsMatchAws() {
        String child = metricAlarm("sdk-composite-child");
        composite("sdk-composite-parent", "OK(" + child + ")");

        assertThatThrownBy(() -> cloudWatch.deleteAlarms(request -> request.alarmNames(child)))
                .isInstanceOfSatisfying(CloudWatchException.class, error -> {
                    assertThat(error.awsErrorDetails().errorCode()).isEqualTo("ValidationError");
                    assertThat(error.awsErrorDetails().errorMessage())
                            .isEqualTo("Cannot delete " + child + " as there are composite alarm(s) depending on it.");
                });
        assertThatThrownBy(() -> cloudWatch.putCompositeAlarm(request -> request
                .alarmName(TestFixtures.uniqueName("sdk-composite-bad")).alarmRule("ALARM(" + child + ") XOR TRUE")))
                .isInstanceOfSatisfying(CloudWatchException.class, error -> assertThat(
                        error.awsErrorDetails().errorMessage()).startsWith("Error in AlarmRule [Unsupported token 'XOR'"));
        String missing = TestFixtures.uniqueName("sdk-composite-missing");
        assertThatThrownBy(() -> cloudWatch.putCompositeAlarm(request -> request
                .alarmName(TestFixtures.uniqueName("sdk-composite-orphan")).alarmRule("ALARM(" + missing + ")")))
                .isInstanceOfSatisfying(CloudWatchException.class, error -> assertThat(
                        error.awsErrorDetails().errorMessage()).startsWith("Could not save the composite alarm as alarms [")
                        .endsWith(":alarm:" + missing + "] in the alarm rule do not exist"));
    }

    private String metricAlarm(String prefix) {
        String name = TestFixtures.uniqueName(prefix);
        cloudWatch.putMetricAlarm(request -> request.alarmName(name).namespace("Compat/Composite")
                .metricName("Errors").statistic(Statistic.SUM).period(3600).evaluationPeriods(1).threshold(1.0)
                .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD).actionsEnabled(false));
        metricAlarms.add(name);
        return name;
    }

    private String composite(String prefix, String rule) {
        String name = TestFixtures.uniqueName(prefix);
        cloudWatch.putCompositeAlarm(request -> request.alarmName(name).alarmRule(rule).actionsEnabled(false)
                .tags(Tag.builder().key("suite").value("compat").build()));
        composites.add(name);
        return name;
    }

    private CompositeAlarm describe(String name) {
        List<CompositeAlarm> found = cloudWatch.describeAlarms(request -> request.alarmNames(name)
                .alarmTypes(AlarmType.COMPOSITE_ALARM)).compositeAlarms();
        assertThat(found).hasSize(1);
        return found.getFirst();
    }

    private String describeMetricArn(String name) {
        return cloudWatch.describeAlarms(request -> request.alarmNames(name)).metricAlarms().getFirst().alarmArn();
    }
}
