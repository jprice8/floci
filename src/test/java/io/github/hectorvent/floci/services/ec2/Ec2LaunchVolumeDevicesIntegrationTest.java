package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A container-backed launch hands the container manager a hook that attaches the data volumes the
 * launch created, which the manager runs before UserData. The root volume is the container's own
 * file system and is never attached as a device.
 *
 * <p>It shares the application of {@link Ec2CapturedImageReclaimIntegrationTest}, which turns EC2's
 * mock mode off so that a launch reaches the container manager, mocked here as there.
 */
@QuarkusTest
@TestProfile(Ec2CapturedImageReclaimIntegrationTest.ContainerBackedEc2.class)
class Ec2LaunchVolumeDevicesIntegrationTest {

    private static final String REGION = "us-east-1";

    @Inject
    Ec2Service service;

    @InjectMock
    Ec2ContainerManager containerManager;

    @InjectMock
    AmiImageResolver amiImageResolver;

    @InjectMock
    Ec2VolumeBlockDeviceManager volumeBlockDeviceManager;

    @BeforeEach
    void resolveEveryAmiToABaseImage() {
        when(amiImageResolver.resolveImage(anyString())).thenReturn(ResolvedAmiImage.minimal("floci/base:latest"));
    }

    @Test
    void theLaunchAttachesItsDataVolumesOnceItsContainerRuns() {
        Instance instance = launchWithDataVolume(true);
        try {
            Runnable postStart = postStartHook(instance);
            verify(volumeBlockDeviceManager, never()).attachVolume(any(), any(), anyString());

            // The container manager marks the instance running before it runs the hook.
            instance.setState(InstanceState.running());
            postStart.run();

            ArgumentCaptor<Volume> attached = ArgumentCaptor.forClass(Volume.class);
            verify(volumeBlockDeviceManager).attachVolume(attached.capture(), eq(instance), eq("/dev/sdf"));
            assertEquals(6, attached.getValue().getSize());
            verify(volumeBlockDeviceManager, never()).attachVolume(any(), any(), eq(instance.getRootDeviceName()));
        } finally {
            // The application is shared with other classes, so the instance gives its volumes back.
            service.terminateInstances(REGION, List.of(instance.getInstanceId()));
        }
    }

    @Test
    void theHookAttachesNothingOnceTheInstanceHasLeftRunning() {
        Instance instance = launchWithDataVolume(true);
        try {
            Runnable postStart = postStartHook(instance);

            // TerminateInstances marks the instance shutting-down before it ends its attachments.
            instance.setState(InstanceState.shuttingDown());
            postStart.run();

            verify(volumeBlockDeviceManager, never()).attachVolume(any(), any(), anyString());
        } finally {
            service.terminateInstances(REGION, List.of(instance.getInstanceId()));
        }
    }

    @Test
    void theHookAttachesNothingOnceTerminationHasTakenTheVolumes() {
        Instance instance = launchWithDataVolume(true);
        Runnable postStart = postStartHook(instance);
        instance.setState(InstanceState.running());

        // A TerminateInstances landing before the hook runs: its volumes are gone, so the hook
        // must not recreate a backing file for one.
        service.terminateInstances(REGION, List.of(instance.getInstanceId()));
        instance.setState(InstanceState.running());
        postStart.run();

        verify(volumeBlockDeviceManager, never()).attachVolume(any(), any(), anyString());
        instance.setState(InstanceState.terminated());
    }

    @Test
    void terminationWaitsForTheHookToFinishAttachingAVolume() throws Exception {
        Instance instance = launchWithDataVolumes(true, "/dev/sdf", "/dev/sdg");
        Runnable postStart = postStartHook(instance);
        instance.setState(InstanceState.running());
        // The container manager marks the instance shutting-down as termination begins.
        doAnswer(invocation -> {
            invocation.<Instance>getArgument(0).setState(InstanceState.shuttingDown());
            return null;
        }).when(containerManager).terminate(eq(instance), any());

        HookRace race = raceTheHook(instance, postStart,
                () -> service.terminateInstances(REGION, List.of(instance.getInstanceId())));

        // The volume came off and went only once its attach had finished, and the hook, which
        // found the instance shutting down, attached no other.
        assertTrue(race.detachedAfterAttach());
        verify(volumeBlockDeviceManager).deleteVolume(race.attachedVolume());
        verify(volumeBlockDeviceManager, times(1)).attachVolume(any(), any(), anyString());
        instance.setState(InstanceState.terminated());
    }

    @Test
    void detachVolumeWaitsForTheHookToFinishAttachingAVolume() throws Exception {
        Instance instance = launchWithDataVolumes(false, "/dev/sdf");
        try {
            Runnable postStart = postStartHook(instance);
            instance.setState(InstanceState.running());
            String volumeId = volumeAt(instance, "/dev/sdf");

            HookRace race = raceTheHook(instance, postStart,
                    () -> service.detachVolume(REGION, volumeId, null, null, false));

            assertEquals(volumeId, race.attachedVolume());
            assertTrue(race.detachedAfterAttach());
            assertEquals("available",
                    service.describeVolumes(REGION, List.of(volumeId), Map.of()).getFirst().getState());
            service.deleteVolume(REGION, volumeId);
        } finally {
            service.terminateInstances(REGION, List.of(instance.getInstanceId()));
        }
    }

    @Test
    void aRebootGivesTheRestartedContainerItsDataVolumesAgain() {
        Instance instance = launchWithDataVolume(true);
        try {
            instance.setState(InstanceState.running());

            service.rebootInstances(REGION, List.of(instance.getInstanceId()));
            ArgumentCaptor<Runnable> postRestart = ArgumentCaptor.forClass(Runnable.class);
            verify(containerManager).reboot(eq(instance), postRestart.capture());
            verify(volumeBlockDeviceManager, never()).attachVolume(any(), any(), anyString());

            // The restarted container has a fresh /dev, which the hook gives the device back.
            postRestart.getValue().run();
            verify(volumeBlockDeviceManager).attachVolume(any(), eq(instance), eq("/dev/sdf"));
            verify(volumeBlockDeviceManager, never()).attachVolume(any(), any(), eq(instance.getRootDeviceName()));
        } finally {
            service.terminateInstances(REGION, List.of(instance.getInstanceId()));
        }
    }

    @Test
    void aStartGivesTheContainerItsDataVolumesOnceItRuns() {
        Instance instance = launchWithDataVolume(true);
        try {
            instance.setState(InstanceState.stopped());

            service.startInstances(REGION, List.of(instance.getInstanceId()));
            ArgumentCaptor<Runnable> postStart = ArgumentCaptor.forClass(Runnable.class);
            verify(containerManager).start(eq(instance), postStart.capture());

            // The container manager marks the instance running before it runs the hook.
            instance.setState(InstanceState.running());
            postStart.getValue().run();
            verify(volumeBlockDeviceManager).attachVolume(any(), eq(instance), eq("/dev/sdf"));
            verify(volumeBlockDeviceManager, never()).attachVolume(any(), any(), eq(instance.getRootDeviceName()));
        } finally {
            service.terminateInstances(REGION, List.of(instance.getInstanceId()));
        }
    }

    @Test
    void theStartHookAttachesNothingOnceTheInstanceHasLeftRunning() {
        Instance instance = launchWithDataVolume(true);
        try {
            instance.setState(InstanceState.stopped());
            service.startInstances(REGION, List.of(instance.getInstanceId()));
            ArgumentCaptor<Runnable> postStart = ArgumentCaptor.forClass(Runnable.class);
            verify(containerManager).start(eq(instance), postStart.capture());

            // A StopInstances landing between the start and its hook.
            instance.setState(InstanceState.stopping());
            postStart.getValue().run();

            verify(volumeBlockDeviceManager, never()).attachVolume(any(), any(), anyString());
        } finally {
            service.terminateInstances(REGION, List.of(instance.getInstanceId()));
        }
    }

    @Test
    void attachVolumeWaitsForAnAttachOfTheSameVolumeUnderWay() throws Exception {
        Instance instance = launchWithDataVolumes(true);
        instance.setState(InstanceState.running());
        String volumeId = service.createVolume(REGION, instance.getPlacement().getAvailabilityZone(), "gp3", 4,
                false, 0, null, null, List.of()).getVolumeId();
        CountDownLatch attaching = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(volumeBlockDeviceManager.attachVolume(any(), eq(instance), anyString())).thenAnswer(invocation -> {
            attaching.countDown();
            release.await(10, TimeUnit.SECONDS);
            return Optional.of(invocation.<String>getArgument(2));
        });
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = threads.submit(() ->
                    service.attachVolume(REGION, volumeId, instance.getInstanceId(), "/dev/sdh"));
            assertTrue(attaching.await(10, TimeUnit.SECONDS), "the first attach never reached the device");
            Future<?> second = threads.submit(() ->
                    service.attachVolume(REGION, volumeId, instance.getInstanceId(), "/dev/sdi"));
            // Held off by the attach under way; unheld, it would answer at once.
            assertThrows(TimeoutException.class, () -> second.get(500, TimeUnit.MILLISECONDS));
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            ExecutionException refused = assertThrows(ExecutionException.class,
                    () -> second.get(10, TimeUnit.SECONDS));
            assertEquals("VolumeInUse", ((AwsException) refused.getCause()).getErrorCode());
        } finally {
            release.countDown();
            threads.shutdownNow();
            service.terminateInstances(REGION, List.of(instance.getInstanceId()));
            service.deleteVolume(REGION, volumeId);
        }
    }

    @Test
    void modifyInstanceAttributeRefusesADeviceTerminationTakesWhileItWaits() throws Exception {
        Instance instance = launchWithDataVolume(true);
        instance.setState(InstanceState.running());
        String volumeId = volumeAt(instance, "/dev/sdf");
        // The container manager marks the instance shutting-down as termination begins.
        doAnswer(invocation -> {
            invocation.<Instance>getArgument(0).setState(InstanceState.shuttingDown());
            return null;
        }).when(containerManager).terminate(eq(instance), any());
        CountDownLatch detaching = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            detaching.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(volumeBlockDeviceManager).detachVolume(any(), any(), anyString());
        LaunchTemplateData.Ebs keep = new LaunchTemplateData.Ebs();
        keep.setDeleteOnTermination(false);
        LaunchTemplateData.BlockDeviceMapping entry = new LaunchTemplateData.BlockDeviceMapping();
        entry.setDeviceName("/dev/sdf");
        entry.setEbs(keep);

        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            // Termination holds /dev/sdf's volume inside its block device detach.
            Future<?> termination = threads.submit(() ->
                    service.terminateInstances(REGION, List.of(instance.getInstanceId())));
            assertTrue(detaching.await(10, TimeUnit.SECONDS), "termination never reached the device");
            Future<?> modify = threads.submit(() ->
                    service.modifyInstanceBlockDeviceMappings(REGION, instance.getInstanceId(), List.of(entry)));
            assertThrows(TimeoutException.class, () -> modify.get(500, TimeUnit.MILLISECONDS));
            release.countDown();
            termination.get(10, TimeUnit.SECONDS);

            // The volume went with the instance, so the change it waited to make is refused.
            ExecutionException refused = assertThrows(ExecutionException.class,
                    () -> modify.get(10, TimeUnit.SECONDS));
            assertEquals("InvalidInstanceAttributeValue", ((AwsException) refused.getCause()).getErrorCode());
            verify(volumeBlockDeviceManager).deleteVolume(volumeId);
        } finally {
            release.countDown();
            threads.shutdownNow();
            instance.setState(InstanceState.terminated());
        }
    }

    @Test
    void modifyInstanceAttributeChangesNoDeviceWhenOneItNamesGoesWhileItWaits() throws Exception {
        Instance instance = launchWithDataVolumes(true, "/dev/sdf", "/dev/sdg");
        instance.setState(InstanceState.running());
        doAnswer(invocation -> {
            invocation.<Instance>getArgument(0).setState(InstanceState.shuttingDown());
            return null;
        }).when(containerManager).terminate(eq(instance), any());
        // Termination holds the first data volume it reaches inside its block device detach,
        // before it has touched the other one.
        CountDownLatch detaching = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> held = new AtomicReference<>();
        doAnswer(invocation -> {
            if (held.compareAndSet(null, invocation.<Volume>getArgument(0).getVolumeId())) {
                detaching.countDown();
                release.await(10, TimeUnit.SECONDS);
            }
            return null;
        }).when(volumeBlockDeviceManager).detachVolume(any(), any(), anyString());

        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> termination = threads.submit(() ->
                    service.terminateInstances(REGION, List.of(instance.getInstanceId())));
            assertTrue(detaching.await(10, TimeUnit.SECONDS), "termination never reached a device");
            String heldDevice = volumeAt(instance, "/dev/sdf").equals(held.get()) ? "/dev/sdf" : "/dev/sdg";
            String otherDevice = "/dev/sdf".equals(heldDevice) ? "/dev/sdg" : "/dev/sdf";
            String otherVolume = volumeAt(instance, otherDevice);
            // The device termination has not reached comes first, so a change made device by
            // device would keep it before the held one is found gone.
            Future<?> modify = threads.submit(() -> service.modifyInstanceBlockDeviceMappings(REGION,
                    instance.getInstanceId(), List.of(keptOnTermination(otherDevice), keptOnTermination(heldDevice))));
            assertThrows(TimeoutException.class, () -> modify.get(500, TimeUnit.MILLISECONDS));
            release.countDown();
            termination.get(10, TimeUnit.SECONDS);

            ExecutionException refused = assertThrows(ExecutionException.class,
                    () -> modify.get(10, TimeUnit.SECONDS));
            assertEquals("InvalidInstanceAttributeValue", ((AwsException) refused.getCause()).getErrorCode());
            // Unchanged, the other volume went with the instance as its launch asked.
            verify(volumeBlockDeviceManager).deleteVolume(otherVolume);
        } finally {
            release.countDown();
            threads.shutdownNow();
            instance.setState(InstanceState.terminated());
        }
    }

    @Test
    void theVolumesOfALaunchWhoseContainerNeverStartedAreSettledAsTerminationWouldSettleThem() {
        Instance instance = launchWithDataVolume(false);
        String dataVolume = volumeAt(instance, "/dev/sdf");
        String rootVolume = instance.getRootVolumeId();

        // As the container manager does when the container never starts: the instance ends
        // terminated on the launch executor, without TerminateInstances.
        instance.setState(InstanceState.terminated());
        List<String> left = service.describeVolumes(REGION, List.of(), Map.of()).stream()
                .map(Volume::getVolumeId).toList();

        // The root took the launch default and is deleted; /dev/sdf asked to be kept.
        assertTrue(left.contains(dataVolume));
        assertFalse(left.contains(rootVolume));
        Volume kept = service.describeVolumes(REGION, List.of(dataVolume), Map.of()).getFirst();
        assertEquals("available", kept.getState());
        assertTrue(kept.getAttachments().isEmpty());
        assertTrue(service.instanceVolumeAttachments(instance).isEmpty());
        service.deleteVolume(REGION, dataVolume);
    }

    @Test
    void describingOtherVolumesLeavesAFailedLaunchsVolumesUntilTheyAreRead() {
        Instance instance = launchWithDataVolume(true);
        String dataVolume = volumeAt(instance, "/dev/sdf");
        String other = service.createVolume(REGION, instance.getPlacement().getAvailabilityZone(), "gp3", 4,
                false, 0, null, null, List.of()).getVolumeId();
        instance.setState(InstanceState.terminated());

        // Settling a volume can mean Docker work, so a call naming other volumes does none for these.
        service.describeVolumes(REGION, List.of(other), Map.of());
        verify(volumeBlockDeviceManager, never()).detachVolume(any(), any(), anyString());
        verify(volumeBlockDeviceManager, never()).deleteVolume(dataVolume);

        assertFalse(service.describeVolumes(REGION, List.of(), Map.of()).stream()
                .map(Volume::getVolumeId).toList().contains(dataVolume));
        verify(volumeBlockDeviceManager).deleteVolume(dataVolume);
        service.deleteVolume(REGION, other);
    }

    private static LaunchTemplateData.BlockDeviceMapping keptOnTermination(String device) {
        LaunchTemplateData.Ebs ebs = new LaunchTemplateData.Ebs();
        ebs.setDeleteOnTermination(false);
        LaunchTemplateData.BlockDeviceMapping entry = new LaunchTemplateData.BlockDeviceMapping();
        entry.setDeviceName(device);
        entry.setEbs(ebs);
        return entry;
    }

    private Instance launchWithDataVolume(boolean deleteOnTermination) {
        return launchWithDataVolumes(deleteOnTermination, "/dev/sdf");
    }

    private Instance launchWithDataVolumes(boolean deleteOnTermination, String... devices) {
        List<LaunchTemplateData.BlockDeviceMapping> mappings = new ArrayList<>();
        for (String device : devices) {
            LaunchTemplateData.Ebs ebs = new LaunchTemplateData.Ebs();
            ebs.setVolumeSize(6);
            ebs.setDeleteOnTermination(deleteOnTermination);
            LaunchTemplateData.BlockDeviceMapping data = new LaunchTemplateData.BlockDeviceMapping();
            data.setDeviceName(device);
            data.setEbs(ebs);
            mappings.add(data);
        }
        return service.runInstances(REGION, "ami-amazonlinux2023", "t3.micro", 1, 1, null,
                List.of(), null, null, List.of(), null, null, null, null, 0, null, null, null, null, false,
                null, null, mappings).getInstances().getFirst();
    }

    /**
     * Runs the launch hook with its first attach held open, starts the competing call while the
     * hook is inside that attach, and checks that the call cannot finish before the attach does:
     * the hook holds the volume's lock, which the call has to take too.
     */
    private HookRace raceTheHook(Instance instance, Runnable postStart, Runnable competing) throws Exception {
        CountDownLatch attaching = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> attachedVolume = new AtomicReference<>();
        AtomicBoolean attachDone = new AtomicBoolean();
        AtomicReference<Boolean> detachedAfterAttach = new AtomicReference<>();
        when(volumeBlockDeviceManager.attachVolume(any(), eq(instance), anyString())).thenAnswer(invocation -> {
            attachedVolume.set(invocation.<Volume>getArgument(0).getVolumeId());
            attaching.countDown();
            release.await(10, TimeUnit.SECONDS);
            attachDone.set(true);
            return Optional.of(invocation.<String>getArgument(2));
        });
        doAnswer(invocation -> {
            if (invocation.<Volume>getArgument(0).getVolumeId().equals(attachedVolume.get())) {
                detachedAfterAttach.set(attachDone.get());
            }
            return null;
        }).when(volumeBlockDeviceManager).detachVolume(any(), any(), anyString());

        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> hook = threads.submit(postStart);
            assertTrue(attaching.await(10, TimeUnit.SECONDS), "the hook never attached a volume");
            Future<?> call = threads.submit(competing);
            // Unheld, the call would be done well within this.
            assertThrows(TimeoutException.class, () -> call.get(500, TimeUnit.MILLISECONDS));
            release.countDown();
            hook.get(10, TimeUnit.SECONDS);
            call.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            threads.shutdownNow();
        }
        return new HookRace(attachedVolume.get(), Boolean.TRUE.equals(detachedAfterAttach.get()));
    }

    /** The volume the hook was attaching, and whether its block device came off only afterwards. */
    private record HookRace(String attachedVolume, boolean detachedAfterAttach) {}

    private Runnable postStartHook(Instance instance) {
        ArgumentCaptor<Runnable> postStart = ArgumentCaptor.forClass(Runnable.class);
        verify(containerManager).launch(eq(instance), any(), any(), anyString(), any(), any(), any(),
                postStart.capture());
        return postStart.getValue();
    }

    private String volumeAt(Instance instance, String device) {
        return service.describeVolumes(REGION, List.of(), Map.of()).stream()
                .filter(volume -> volume.getAttachments().stream()
                        .anyMatch(attachment -> instance.getInstanceId().equals(attachment.getInstanceId())
                                && device.equals(attachment.getDevice())))
                .map(Volume::getVolumeId)
                .findFirst().orElseThrow();
    }
}
