package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * An {@code AWS::ElasticLoadBalancingV2::TargetGroup} without {@code Port}, through the whole engine.
 * The group is created by CreateTargetGroup, so a group for instances or IP addresses without a port
 * fails the stack with that call's ValidationError and the stack rolls back. A group for a Lambda
 * function needs no port.
 */
@QuarkusTest
class CloudFormationElbV2TargetGroupPortIntegrationTest {

    private static final String REGION = "us-east-1";

    @ParameterizedTest
    @ValueSource(strings = {"instance", "ip"})
    void aTargetGroupWithoutAPortFailsTheStack(String targetType) {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "tg-no-port-" + suffix;
        createStack(stackName, """
            {"Resources": {"Tg": {"Type": "AWS::ElasticLoadBalancingV2::TargetGroup",
              "Properties": {"Name": "no-port-%s", "Protocol": "HTTP", "VpcId": "%s", "TargetType": "%s"}}}}
            """.formatted(suffix, Ec2Service.defaultVpcId(REGION), targetType));
        try {
            CfnStackWaits.awaitStatus(stackName, "ROLLBACK_COMPLETE");
            assertThat(CfnStackWaits.failedResourceReason(stackName), containsString("A port must be specified"));
            given().formParam("Action", "DescribeTargetGroups").formParam("Version", "2015-12-01")
                .formParam("Names.member.1", "no-port-" + suffix)
            .when().post("/").then().statusCode(400).body(containsString("TargetGroupNotFound"));
        } finally {
            deleteStack(stackName);
        }
    }

    @Test
    void aLambdaTargetGroupNeedsNoPort() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "tg-lambda-" + suffix;
        createStack(stackName, """
            {"Resources": {"Tg": {"Type": "AWS::ElasticLoadBalancingV2::TargetGroup",
              "Properties": {"Name": "fn-%s", "TargetType": "lambda"}}}}
            """.formatted(suffix));
        try {
            CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");
            given().formParam("Action", "DescribeTargetGroups").formParam("Version", "2015-12-01")
                .formParam("Names.member.1", "fn-" + suffix)
            .when().post("/").then().statusCode(200)
                .body("DescribeTargetGroupsResponse.DescribeTargetGroupsResult.TargetGroups.member.TargetType",
                        equalTo("lambda"));
        } finally {
            deleteStack(stackName);
        }
    }

    private static void createStack(String stackName, String body) {
        given().formParam("Action", "CreateStack").formParam("Version", "2010-05-15")
            .formParam("StackName", stackName).formParam("TemplateBody", body)
        .when().post("/").then().statusCode(200);
    }

    private static void deleteStack(String stackName) {
        given().formParam("Action", "DeleteStack").formParam("Version", "2010-05-15")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200);
        CfnStackWaits.awaitStackDeleted(stackName);
    }
}
