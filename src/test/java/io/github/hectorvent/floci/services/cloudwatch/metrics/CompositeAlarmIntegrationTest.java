package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/** PutCompositeAlarm and the composite side of DescribeAlarms, SetAlarmState and DeleteAlarms over both wire protocols. */
@QuarkusTest
class CompositeAlarmIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SCOPE = "AWS4-HMAC-SHA256 Credential=test/20261015/us-east-1/monitoring/aws4_request";
    private static final String ARN_PREFIX = "arn:aws:cloudwatch:us-east-1:000000000000:alarm:";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureAwsJson() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aCompositeAlarmFollowsItsChildOverEitherProtocol(boolean json) throws Exception {
        String child = unique("child");
        String parent = unique("parent");
        putMetricAlarm(json, child);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("AlarmName", parent);
        request.put("AlarmRule", "ALARM(\"" + child + "\")");
        request.put("AlarmDescription", "child is breaching");
        request.put("ActionsEnabled", false);
        request.put("Tags", List.of(Map.of("Key", "team", "Value", "ops")));
        call(json, "PutCompositeAlarm", request).then().statusCode(200);
        cleanup.register(() -> call(json, "DeleteAlarms", Map.of("AlarmNames", List.of(parent))));

        Map<String, Object> byName = Map.of("AlarmNames", List.of(parent));
        assertEquals(List.of(), describe(json, byName, "CompositeAlarms"));
        assertEquals(List.of(), describe(json, byName, "MetricAlarms"));

        Map<String, String> composite = onlyComposite(json, parent);
        assertEquals("ALARM(\"" + child + "\")", composite.get("AlarmRule"));
        assertEquals("child is breaching", composite.get("AlarmDescription"));
        assertEquals("false", composite.get("ActionsEnabled"));
        assertEquals(ARN_PREFIX + parent, composite.get("AlarmArn"));
        assertEquals("OK", composite.get("StateValue"));
        assertEquals(ARN_PREFIX + parent + " was created and its alarm rule evaluates to OK", composite.get("StateReason"));
        JsonNode reasonData = MAPPER.readTree(composite.get("StateReasonData"));
        assertEquals(ARN_PREFIX + child, reasonData.path("triggeringAlarms").get(0).path("arn").asText());
        assertEquals("INSUFFICIENT_DATA", reasonData.path("triggeringAlarms").get(0).path("state").path("value").asText());
        assertNotNull(composite.get("StateTransitionedTimestamp"));
        assertNotNull(composite.get("AlarmConfigurationUpdatedTimestamp"));

        Map<String, Object> both = Map.of("AlarmNames", List.of(child, parent),
                "AlarmTypes", List.of("MetricAlarm", "CompositeAlarm"));
        assertEquals(List.of(parent), describe(json, both, "CompositeAlarms"));
        assertEquals(List.of(child), describe(json, both, "MetricAlarms"));

        call(json, "SetAlarmState", Map.of("AlarmName", child, "StateValue", "ALARM", "StateReason", "breached"))
                .then().statusCode(200);
        composite = onlyComposite(json, parent);
        assertEquals("ALARM", composite.get("StateValue"));
        assertTrue(composite.get("StateReason").startsWith(ARN_PREFIX + child + " transitioned to ALARM at "),
                composite.get("StateReason"));

        Response tags = call(json, "ListTagsForResource", Map.of("ResourceARN", ARN_PREFIX + parent));
        tags.then().statusCode(200);
        assertEquals("team", json ? tags.jsonPath().getString("Tags[0].Key")
                : tags.xmlPath().getString("ListTagsForResourceResponse.ListTagsForResourceResult.Tags.member.Key"));

        assertError(json, call(json, "DeleteAlarms", Map.of("AlarmNames", List.of(child))),
                "Cannot delete " + child + " as there are composite alarm(s) depending on it.");
        call(json, "DeleteAlarms", Map.of("AlarmNames", List.of(parent))).then().statusCode(200);
        call(json, "DeleteAlarms", Map.of("AlarmNames", List.of(child))).then().statusCode(200);
        assertEquals(List.of(), describe(json, both, "CompositeAlarms"));
        assertEquals(List.of(), describe(json, both, "MetricAlarms"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void childrenAndParentsOfAnAlarmAreListedOnTheirOwn(boolean json) {
        String metric = unique("leaf");
        String inner = unique("inner");
        String outer = unique("outer");
        putMetricAlarm(json, metric);
        putComposite(json, inner, "ALARM(\"" + metric + "\")");
        putComposite(json, outer, "ALARM(" + inner + ") OR OK(\"" + ARN_PREFIX + metric + "\")");

        Map<String, Object> children = Map.of("ChildrenOfAlarmName", outer);
        assertEquals(List.of(metric), describe(json, children, "MetricAlarms"));
        assertEquals(List.of(inner), describe(json, children, "CompositeAlarms"));
        Map<String, Object> parents = Map.of("ParentsOfAlarmName", metric);
        assertEquals(List.of(), describe(json, parents, "MetricAlarms"));
        assertEquals(List.of(inner, outer), describe(json, parents, "CompositeAlarms").stream().sorted().toList());
        assertEquals(List.of(outer), describe(json, Map.of("ParentsOfAlarmName", inner), "CompositeAlarms"));
        assertEquals(List.of(), describe(json, Map.of("ChildrenOfAlarmName", metric), "MetricAlarms"));

        assertError(json, call(json, "DescribeAlarms", Map.of("ChildrenOfAlarmName", outer,
                "AlarmNames", List.of(metric))), "ChildrenOfAlarmName and ParentsOfAlarmName cannot be combined "
                + "with each other or with any parameter except MaxRecords and NextToken.");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stateValueAndActionPrefixFilterBothKinds(boolean json) {
        String quiet = unique("quiet");
        String loud = unique("loud");
        String watcher = unique("watcher");
        putMetricAlarm(json, quiet);
        putMetricAlarm(json, loud);
        String topic = "arn:aws:sns:us-east-1:000000000000:" + unique("topic");
        call(json, "PutCompositeAlarm", Map.of("AlarmName", watcher, "AlarmRule", "ALARM(" + loud + ")",
                "ActionsEnabled", false, "OKActions", List.of(topic))).then().statusCode(200);
        cleanup.register(() -> call(json, "DeleteAlarms", Map.of("AlarmNames", List.of(watcher))));
        call(json, "SetAlarmState", Map.of("AlarmName", loud, "StateValue", "ALARM", "StateReason", "test"))
                .then().statusCode(200);

        List<String> mine = List.of(quiet, loud, watcher);
        Map<String, Object> inAlarm = Map.of("AlarmNames", mine, "StateValue", "ALARM",
                "AlarmTypes", List.of("MetricAlarm", "CompositeAlarm"));
        assertEquals(List.of(loud), describe(json, inAlarm, "MetricAlarms"));
        assertEquals(List.of(watcher), describe(json, inAlarm, "CompositeAlarms"));
        Map<String, Object> notifying = Map.of("AlarmNames", mine, "ActionPrefix", topic.substring(0, topic.length() - 3),
                "AlarmTypes", List.of("MetricAlarm", "CompositeAlarm"));
        assertEquals(List.of(), describe(json, notifying, "MetricAlarms"));
        assertEquals(List.of(watcher), describe(json, notifying, "CompositeAlarms"));
        assertError(json, call(json, "DescribeAlarms", Map.of("ParentsOfAlarmName", loud, "StateValue", "ALARM")),
                "ChildrenOfAlarmName and ParentsOfAlarmName cannot be combined with each other or with any parameter "
                        + "except MaxRecords and NextToken.");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidRulesAndUnknownChildrenStoreNothing(boolean json) {
        String name = unique("invalid");
        assertError(json, call(json, "PutCompositeAlarm", Map.of("AlarmName", name, "AlarmRule", "TRUE AND")),
                "Error in AlarmRule [Unsupported token '<EOF>' at char 8, must be: "
                        + "'(', 'NOT', AT_LEAST, TRUE or FALSE, ALARM, OK, or INSUFFICIENT_DATA]");
        String missing = unique("missing");
        assertError(json, call(json, "PutCompositeAlarm", Map.of("AlarmName", name, "AlarmRule", "OK(" + missing + ")")),
                "Could not save the composite alarm as alarms [" + ARN_PREFIX + missing
                        + "] in the alarm rule do not exist");
        assertError(json, call(json, "DescribeAlarms", Map.of("AlarmTypes", List.of("Composite"))),
                "1 validation error detected: Value '[Composite]' at 'alarmTypes' failed to satisfy constraint: "
                        + "Member must satisfy constraint: [Member must satisfy enum value set: "
                        + "[CompositeAlarm, LogAlarm, MetricAlarm]]");
        assertEquals(List.of(), describe(json, Map.of("AlarmNames", List.of(name),
                "AlarmTypes", List.of("CompositeAlarm")), "CompositeAlarms"));
    }

    private void putMetricAlarm(boolean json, String name) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("AlarmName", name);
        request.put("MetricName", "Errors");
        request.put("Namespace", "Composite/Wire");
        request.put("Statistic", "Sum");
        request.put("Period", 3600);
        request.put("EvaluationPeriods", 1);
        request.put("Threshold", 1);
        request.put("ComparisonOperator", "GreaterThanThreshold");
        request.put("TreatMissingData", "ignore");
        call(json, "PutMetricAlarm", request).then().statusCode(200);
        cleanup.register(() -> call(json, "DeleteAlarms", Map.of("AlarmNames", List.of(name))));
    }

    private void putComposite(boolean json, String name, String rule) {
        call(json, "PutCompositeAlarm", Map.of("AlarmName", name, "AlarmRule", rule)).then().statusCode(200);
        cleanup.register(() -> call(json, "DeleteAlarms", Map.of("AlarmNames", List.of(name))));
    }

    private List<String> describe(boolean json, Map<String, Object> request, String list) {
        Response response = call(json, "DescribeAlarms", request);
        response.then().statusCode(200);
        if (json) {
            return response.jsonPath().getList(list + ".AlarmName", String.class);
        }
        return response.xmlPath().getList("DescribeAlarmsResponse.DescribeAlarmsResult." + list + ".member.AlarmName",
                String.class);
    }

    private Map<String, String> onlyComposite(boolean json, String name) {
        Response response = call(json, "DescribeAlarms",
                Map.of("AlarmNames", List.of(name), "AlarmTypes", List.of("CompositeAlarm")));
        response.then().statusCode(200);
        Map<String, String> fields = new LinkedHashMap<>();
        List<String> members = List.of("AlarmName", "AlarmArn", "AlarmRule", "AlarmDescription", "ActionsEnabled",
                "StateValue", "StateReason", "StateReasonData", "StateTransitionedTimestamp",
                "AlarmConfigurationUpdatedTimestamp");
        if (json) {
            JsonPath body = response.jsonPath();
            assertEquals(1, body.getList("CompositeAlarms").size());
            members.forEach(member -> fields.put(member, body.getString("CompositeAlarms[0]." + member)));
        } else {
            XmlPath body = response.xmlPath();
            String path = "DescribeAlarmsResponse.DescribeAlarmsResult.CompositeAlarms.member";
            assertEquals(1, body.getList(path).size());
            members.forEach(member -> {
                String value = body.getString(path + "." + member);
                fields.put(member, value == null || value.isEmpty() ? null : value);
            });
        }
        return fields;
    }

    private static void assertError(boolean json, Response response, String message) {
        response.then().statusCode(400);
        if (json) {
            assertEquals("ValidationError", response.jsonPath().getString("__type"));
            assertEquals(message, response.jsonPath().getString("message"));
        } else {
            assertEquals("ValidationError", response.xmlPath().getString("ErrorResponse.Error.Code"));
            assertEquals(message, response.xmlPath().getString("ErrorResponse.Error.Message"));
        }
    }

    private static Response call(boolean json, String operation, Map<String, ?> request) {
        if (json) {
            return given().contentType("application/x-amz-json-1.0").header("Authorization", SCOPE)
                    .header("X-Amz-Target", "GraniteServiceVersion20100801." + operation)
                    .body(MAPPER.valueToTree(request).toString()).post("/");
        }
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("Action", operation);
        form.put("Version", "2010-08-01");
        request.forEach((key, value) -> {
            if (value instanceof List<?> values) {
                for (int index = 0; index < values.size(); index++) {
                    if (values.get(index) instanceof Map<?, ?> tag) {
                        form.put(key + ".member." + (index + 1) + ".Key", tag.get("Key"));
                        form.put(key + ".member." + (index + 1) + ".Value", tag.get("Value"));
                    } else {
                        form.put(key + ".member." + (index + 1), values.get(index));
                    }
                }
            } else {
                form.put(key, value);
            }
        });
        return given().contentType("application/x-www-form-urlencoded").header("Authorization", SCOPE)
                .formParams(form).post("/");
    }

    private static String unique(String label) {
        return "composite-wire-" + label + "-" + Long.toString(System.nanoTime(), 36);
    }
}
