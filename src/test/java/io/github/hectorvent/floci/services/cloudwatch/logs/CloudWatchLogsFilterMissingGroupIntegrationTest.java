package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class CloudWatchLogsFilterMissingGroupIntegrationTest {

    private final String group = "/floci/filter-missing/" + UUID.randomUUID();

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"logGroupName", "logGroupIdentifier"})
    void filteringADeletedGroupIsResourceNotFound(String selector) {
        logs("CreateLogGroup", Map.of("logGroupName", group)).then().statusCode(200);
        logs("CreateLogStream", Map.of("logGroupName", group, "logStreamName", "stream")).then().statusCode(200);
        logs("PutLogEvents", Map.of("logGroupName", group, "logStreamName", "stream",
                "logEvents", List.of(Map.of("timestamp", System.currentTimeMillis(), "message", "gone"))))
                .then().statusCode(200);
        String arn = logs("DescribeLogGroups", Map.of("logGroupNamePrefix", group))
                .then().statusCode(200).extract().path("logGroups[0].logGroupArn");
        logs("DeleteLogGroup", Map.of("logGroupName", group)).then().statusCode(200);

        logs("FilterLogEvents", Map.of(selector, "logGroupName".equals(selector) ? group : arn))
                .then().statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("message", equalTo("The specified log group does not exist."));
    }

    @Test
    void aMissingGroupIsReportedBeforeAnInvalidPattern() {
        logs("FilterLogEvents", Map.of("logGroupName", group, "filterPattern", "["))
                .then().statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    private Response logs(String action, Map<String, ?> body) {
        return given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "Logs_20140328." + action).body(body).post("/");
    }
}
