package io.github.hectorvent.floci.services.autoscaling;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An Auto Scaling group launching from a launch configuration creates the volumes the
 * configuration's block device mappings describe, as one launching from a template does.
 */
@QuarkusTest
class AutoScalingLaunchConfigurationBlockDevicesIntegrationTest {

    /** An account of its own, so the group's instance is the only one in it. */
    private static final String ACCOUNT = "724700000001";
    private static final String GROUP = "DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult"
            + ".AutoScalingGroups.member";
    private static final String INSTANCE = "DescribeInstancesResponse.reservationSet.item.instancesSet.item";

    @Inject
    AutoScalingReconciler reconciler;

    @Test
    void aGroupLaunchesWithItsLaunchConfigurationsBlockDeviceMappings() {
        String name = "lc-block-devices";
        // The AMI maps a data device the configuration suppresses, so a NoDevice the launch did
        // not receive would show up as a /dev/sdf volume.
        String image = call("ec2", "RegisterImage", "Name", name,
                "RootDeviceName", "/dev/xvda",
                "BlockDeviceMapping.1.DeviceName", "/dev/xvda",
                "BlockDeviceMapping.1.Ebs.VolumeSize", "8",
                "BlockDeviceMapping.1.Ebs.VolumeType", "gp2",
                "BlockDeviceMapping.2.DeviceName", "/dev/sdf",
                "BlockDeviceMapping.2.Ebs.VolumeSize", "10")
                .getString("RegisterImageResponse.imageId");
        call("autoscaling", "CreateLaunchConfiguration",
                "LaunchConfigurationName", name,
                "ImageId", image,
                "InstanceType", "t3.micro",
                "BlockDeviceMappings.member.1.DeviceName", "/dev/xvda",
                "BlockDeviceMappings.member.1.Ebs.VolumeSize", "30",
                "BlockDeviceMappings.member.1.Ebs.VolumeType", "io1",
                "BlockDeviceMappings.member.1.Ebs.Iops", "1500",
                "BlockDeviceMappings.member.2.DeviceName", "/dev/sdf",
                "BlockDeviceMappings.member.2.NoDevice", "true",
                "BlockDeviceMappings.member.3.DeviceName", "/dev/sdg",
                "BlockDeviceMappings.member.3.Ebs.VolumeSize", "12",
                "BlockDeviceMappings.member.3.Ebs.VolumeType", "gp3",
                "BlockDeviceMappings.member.3.Ebs.DeleteOnTermination", "false");
        try {
            call("autoscaling", "CreateAutoScalingGroup",
                    "AutoScalingGroupName", name,
                    "LaunchConfigurationName", name,
                    "MinSize", "1", "MaxSize", "1", "DesiredCapacity", "1",
                    "AvailabilityZones.member.1", "us-east-1a");
            try {
                reconciler.reconcileAll();
                String instance = call("autoscaling", "DescribeAutoScalingGroups",
                        "AutoScalingGroupNames.member.1", name).getString(GROUP + ".Instances.member.InstanceId");

                XmlPath described = call("ec2", "DescribeInstances", "InstanceId.1", instance);
                assertEquals(List.of("/dev/xvda", "/dev/sdg"),
                        described.getList(INSTANCE + ".blockDeviceMapping.item.deviceName", String.class));
                assertEquals(List.of("true", "false"),
                        described.getList(INSTANCE + ".blockDeviceMapping.item.ebs.deleteOnTermination", String.class));
                XmlPath root = call("ec2", "DescribeVolumes", "VolumeId.1",
                        described.getString(INSTANCE + ".blockDeviceMapping.item[0].ebs.volumeId"));
                assertEquals("30", root.getString("DescribeVolumesResponse.volumeSet.item.size"));
                assertEquals("io1", root.getString("DescribeVolumesResponse.volumeSet.item.volumeType"));
                assertEquals("1500", root.getString("DescribeVolumesResponse.volumeSet.item.iops"));
                XmlPath data = call("ec2", "DescribeVolumes", "VolumeId.1",
                        described.getString(INSTANCE + ".blockDeviceMapping.item[1].ebs.volumeId"));
                assertEquals("12", data.getString("DescribeVolumesResponse.volumeSet.item.size"));
                assertEquals("gp3", data.getString("DescribeVolumesResponse.volumeSet.item.volumeType"));
            } finally {
                call("autoscaling", "DeleteAutoScalingGroup", "AutoScalingGroupName", name, "ForceDelete", "true");
            }
        } finally {
            call("autoscaling", "DeleteLaunchConfiguration", "LaunchConfigurationName", name);
        }
    }

    private static XmlPath call(String service, String action, String... params) {
        RequestSpecification request = given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                        + "/20261022/us-east-1/" + service + "/aws4_request, SignedHeaders=host, Signature=abc")
                .formParam("Action", action);
        for (int i = 0; i < params.length; i += 2) {
            request = request.formParam(params[i], params[i + 1]);
        }
        return request.post("/").then().statusCode(200).extract().xmlPath();
    }
}
