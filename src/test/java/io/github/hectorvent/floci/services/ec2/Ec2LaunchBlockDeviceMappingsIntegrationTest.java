package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RunInstances creates one EBS volume for each device of the AMI's block device mapping with the
 * launch's own entries over it, field by field, and DescribeInstances lists the volumes an
 * instance has. Termination then keeps or deletes each one as its DeleteOnTermination says.
 *
 * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/instance-block-device-mapping.html">Add block device mappings to Amazon EC2 instance</a>
 */
@QuarkusTest
class Ec2LaunchBlockDeviceMappingsIntegrationTest {

    /** Each test works in an account of its own, so no other test's volumes show up in its answers. */
    private static final AtomicLong ACCOUNTS = new AtomicLong(724600000000L);
    private static final String INSTANCE = "DescribeInstancesResponse.reservationSet.item.instancesSet.item";
    private static final String SNAPSHOT = "snap-0a1b2c3d4e5f60718";

    @Test
    void aLaunchCreatesTheAmiDevicesWithItsOwnEntriesOverThem() {
        String account = account();
        String image = registerImage(account);

        String instance = launch(account, image,
                "BlockDeviceMapping.1.DeviceName", "/dev/sda1",
                "BlockDeviceMapping.1.Ebs.VolumeSize", "20",
                "BlockDeviceMapping.1.Ebs.DeleteOnTermination", "false",
                "BlockDeviceMapping.2.DeviceName", "/dev/sdg",
                "BlockDeviceMapping.2.Ebs.VolumeSize", "5",
                "BlockDeviceMapping.2.Ebs.VolumeType", "gp3");

        XmlPath described = ec2(account, "DescribeInstances", "InstanceId.1", instance);
        assertEquals("/dev/sda1", described.getString(INSTANCE + ".rootDeviceName"));
        assertEquals(List.of("/dev/sda1", "/dev/sdf", "/dev/sdg"),
                described.getList(INSTANCE + ".blockDeviceMapping.item.deviceName", String.class));
        assertEquals(List.of("false", "false", "true"),
                described.getList(INSTANCE + ".blockDeviceMapping.item.ebs.deleteOnTermination", String.class));
        assertEquals(List.of("attached", "attached", "attached"),
                described.getList(INSTANCE + ".blockDeviceMapping.item.ebs.status", String.class));

        // The resized root keeps the AMI's snapshot and gp2, where a root mapping nothing would be
        // gp3; the AMI's data device comes as it maps it.
        volume(account, deviceVolume(account, instance, "/dev/sda1"))
                .body("DescribeVolumesResponse.volumeSet.item.size", equalTo("20"))
                .body("DescribeVolumesResponse.volumeSet.item.snapshotId", equalTo(SNAPSHOT))
                .body("DescribeVolumesResponse.volumeSet.item.volumeType", equalTo("gp2"));
        volume(account, deviceVolume(account, instance, "/dev/sdf"))
                .body("DescribeVolumesResponse.volumeSet.item.size", equalTo("125"))
                .body("DescribeVolumesResponse.volumeSet.item.volumeType", equalTo("st1"))
                .body("DescribeVolumesResponse.volumeSet.item.attachmentSet.item.deleteOnTermination",
                        equalTo("false"));
        volume(account, deviceVolume(account, instance, "/dev/sdg"))
                .body("DescribeVolumesResponse.volumeSet.item.size", equalTo("5"))
                .body("DescribeVolumesResponse.volumeSet.item.volumeType", equalTo("gp3"));
        assertEquals(described.getString(INSTANCE + ".blockDeviceMapping.item[0].ebs.volumeId"),
                deviceVolume(account, instance, "/dev/sda1"));

        // The launch's entries change only this instance, never the AMI it came from.
        XmlPath amiAfter = ec2(account, "DescribeImages", "ImageId.1", image);
        assertEquals(List.of("16", "125"),
                amiAfter.getList("DescribeImagesResponse.imagesSet.item.blockDeviceMapping.item.ebs.volumeSize",
                        String.class));
    }

    @Test
    void terminationKeepsOrDeletesEachVolumeAsItsDeleteOnTerminationSays() {
        String account = account();
        String image = registerImage(account);
        String instance = launch(account, image,
                "BlockDeviceMapping.1.DeviceName", "/dev/sda1",
                "BlockDeviceMapping.1.Ebs.DeleteOnTermination", "false",
                "BlockDeviceMapping.2.DeviceName", "/dev/sdg",
                "BlockDeviceMapping.2.Ebs.VolumeSize", "5");
        String root = deviceVolume(account, instance, "/dev/sda1");
        String data = deviceVolume(account, instance, "/dev/sdf");

        ec2(account, "TerminateInstances", "InstanceId.1", instance);

        // The root and /dev/sdf asked to be kept, /dev/sdg took the launch default and is deleted.
        assertEquals(List.of(root, data).stream().sorted().toList(), volumeIds(account));
        for (String kept : List.of(root, data)) {
            volume(account, kept).body("DescribeVolumesResponse.volumeSet.item.status", equalTo("available"));
            assertTrue(ec2(account, "DescribeVolumes", "VolumeId.1", kept)
                    .getList("DescribeVolumesResponse.volumeSet.item.attachmentSet.item").isEmpty());
        }
        assertTrue(ec2(account, "DescribeInstances", "InstanceId.1", instance)
                .getList(INSTANCE + ".blockDeviceMapping.item").isEmpty());
    }

    @Test
    void noDeviceKeepsAnAmiDeviceFromBeingCreatedButNotTheRoot() {
        String account = account();
        String image = registerImage(account);

        String instance = launch(account, image,
                "BlockDeviceMapping.1.DeviceName", "/dev/sdf",
                "BlockDeviceMapping.1.NoDevice", "",
                "BlockDeviceMapping.2.DeviceName", "/dev/sda1",
                "BlockDeviceMapping.2.NoDevice", "");

        assertEquals(List.of("/dev/sda1"), ec2(account, "DescribeInstances", "InstanceId.1", instance)
                .getList(INSTANCE + ".blockDeviceMapping.item.deviceName", String.class));
        assertEquals(1, volumeIds(account).size());
        // The root stays, as the AMI maps it.
        volume(account, deviceVolume(account, instance, "/dev/sda1"))
                .body("DescribeVolumesResponse.volumeSet.item.size", equalTo("16"))
                .body("DescribeVolumesResponse.volumeSet.item.snapshotId", equalTo(SNAPSHOT));
    }

    @Test
    void aVolumeFromASnapshotTakesTheSnapshotsSizeWhenItsEntryNamesNone() {
        String account = account();
        String image = registerImage(account);

        String instance = launch(account, image,
                "BlockDeviceMapping.1.DeviceName", "/dev/sdh",
                "BlockDeviceMapping.1.Ebs.SnapshotId", SNAPSHOT);

        volume(account, deviceVolume(account, instance, "/dev/sdh"))
                .body("DescribeVolumesResponse.volumeSet.item.size", equalTo("16"))
                .body("DescribeVolumesResponse.volumeSet.item.snapshotId", equalTo(SNAPSHOT))
                .body("DescribeVolumesResponse.volumeSet.item.volumeType", equalTo("gp2"));
    }

    @Test
    void aLaunchTemplateDeviceKeepsItsSettingsUnderARequestEntryThatChangesItsType() {
        String account = account();
        String template = ec2(account, "CreateLaunchTemplate",
                "LaunchTemplateName", "launch-volumes-" + account,
                "LaunchTemplateData.ImageId", "ami-0abcdef1234567890",
                "LaunchTemplateData.BlockDeviceMapping.1.DeviceName", "/dev/sdf",
                "LaunchTemplateData.BlockDeviceMapping.1.Ebs.VolumeSize", "12",
                "LaunchTemplateData.BlockDeviceMapping.1.Ebs.VolumeType", "gp3",
                "LaunchTemplateData.BlockDeviceMapping.1.Ebs.Iops", "4000",
                "LaunchTemplateData.BlockDeviceMapping.1.Ebs.Throughput", "250",
                "LaunchTemplateData.BlockDeviceMapping.1.Ebs.Encrypted", "true")
                .getString("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");

        String asTemplated = launch(account, null, "LaunchTemplate.LaunchTemplateId", template);
        volume(account, deviceVolume(account, asTemplated, "/dev/sdf"))
                .body("DescribeVolumesResponse.volumeSet.item.size", equalTo("12"))
                .body("DescribeVolumesResponse.volumeSet.item.volumeType", equalTo("gp3"))
                .body("DescribeVolumesResponse.volumeSet.item.iops", equalTo("4000"))
                .body("DescribeVolumesResponse.volumeSet.item.throughput", equalTo("250"))
                .body("DescribeVolumesResponse.volumeSet.item.encrypted", equalTo("true"));

        // The new type takes neither of the old one's performance settings; the rest still apply.
        String retyped = launch(account, null, "LaunchTemplate.LaunchTemplateId", template,
                "BlockDeviceMapping.1.DeviceName", "/dev/sdf",
                "BlockDeviceMapping.1.Ebs.VolumeType", "gp2");
        XmlPath gp2 = ec2(account, "DescribeVolumes", "VolumeId.1", deviceVolume(account, retyped, "/dev/sdf"));
        assertEquals("gp2", gp2.getString("DescribeVolumesResponse.volumeSet.item.volumeType"));
        assertEquals("12", gp2.getString("DescribeVolumesResponse.volumeSet.item.size"));
        assertEquals("true", gp2.getString("DescribeVolumesResponse.volumeSet.item.encrypted"));
        assertEquals("", gp2.getString("DescribeVolumesResponse.volumeSet.item.iops"));
        assertEquals("", gp2.getString("DescribeVolumesResponse.volumeSet.item.throughput"));
    }

    @Test
    void aVolumeAttachedAfterLaunchIsPartOfTheInstancesBlockDeviceMapping() {
        String account = account();
        String instance = launch(account, "ami-0abcdef1234567890");
        String zone = ec2(account, "DescribeInstances", "InstanceId.1", instance)
                .getString(INSTANCE + ".placement.availabilityZone");
        String attached = ec2(account, "CreateVolume", "Size", "4", "AvailabilityZone", zone)
                .getString("CreateVolumeResponse.volumeId");

        ec2(account, "AttachVolume", "VolumeId", attached, "InstanceId", instance, "Device", "/dev/sdj");

        XmlPath described = ec2(account, "DescribeInstances", "InstanceId.1", instance);
        assertEquals(List.of("/dev/xvda", "/dev/sdj"),
                described.getList(INSTANCE + ".blockDeviceMapping.item.deviceName", String.class));
        assertEquals(attached, described.getString(INSTANCE + ".blockDeviceMapping.item[1].ebs.volumeId"));
        assertEquals("false", described.getString(INSTANCE + ".blockDeviceMapping.item[1].ebs.deleteOnTermination"));
    }

    @Test
    void modifyInstanceAttributeSetsDeleteOnTerminationForTheDevicesItNames() {
        String account = account();
        String instance = launch(account, "ami-0abcdef1234567890",
                "BlockDeviceMapping.1.DeviceName", "/dev/sdg",
                "BlockDeviceMapping.1.Ebs.VolumeSize", "5");
        String zone = ec2(account, "DescribeInstances", "InstanceId.1", instance)
                .getString(INSTANCE + ".placement.availabilityZone");
        String attached = ec2(account, "CreateVolume", "Size", "4", "AvailabilityZone", zone)
                .getString("CreateVolumeResponse.volumeId");
        ec2(account, "AttachVolume", "VolumeId", attached, "InstanceId", instance, "Device", "/dev/sdj");
        String launched = deviceVolume(account, instance, "/dev/sdg");

        // An entry that leaves DeleteOnTermination unset sets it to true, as AWS documents.
        ec2(account, "ModifyInstanceAttribute", "InstanceId", instance,
                "BlockDeviceMapping.1.DeviceName", "/dev/sdg",
                "BlockDeviceMapping.1.Ebs.DeleteOnTermination", "false",
                "BlockDeviceMapping.2.DeviceName", "/dev/sdj");

        assertEquals(List.of("true", "false", "true"), ec2(account, "DescribeInstances", "InstanceId.1", instance)
                .getList(INSTANCE + ".blockDeviceMapping.item.ebs.deleteOnTermination", String.class));
        ec2(account, "TerminateInstances", "InstanceId.1", instance);
        assertEquals(List.of(launched), volumeIds(account));
    }

    @Test
    void modifyInstanceAttributeRefusesADeviceTheInstanceHasNoVolumeAt() {
        String account = account();
        String instance = launch(account, "ami-0abcdef1234567890");

        request(account, "ModifyInstanceAttribute", "InstanceId", instance,
                "BlockDeviceMapping.1.DeviceName", "/dev/xvda",
                "BlockDeviceMapping.1.Ebs.DeleteOnTermination", "false",
                "BlockDeviceMapping.2.DeviceName", "/dev/sda1",
                "BlockDeviceMapping.2.Ebs.DeleteOnTermination", "false")
                .post("/").then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidInstanceAttributeValue"))
                .body("Response.Errors.Error.Message", equalTo("No device is currently mapped at /dev/sda1"));

        // Nothing was changed, the device the instance does have included.
        assertEquals("true", ec2(account, "DescribeInstances", "InstanceId.1", instance)
                .getString(INSTANCE + ".blockDeviceMapping.item[0].ebs.deleteOnTermination"));
        request(account, "ModifyInstanceAttribute", "InstanceId", "i-0123456789abcdef0",
                "BlockDeviceMapping.1.DeviceName", "/dev/xvda",
                "BlockDeviceMapping.1.Ebs.DeleteOnTermination", "false")
                .post("/").then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidInstanceID.NotFound"));
    }

    @Test
    void modifyInstanceAttributeRefusesDevicesNamedWithAnotherAttribute() {
        String account = account();
        String instance = launch(account, "ami-0abcdef1234567890");
        XmlPath described = ec2(account, "DescribeInstances", "InstanceId.1", instance);
        List<String> groups = described.getList(INSTANCE + ".groupSet.item.groupId", String.class);
        String other = ec2(account, "CreateSecurityGroup", "GroupName", "other-" + account,
                "GroupDescription", "other", "VpcId", described.getString(INSTANCE + ".vpcId"))
                .getString("CreateSecurityGroupResponse.groupId");

        // AWS takes one attribute per call, so neither half is applied, whichever would fail.
        for (String device : List.of("/dev/xvda", "/dev/sdz")) {
            request(account, "ModifyInstanceAttribute", "InstanceId", instance,
                    "GroupId.1", other,
                    "BlockDeviceMapping.1.DeviceName", device,
                    "BlockDeviceMapping.1.Ebs.DeleteOnTermination", "false")
                    .post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("InvalidParameterCombination"))
                    .body("Response.Errors.Error.Message",
                            equalTo("Fields for multiple attribute types specified: blockDeviceMapping,groupSet"));
        }

        // Attributes Floci does not change count as well, the generic Attribute form included.
        request(account, "ModifyInstanceAttribute", "InstanceId", instance,
                "DisableApiTermination.Value", "true",
                "BlockDeviceMapping.1.DeviceName", "/dev/xvda",
                "BlockDeviceMapping.1.Ebs.DeleteOnTermination", "false")
                .post("/").then().statusCode(400)
                .body("Response.Errors.Error.Message",
                        equalTo("Fields for multiple attribute types specified: disableApiTermination,blockDeviceMapping"));
        request(account, "ModifyInstanceAttribute", "InstanceId", instance,
                "Attribute", "kernel", "Value", "aki-0123456789abcdef0",
                "BlockDeviceMapping.1.DeviceName", "/dev/xvda",
                "BlockDeviceMapping.1.Ebs.DeleteOnTermination", "false")
                .post("/").then().statusCode(400)
                .body("Response.Errors.Error.Message",
                        equalTo("Fields for multiple attribute types specified: kernel,blockDeviceMapping"));

        XmlPath after = ec2(account, "DescribeInstances", "InstanceId.1", instance);
        assertEquals(groups, after.getList(INSTANCE + ".groupSet.item.groupId", String.class));
        assertEquals("true", after.getString(INSTANCE + ".blockDeviceMapping.item[0].ebs.deleteOnTermination"));
    }

    @Test
    void describeInstanceAttributeListsTheBlockDeviceMapping() {
        String account = account();
        String instance = launch(account, "ami-0abcdef1234567890",
                "BlockDeviceMapping.1.DeviceName", "/dev/sdg",
                "BlockDeviceMapping.1.Ebs.VolumeSize", "5",
                "BlockDeviceMapping.1.Ebs.DeleteOnTermination", "false");

        XmlPath attribute = ec2(account, "DescribeInstanceAttribute", "InstanceId", instance,
                "Attribute", "blockDeviceMapping");

        String item = "DescribeInstanceAttributeResponse.blockDeviceMapping.item";
        assertEquals(List.of("/dev/xvda", "/dev/sdg"), attribute.getList(item + ".deviceName", String.class));
        assertEquals(List.of("true", "false"), attribute.getList(item + ".ebs.deleteOnTermination", String.class));
        assertEquals(List.of("attached", "attached"), attribute.getList(item + ".ebs.status", String.class));
        assertEquals(deviceVolume(account, instance, "/dev/sdg"), attribute.getString(item + "[1].ebs.volumeId"));
    }

    @Test
    void createImageRecordsAnAliasedRootAsTheRootAndEachDeviceOnce() {
        String account = account();
        String image = ec2(account, "RegisterImage", "Name", "aliased-capture-" + account,
                "RootDeviceName", "/dev/xvda",
                "BlockDeviceMapping.1.DeviceName", "xvda",
                "BlockDeviceMapping.1.Ebs.VolumeSize", "30",
                "BlockDeviceMapping.1.Ebs.VolumeType", "gp2",
                "BlockDeviceMapping.2.DeviceName", "/dev/sdf",
                "BlockDeviceMapping.2.Ebs.VolumeSize", "10")
                .getString("RegisterImageResponse.imageId");
        String instance = launch(account, image,
                "BlockDeviceMapping.1.DeviceName", "/dev/xvda",
                "BlockDeviceMapping.1.Ebs.VolumeType", "io1",
                "BlockDeviceMapping.1.Ebs.Iops", "3000",
                "BlockDeviceMapping.2.DeviceName", "/dev/sdf",
                "BlockDeviceMapping.2.NoDevice", "");
        String zone = ec2(account, "DescribeInstances", "InstanceId.1", instance)
                .getString(INSTANCE + ".placement.availabilityZone");
        String attached = ec2(account, "CreateVolume", "Size", "4", "AvailabilityZone", zone)
                .getString("CreateVolumeResponse.volumeId");
        ec2(account, "AttachVolume", "VolumeId", attached, "InstanceId", instance, "Device", "sdf");

        String captured = ec2(account, "CreateImage", "InstanceId", instance, "Name", "capture-" + account)
                .getString("CreateImageResponse.imageId");

        // The root entry the AMI spells "xvda" is recorded from the root volume, io1, with a
        // snapshot of its own. "sdf" is the device the AMI maps as "/dev/sdf", recorded once and as
        // the instance has it: the 4 GiB volume attached there, kept on termination, not the AMI's
        // 10 GiB.
        String item = "DescribeImagesResponse.imagesSet.item.blockDeviceMapping.item";
        XmlPath described = ec2(account, "DescribeImages", "ImageId.1", captured);
        assertEquals(List.of("xvda", "/dev/sdf"), described.getList(item + ".deviceName", String.class));
        assertEquals("io1", described.getString(item + "[0].ebs.volumeType"));
        assertEquals("30", described.getString(item + "[0].ebs.volumeSize"));
        assertTrue(described.getString(item + "[0].ebs.snapshotId").startsWith("snap-"));
        assertEquals("4", described.getString(item + "[1].ebs.volumeSize"));
        assertEquals("false", described.getString(item + "[1].ebs.deleteOnTermination"));
        assertTrue(described.getString(item + "[1].ebs.snapshotId").startsWith("snap-"));
    }

    @Test
    void createImageRecordsTheVolumesTheInstanceHas() {
        String account = account();
        String image = registerImage(account);
        String instance = launch(account, image,
                "BlockDeviceMapping.1.DeviceName", "/dev/sda1",
                "BlockDeviceMapping.1.Ebs.VolumeSize", "30",
                "BlockDeviceMapping.1.Ebs.DeleteOnTermination", "false");
        // The AMI's /dev/sdf volume leaves the instance before the capture.
        ec2(account, "DetachVolume", "VolumeId", deviceVolume(account, instance, "/dev/sdf"));

        String captured = ec2(account, "CreateImage", "InstanceId", instance, "Name", "volumes-" + account)
                .getString("CreateImageResponse.imageId");

        String item = "DescribeImagesResponse.imagesSet.item.blockDeviceMapping.item";
        XmlPath described = ec2(account, "DescribeImages", "ImageId.1", captured);
        assertEquals(List.of("/dev/sda1"), described.getList(item + ".deviceName", String.class));
        assertEquals("30", described.getString(item + ".ebs.volumeSize"));
        assertEquals("false", described.getString(item + ".ebs.deleteOnTermination"));
        assertEquals("gp2", described.getString(item + ".ebs.volumeType"));
        // A launch from the new AMI gets the root as the instance had it, and no /dev/sdf.
        String relaunched = launch(account, captured);
        XmlPath relaunchedDevices = ec2(account, "DescribeInstances", "InstanceId.1", relaunched);
        assertEquals(List.of("/dev/sda1"),
                relaunchedDevices.getList(INSTANCE + ".blockDeviceMapping.item.deviceName", String.class));
        assertEquals("false", relaunchedDevices.getString(INSTANCE + ".blockDeviceMapping.item.ebs.deleteOnTermination"));
        volume(account, deviceVolume(account, relaunched, "/dev/sda1"))
                .body("DescribeVolumesResponse.volumeSet.item.size", equalTo("30"));
    }

    @Test
    void createImageKeepsTheAmiRootEntryWhenTheInstanceHasNoRootVolume() {
        String account = account();
        String image = registerImage(account);
        String instance = launch(account, image);
        ec2(account, "StopInstances", "InstanceId.1", instance);
        ec2(account, "DetachVolume", "VolumeId", deviceVolume(account, instance, "/dev/sda1"), "Force", "true");

        String captured = ec2(account, "CreateImage", "InstanceId", instance, "Name", "rootless-" + account,
                "NoReboot", "true").getString("CreateImageResponse.imageId");

        // The root is the AMI's, on its 16 GiB snapshot; /dev/sdf is the volume the instance has.
        String item = "DescribeImagesResponse.imagesSet.item.blockDeviceMapping.item";
        XmlPath described = ec2(account, "DescribeImages", "ImageId.1", captured);
        assertEquals(List.of("/dev/sda1", "/dev/sdf"), described.getList(item + ".deviceName", String.class));
        assertEquals("16", described.getString(item + "[0].ebs.volumeSize"));
    }

    @Test
    void createImageRecordsTheVolumeAtTheRootDeviceAsTheRoot() {
        String account = account();
        String image = registerImage(account);
        String instance = launch(account, image);
        String zone = ec2(account, "DescribeInstances", "InstanceId.1", instance)
                .getString(INSTANCE + ".placement.availabilityZone");
        String formerRoot = deviceVolume(account, instance, "/dev/sda1");
        ec2(account, "StopInstances", "InstanceId.1", instance);
        ec2(account, "DetachVolume", "VolumeId", formerRoot, "Force", "true");
        String newRoot = ec2(account, "CreateVolume", "Size", "20", "AvailabilityZone", zone)
                .getString("CreateVolumeResponse.volumeId");
        ec2(account, "AttachVolume", "VolumeId", newRoot, "InstanceId", instance, "Device", "/dev/sda1");
        ec2(account, "AttachVolume", "VolumeId", formerRoot, "InstanceId", instance, "Device", "/dev/sdh");

        String captured = ec2(account, "CreateImage", "InstanceId", instance, "Name", "swapped-root-" + account,
                "NoReboot", "true").getString("CreateImageResponse.imageId");

        // The root is the 20 GiB volume now at /dev/sda1; the former root is a data device.
        String item = "DescribeImagesResponse.imagesSet.item.blockDeviceMapping.item";
        XmlPath described = ec2(account, "DescribeImages", "ImageId.1", captured);
        assertEquals(List.of("/dev/sda1", "/dev/sdf", "/dev/sdh"), described.getList(item + ".deviceName", String.class));
        assertEquals("20", described.getString(item + "[0].ebs.volumeSize"));
        assertEquals("16", described.getString(item + "[2].ebs.volumeSize"));
    }

    @Test
    void createImageOfAnInstanceFromAnUnknownAmiIsRootedWhereTheInstanceIs() {
        String account = account();
        String instance = launch(account, "ami-0c55b159cbfafe1f0");

        String captured = ec2(account, "CreateImage", "InstanceId", instance, "Name", "unknown-source-" + account)
                .getString("CreateImageResponse.imageId");

        XmlPath described = ec2(account, "DescribeImages", "ImageId.1", captured);
        assertEquals("/dev/xvda", described.getString("DescribeImagesResponse.imagesSet.item.rootDeviceName"));
        assertEquals(List.of("/dev/xvda"), described.getList(
                "DescribeImagesResponse.imagesSet.item.blockDeviceMapping.item.deviceName", String.class));
        // A launch from it gets that root alone.
        String relaunched = launch(account, captured);
        assertEquals(List.of("/dev/xvda"), ec2(account, "DescribeInstances", "InstanceId.1", relaunched)
                .getList(INSTANCE + ".blockDeviceMapping.item.deviceName", String.class));
        assertEquals(2, volumeIds(account).size());
    }

    @Test
    void anAmiEntryWithoutAnEbsBlockCreatesNoVolume() {
        String account = account();
        String image = ec2(account, "RegisterImage", "Name", "instance-store-" + account,
                "RootDeviceName", "/dev/xvda",
                "BlockDeviceMapping.1.DeviceName", "/dev/xvda",
                "BlockDeviceMapping.1.Ebs.VolumeSize", "8",
                "BlockDeviceMapping.2.DeviceName", "/dev/sdb",
                "BlockDeviceMapping.2.VirtualName", "ephemeral0",
                "BlockDeviceMapping.3.DeviceName", "/dev/sdc",
                "BlockDeviceMapping.3.NoDevice", "")
                .getString("RegisterImageResponse.imageId");

        String instance = launch(account, image);

        assertEquals(List.of("/dev/xvda"), ec2(account, "DescribeInstances", "InstanceId.1", instance)
                .getList(INSTANCE + ".blockDeviceMapping.item.deviceName", String.class));
        assertEquals(1, volumeIds(account).size());
        // A capture keeps the entries that describe no EBS volume.
        String captured = ec2(account, "CreateImage", "InstanceId", instance, "Name", "instance-store-capture-" + account)
                .getString("CreateImageResponse.imageId");
        assertEquals(List.of("/dev/xvda", "/dev/sdb", "/dev/sdc"), ec2(account, "DescribeImages", "ImageId.1", captured)
                .getList("DescribeImagesResponse.imagesSet.item.blockDeviceMapping.item.deviceName", String.class));
    }

    @Test
    void aDeviceNameTheRootAlsoAnswersToChangesTheRoot() {
        String account = account();

        String instance = launch(account, "ami-0abcdef1234567890",
                "BlockDeviceMapping.1.DeviceName", "xvda",
                "BlockDeviceMapping.1.Ebs.VolumeSize", "30");

        assertEquals(List.of("/dev/xvda"), ec2(account, "DescribeInstances", "InstanceId.1", instance)
                .getList(INSTANCE + ".blockDeviceMapping.item.deviceName", String.class));
        volume(account, deviceVolume(account, instance, "/dev/xvda"))
                .body("DescribeVolumesResponse.volumeSet.item.size", equalTo("30"));
    }

    @Test
    void anAmiRootMappedUnderAnotherSpellingIsTheRoot() {
        String account = account();
        String image = ec2(account, "RegisterImage", "Name", "aliased-root-" + account,
                "RootDeviceName", "/dev/xvda",
                "BlockDeviceMapping.1.DeviceName", "xvda",
                "BlockDeviceMapping.1.Ebs.VolumeSize", "30",
                "BlockDeviceMapping.1.Ebs.VolumeType", "gp2")
                .getString("RegisterImageResponse.imageId");

        String asMapped = launch(account, image);
        String resized = launch(account, image,
                "BlockDeviceMapping.1.DeviceName", "/dev/xvda",
                "BlockDeviceMapping.1.Ebs.VolumeSize", "40");

        for (String instance : List.of(asMapped, resized)) {
            assertEquals(List.of("/dev/xvda"), ec2(account, "DescribeInstances", "InstanceId.1", instance)
                    .getList(INSTANCE + ".blockDeviceMapping.item.deviceName", String.class));
        }
        assertEquals(2, volumeIds(account).size());
        volume(account, deviceVolume(account, asMapped, "/dev/xvda"))
                .body("DescribeVolumesResponse.volumeSet.item.size", equalTo("30"))
                .body("DescribeVolumesResponse.volumeSet.item.volumeType", equalTo("gp2"));
        volume(account, deviceVolume(account, resized, "/dev/xvda"))
                .body("DescribeVolumesResponse.volumeSet.item.size", equalTo("40"))
                .body("DescribeVolumesResponse.volumeSet.item.volumeType", equalTo("gp2"));
    }

    @Test
    void aRequestEntrySpellingATemplateDeviceDifferentlyChangesThatDevice() {
        String account = account();
        String template = ec2(account, "CreateLaunchTemplate",
                "LaunchTemplateName", "spelled-" + account,
                "LaunchTemplateData.ImageId", "ami-0abcdef1234567890",
                "LaunchTemplateData.BlockDeviceMapping.1.DeviceName", "/dev/xvdf",
                "LaunchTemplateData.BlockDeviceMapping.1.Ebs.VolumeSize", "10",
                "LaunchTemplateData.BlockDeviceMapping.1.Ebs.VolumeType", "io1",
                "LaunchTemplateData.BlockDeviceMapping.1.Ebs.Iops", "400")
                .getString("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");

        String instance = launch(account, null, "LaunchTemplate.LaunchTemplateId", template,
                "BlockDeviceMapping.1.DeviceName", "xvdf",
                "BlockDeviceMapping.1.Ebs.VolumeSize", "20");

        assertEquals(List.of("/dev/xvda", "/dev/xvdf"), ec2(account, "DescribeInstances", "InstanceId.1", instance)
                .getList(INSTANCE + ".blockDeviceMapping.item.deviceName", String.class));
        volume(account, deviceVolume(account, instance, "/dev/xvdf"))
                .body("DescribeVolumesResponse.volumeSet.item.size", equalTo("20"))
                .body("DescribeVolumesResponse.volumeSet.item.volumeType", equalTo("io1"))
                .body("DescribeVolumesResponse.volumeSet.item.iops", equalTo("400"));
    }

    @Test
    void anEntryNamingAnInstanceStoreVolumeTakesAnAmiDeviceOverButNotTheRoot() {
        String account = account();
        String image = registerImage(account);

        String instance = launch(account, image,
                "BlockDeviceMapping.1.DeviceName", "/dev/sdf",
                "BlockDeviceMapping.1.VirtualName", "ephemeral0",
                "BlockDeviceMapping.2.DeviceName", "/dev/sda1",
                "BlockDeviceMapping.2.VirtualName", "ephemeral1");

        assertEquals(List.of("/dev/sda1"), ec2(account, "DescribeInstances", "InstanceId.1", instance)
                .getList(INSTANCE + ".blockDeviceMapping.item.deviceName", String.class));
        assertEquals(1, volumeIds(account).size());
        volume(account, deviceVolume(account, instance, "/dev/sda1"))
                .body("DescribeVolumesResponse.volumeSet.item.size", equalTo("16"))
                .body("DescribeVolumesResponse.volumeSet.item.snapshotId", equalTo(SNAPSHOT));
    }

    @Test
    void aRequestEntryOfAnotherKindReplacesTheTemplatesForItsDevice() {
        String account = account();
        String image = registerImage(account);
        String template = ec2(account, "CreateLaunchTemplate",
                "LaunchTemplateName", "kinds-" + account,
                "LaunchTemplateData.ImageId", image,
                "LaunchTemplateData.BlockDeviceMapping.1.DeviceName", "/dev/sdb",
                "LaunchTemplateData.BlockDeviceMapping.1.VirtualName", "ephemeral0",
                "LaunchTemplateData.BlockDeviceMapping.2.DeviceName", "/dev/sdf",
                "LaunchTemplateData.BlockDeviceMapping.2.NoDevice", "")
                .getString("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");

        // The template makes /dev/sdb an instance store volume and suppresses the AMI's /dev/sdf;
        // the request asks for EBS volumes at both, and names an empty VirtualName at /dev/sdc.
        String instance = launch(account, null, "LaunchTemplate.LaunchTemplateId", template,
                "BlockDeviceMapping.1.DeviceName", "/dev/sdb",
                "BlockDeviceMapping.1.Ebs.VolumeSize", "100",
                "BlockDeviceMapping.2.DeviceName", "/dev/sdf",
                "BlockDeviceMapping.2.Ebs.VolumeSize", "150",
                "BlockDeviceMapping.3.DeviceName", "/dev/sdc",
                "BlockDeviceMapping.3.VirtualName", "",
                "BlockDeviceMapping.3.Ebs.VolumeSize", "7");

        assertEquals(List.of("/dev/sda1", "/dev/sdb", "/dev/sdc", "/dev/sdf"),
                ec2(account, "DescribeInstances", "InstanceId.1", instance)
                        .getList(INSTANCE + ".blockDeviceMapping.item.deviceName", String.class));
        volume(account, deviceVolume(account, instance, "/dev/sdb"))
                .body("DescribeVolumesResponse.volumeSet.item.size", equalTo("100"));
        // /dev/sdf still merges into the AMI's device, so it keeps the AMI's type.
        volume(account, deviceVolume(account, instance, "/dev/sdf"))
                .body("DescribeVolumesResponse.volumeSet.item.size", equalTo("150"))
                .body("DescribeVolumesResponse.volumeSet.item.volumeType", equalTo("st1"));
    }

    @Test
    void aRootEntryWithAnEmptyVirtualNameOrOverATemplatesNoDeviceStillResizesTheRoot() {
        String account = account();
        String template = ec2(account, "CreateLaunchTemplate",
                "LaunchTemplateName", "root-kinds-" + account,
                "LaunchTemplateData.ImageId", "ami-0abcdef1234567890",
                "LaunchTemplateData.BlockDeviceMapping.1.DeviceName", "/dev/xvda",
                "LaunchTemplateData.BlockDeviceMapping.1.NoDevice", "")
                .getString("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");

        String blank = launch(account, "ami-0abcdef1234567890",
                "BlockDeviceMapping.1.DeviceName", "/dev/xvda",
                "BlockDeviceMapping.1.VirtualName", "",
                "BlockDeviceMapping.1.Ebs.VolumeSize", "50");
        String overNoDevice = launch(account, null, "LaunchTemplate.LaunchTemplateId", template,
                "BlockDeviceMapping.1.DeviceName", "/dev/xvda",
                "BlockDeviceMapping.1.Ebs.VolumeSize", "40");

        volume(account, deviceVolume(account, blank, "/dev/xvda"))
                .body("DescribeVolumesResponse.volumeSet.item.size", equalTo("50"));
        volume(account, deviceVolume(account, overNoDevice, "/dev/xvda"))
                .body("DescribeVolumesResponse.volumeSet.item.size", equalTo("40"));
    }

    @Test
    void eachInstanceOfALaunchGetsVolumesOfItsOwn() {
        String account = account();

        XmlPath launched = request(account, "RunInstances", "ImageId", "ami-0abcdef1234567890",
                "InstanceType", "t3.micro", "MinCount", "2", "MaxCount", "2",
                "BlockDeviceMapping.1.DeviceName", "/dev/sdf",
                "BlockDeviceMapping.1.Ebs.VolumeSize", "4")
                .post("/").then().statusCode(200).extract().xmlPath();
        List<String> instances = launched.getList("RunInstancesResponse.instancesSet.item.instanceId", String.class);

        assertEquals(2, instances.size());
        assertEquals(4, volumeIds(account).size());
        assertNotEquals(deviceVolume(account, instances.get(0), "/dev/sdf"),
                deviceVolume(account, instances.get(1), "/dev/sdf"));
    }

    @Test
    void aDeviceNamedTwiceIsRefusedWithALaunchTemplateToo() {
        String account = account();
        String template = ec2(account, "CreateLaunchTemplate",
                "LaunchTemplateName", "twice-" + account,
                "LaunchTemplateData.ImageId", "ami-0abcdef1234567890",
                "LaunchTemplateData.BlockDeviceMapping.1.DeviceName", "/dev/sdh",
                "LaunchTemplateData.BlockDeviceMapping.1.Ebs.VolumeSize", "5")
                .getString("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");
        String twiceInTemplate = ec2(account, "CreateLaunchTemplate",
                "LaunchTemplateName", "twice-in-template-" + account,
                "LaunchTemplateData.ImageId", "ami-0abcdef1234567890",
                "LaunchTemplateData.BlockDeviceMapping.1.DeviceName", "/dev/sdh",
                "LaunchTemplateData.BlockDeviceMapping.1.Ebs.VolumeSize", "5",
                "LaunchTemplateData.BlockDeviceMapping.2.DeviceName", "sdh",
                "LaunchTemplateData.BlockDeviceMapping.2.Ebs.VolumeSize", "6")
                .getString("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");

        refused(account, null, "LaunchTemplate.LaunchTemplateId", template,
                "BlockDeviceMapping.1.DeviceName", "/dev/sdj",
                "BlockDeviceMapping.1.Ebs.VolumeSize", "5",
                "BlockDeviceMapping.2.DeviceName", "/dev/sdj",
                "BlockDeviceMapping.2.Ebs.VolumeSize", "6")
                .body("Response.Errors.Error.Code", equalTo("InvalidBlockDeviceMapping"))
                .body("Response.Errors.Error.Message", equalTo("/dev/sdj is being assigned to multiple times."));
        refused(account, null, "LaunchTemplate.LaunchTemplateId", twiceInTemplate)
                .body("Response.Errors.Error.Code", equalTo("InvalidBlockDeviceMapping"))
                .body("Response.Errors.Error.Message", equalTo("sdh is being assigned to multiple times."));
        assertNothingLaunched(account);
    }

    @Test
    void aDeviceNameAttachVolumeWouldRefuseIsRefused() {
        String account = account();

        refused(account, "ami-0abcdef1234567890",
                "BlockDeviceMapping.1.DeviceName", "/dev/sd;f",
                "BlockDeviceMapping.1.Ebs.VolumeSize", "5")
                .body("Response.Errors.Error.Code", equalTo("InvalidBlockDeviceMapping"))
                .body("Response.Errors.Error.Message", equalTo("Invalid device name /dev/sd;f"));
        assertNothingLaunched(account);
    }

    @Test
    void aRootSmallerThanItsSnapshotIsRefusedWithNothingLeftBehind() {
        String account = account();
        String image = registerImage(account);

        refused(account, image, "BlockDeviceMapping.1.DeviceName", "/dev/sda1",
                "BlockDeviceMapping.1.Ebs.VolumeSize", "8")
                .body("Response.Errors.Error.Code", equalTo("InvalidBlockDeviceMapping"))
                .body("Response.Errors.Error.Message", equalTo(
                        "Volume of size 8GB is smaller than snapshot '" + SNAPSHOT + "', expect size>= 16GB"));
        assertNothingLaunched(account);
    }

    @Test
    void anEntryNamingASnapshotThatDoesNotExistIsRefusedWithNothingLeftBehind() {
        String account = account();
        String image = registerImage(account);

        refused(account, image, "BlockDeviceMapping.1.DeviceName", "/dev/sdh",
                "BlockDeviceMapping.1.Ebs.SnapshotId", "snap-0fffffffffffffff1")
                .body("Response.Errors.Error.Code", equalTo("InvalidSnapshot.NotFound"))
                .body("Response.Errors.Error.Message", equalTo("The snapshot 'snap-0fffffffffffffff1' does not exist."));
        assertNothingLaunched(account);
    }

    @Test
    void aDeviceNamedTwiceIsRefusedWithNothingLeftBehind() {
        String account = account();

        refused(account, "ami-0abcdef1234567890",
                "BlockDeviceMapping.1.DeviceName", "/dev/sdh",
                "BlockDeviceMapping.1.Ebs.VolumeSize", "5",
                "BlockDeviceMapping.2.DeviceName", "/dev/sdh",
                "BlockDeviceMapping.2.Ebs.VolumeSize", "6")
                .body("Response.Errors.Error.Code", equalTo("InvalidBlockDeviceMapping"))
                .body("Response.Errors.Error.Message", equalTo("/dev/sdh is being assigned to multiple times."));
        assertNothingLaunched(account);
    }

    @Test
    void anEntryWithoutADeviceNameIsRefusedWithNothingLeftBehind() {
        String account = account();

        refused(account, "ami-0abcdef1234567890", "BlockDeviceMapping.1.Ebs.VolumeSize", "5")
                .body("Response.Errors.Error.Code", equalTo("InvalidBlockDeviceMapping"))
                .body("Response.Errors.Error.Message", equalTo("Missing device name"));
        assertNothingLaunched(account);
    }

    /**
     * A RegisterImage AMI rooted at /dev/sda1 on a 16 GiB gp2 snapshot, with an empty 125 GiB st1
     * data device at /dev/sdf that is kept when an instance terminates.
     */
    private static String registerImage(String account) {
        return ec2(account, "RegisterImage", "Name", "launch-volumes-" + account,
                "RootDeviceName", "/dev/sda1",
                "BlockDeviceMapping.1.DeviceName", "/dev/sda1",
                "BlockDeviceMapping.1.Ebs.SnapshotId", SNAPSHOT,
                "BlockDeviceMapping.1.Ebs.VolumeSize", "16",
                "BlockDeviceMapping.1.Ebs.VolumeType", "gp2",
                "BlockDeviceMapping.2.DeviceName", "/dev/sdf",
                "BlockDeviceMapping.2.Ebs.VolumeSize", "125",
                "BlockDeviceMapping.2.Ebs.VolumeType", "st1",
                "BlockDeviceMapping.2.Ebs.DeleteOnTermination", "false")
                .getString("RegisterImageResponse.imageId");
    }

    private static String launch(String account, String image, String... params) {
        return launchRequest(account, image, params).post("/").then().statusCode(200).extract()
                .path("RunInstancesResponse.instancesSet.item.instanceId");
    }

    private static ValidatableResponse refused(String account, String image, String... params) {
        return launchRequest(account, image, params).post("/").then().statusCode(400);
    }

    private static RequestSpecification launchRequest(String account, String image, String... params) {
        RequestSpecification request = request(account, "RunInstances", params)
                .formParam("InstanceType", "t3.micro").formParam("MinCount", "1").formParam("MaxCount", "1");
        return image == null ? request : request.formParam("ImageId", image);
    }

    private static void assertNothingLaunched(String account) {
        assertTrue(ec2(account, "DescribeInstances").getList(INSTANCE + ".instanceId").isEmpty());
        assertTrue(volumeIds(account).isEmpty());
        assertTrue(ec2(account, "DescribeNetworkInterfaces")
                .getList("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.networkInterfaceId").isEmpty());
    }

    private static String deviceVolume(String account, String instance, String device) {
        return ec2(account, "DescribeInstances", "InstanceId.1", instance).getString(
                INSTANCE + ".blockDeviceMapping.item.find { it.deviceName == '" + device + "' }.ebs.volumeId");
    }

    private static ValidatableResponse volume(String account, String volumeId) {
        return request(account, "DescribeVolumes", "VolumeId.1", volumeId).post("/").then().statusCode(200);
    }

    private static List<String> volumeIds(String account) {
        return ec2(account, "DescribeVolumes").getList("DescribeVolumesResponse.volumeSet.item.volumeId", String.class)
                .stream().sorted().toList();
    }

    private static String account() {
        return Long.toString(ACCOUNTS.incrementAndGet());
    }

    private static XmlPath ec2(String account, String action, String... params) {
        return request(account, action, params).post("/").then().statusCode(200).extract().xmlPath();
    }

    private static RequestSpecification request(String account, String action, String... params) {
        RequestSpecification request = given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account
                        + "/20261022/us-east-1/ec2/aws4_request, SignedHeaders=host, Signature=abc")
                .formParam("Action", action);
        for (int i = 0; i < params.length; i += 2) {
            request = request.formParam(params[i], params[i + 1]);
        }
        return request;
    }
}
