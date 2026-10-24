package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;

/**
 * Target ports through the Query protocol. A target group's port is the one its targets receive
 * traffic on unless a registration overrides it, so every target type but Lambda needs one, and a
 * target registered without a port is the target at that port: the same target as one registered
 * with it, deregistered with or without it, and reported with it.
 */
@QuarkusTest
class ElbV2TargetPortIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261022/us-east-1/elasticloadbalancing/aws4_request";
    private static final String TARGETS =
            "DescribeTargetHealthResponse.DescribeTargetHealthResult.TargetHealthDescriptions.member";

    /** Without a TargetType the group is an instance group, which needs a port as much as an ip group. */
    @ParameterizedTest
    @CsvSource(nullValues = "NONE", value = {"NONE", "instance", "ip"})
    void createTargetGroupWithoutAPortIsAValidationError(String targetType) {
        String name = uniqueName("no-port");
        List<String> params = new ArrayList<>(List.of("Name", name, "Protocol", "HTTP",
                "VpcId", Ec2Service.defaultVpcId("us-east-1")));
        if (targetType != null) {
            params.addAll(List.of("TargetType", targetType));
        }

        elb("CreateTargetGroup", params.toArray(String[]::new))
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message", equalTo("A port must be specified"));
        elb("DescribeTargetGroups", "Names.member.1", name)
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("TargetGroupNotFound"));
    }

    /** Either form may come first; a port-less registration after the other still reports the port. */
    @ParameterizedTest
    @CsvSource(nullValues = "NONE", value = {"NONE,8080", "8080,NONE"})
    void aTargetRegisteredWithAndWithoutItsGroupsPortIsOneTarget(String firstPort, String secondPort) {
        String targetGroup = targetGroup(uniqueName("one-target"), 8080);
        try {
            register(targetGroup, "i-0a1b2c3d4e5f60001", firstPort);
            register(targetGroup, "i-0a1b2c3d4e5f60001", secondPort);
            register(targetGroup, "i-0a1b2c3d4e5f60001", "9090");

            elb("DescribeTargetHealth", "TargetGroupArn", targetGroup)
                    .statusCode(200)
                    .body(TARGETS + ".size()", equalTo(2))
                    .body(TARGETS + ".Target.Id", everyItem(equalTo("i-0a1b2c3d4e5f60001")))
                    .body(TARGETS + ".Target.Port", containsInAnyOrder("8080", "9090"))
                    .body(TARGETS + ".HealthCheckPort", containsInAnyOrder("8080", "9090"));
        } finally {
            elb("DeleteTargetGroup", "TargetGroupArn", targetGroup).statusCode(200);
        }
    }

    @Test
    void deregisterTargetsWithoutAPortRemovesOnlyTheTargetAtItsGroupsPort() {
        String targetGroup = targetGroup(uniqueName("deregister"), 8080);
        try {
            register(targetGroup, "i-0a1b2c3d4e5f60002", "8080");
            register(targetGroup, "i-0a1b2c3d4e5f60002", "9090");
            register(targetGroup, "i-0a1b2c3d4e5f60003", null);

            deregister(targetGroup, "i-0a1b2c3d4e5f60002", null);
            deregister(targetGroup, "i-0a1b2c3d4e5f60003", "8080");

            elb("DescribeTargetHealth", "TargetGroupArn", targetGroup)
                    .statusCode(200)
                    .body(TARGETS + ".size()", equalTo(1))
                    .body(TARGETS + ".Target.Id", equalTo("i-0a1b2c3d4e5f60002"))
                    .body(TARGETS + ".Target.Port", equalTo("9090"));
        } finally {
            elb("DeleteTargetGroup", "TargetGroupArn", targetGroup).statusCode(200);
        }
    }

    private static String targetGroup(String name, int port) {
        return elb("CreateTargetGroup", "Name", name, "Protocol", "HTTP", "Port", String.valueOf(port),
                "VpcId", Ec2Service.defaultVpcId("us-east-1"), "TargetType", "instance")
                .statusCode(200)
                .extract()
                .path("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");
    }

    private static void register(String targetGroup, String id, String port) {
        targets("RegisterTargets", targetGroup, id, port);
    }

    private static void deregister(String targetGroup, String id, String port) {
        targets("DeregisterTargets", targetGroup, id, port);
    }

    private static void targets(String action, String targetGroup, String id, String port) {
        List<String> params = new ArrayList<>(List.of("TargetGroupArn", targetGroup, "Targets.member.1.Id", id));
        if (port != null) {
            params.addAll(List.of("Targets.member.1.Port", port));
        }
        elb(action, params.toArray(String[]::new)).statusCode(200);
    }

    private static ValidatableResponse elb(String action, String... values) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("Action", action);
        for (int i = 0; i < values.length; i += 2) {
            parameters.put(values[i], values[i + 1]);
        }
        return given().header("Authorization", AUTH).formParams(parameters).when().post("/").then();
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + Long.toString(System.nanoTime(), 36);
    }
}
