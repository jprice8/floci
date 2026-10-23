package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Alarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.CompositeAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class CompositeAlarmServiceTest {
    private static final String REGION = "eu-central-1";
    private static final String ACCOUNT = "123456789012";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CloudWatchMetricsService service = new CloudWatchMetricsService(
            new InMemoryStorage<>(), new InMemoryStorage<>(), new RegionResolver(REGION, ACCOUNT));

    /**
     * Replays a public probe of CloudWatch in eu-central-1 (2026-10-15) step by step, asserting the
     * states, reasons and triggering children AWS reported at each one.
     */
    @Test
    void evaluationMatchesWhatCloudWatchReportedForTheSameSteps() throws Exception {
        for (String name : List.of("m-a", "m-b", "m-c")) {
            metric(name);
        }
        Map<String, String> rules = new LinkedHashMap<>();
        rules.put("c-and", "ALARM(\"m-a\") AND ALARM(\"m-b\")");
        rules.put("c-or", "ALARM(" + arn("m-a") + ") OR ALARM(m-b)");
        rules.put("c-not", "NOT ALARM(\"m-a\")");
        rules.put("c-ok", "OK(\"m-a\")");
        rules.put("c-ins", "INSUFFICIENT_DATA(\"m-b\")");
        rules.put("c-true", "TRUE");
        rules.put("c-false", "FALSE");
        rules.put("c-nest", "ALARM(\"c-or\")");
        rules.put("c-mix", "(ALARM(\"m-a\") OR OK(\"m-b\")) AND NOT INSUFFICIENT_DATA(\"m-b\")");
        rules.put("c-least", "AT_LEAST(2, ALARM, (\"m-a\", \"m-b\"))");
        rules.put("c-rev", "ALARM(\"m-b\") AND ALARM(\"m-a\")");
        rules.put("c-pct", "AT_LEAST(50%, NOT OK, (m-a, m-b))");
        rules.put("c-prec", "TRUE OR TRUE AND FALSE");
        rules.put("c-notprec", "NOT TRUE AND FALSE");
        rules.forEach(this::composite);

        assertStates("""
                c-and OK, c-false OK, c-ins ALARM, c-least OK, c-mix OK, c-nest OK, c-not ALARM,
                c-notprec OK, c-ok OK, c-or OK, c-pct ALARM, c-prec ALARM, c-rev OK, c-true ALARM""");
        for (String name : rules.keySet()) {
            assertEquals(arn(name) + " was created and its alarm rule evaluates to " + composite(name).getStateValue(),
                    composite(name).getStateReason());
        }
        assertTriggering("c-and", "m-a INSUFFICIENT_DATA", "m-b INSUFFICIENT_DATA");
        assertTriggering("c-false");
        assertTriggering("c-ins", "m-b INSUFFICIENT_DATA");
        assertTriggering("c-least", "m-a INSUFFICIENT_DATA", "m-b INSUFFICIENT_DATA");
        assertTriggering("c-mix", "m-a INSUFFICIENT_DATA", "m-b INSUFFICIENT_DATA");
        assertTriggering("c-nest", "c-or OK");
        assertTriggering("c-not", "m-a INSUFFICIENT_DATA");
        assertTriggering("c-ok", "m-a INSUFFICIENT_DATA");
        assertTriggering("c-pct", "m-a INSUFFICIENT_DATA", "m-b INSUFFICIENT_DATA");
        assertTriggering("c-true");

        set("m-a", "ALARM");
        assertStates("""
                c-and OK, c-false OK, c-ins ALARM, c-least OK, c-mix OK, c-nest ALARM, c-not OK,
                c-notprec OK, c-ok OK, c-or ALARM, c-pct ALARM, c-prec ALARM, c-rev OK, c-true ALARM""");
        assertTransitionedBecause("c-or", "m-a", "ALARM");
        assertOrderedTriggering("c-or", "m-a ALARM");
        assertTransitionedBecause("c-nest", "c-or", "ALARM");
        assertOrderedTriggering("c-nest", "c-or ALARM");
        assertTransitionedBecause("c-not", "m-a", "ALARM");

        set("m-b", "ALARM");
        assertStates("""
                c-and ALARM, c-false OK, c-ins OK, c-least ALARM, c-mix ALARM, c-nest ALARM, c-not OK,
                c-notprec OK, c-ok OK, c-or ALARM, c-pct ALARM, c-prec ALARM, c-rev ALARM, c-true ALARM""");
        assertTransitionedBecause("c-and", "m-b", "ALARM");
        assertOrderedTriggering("c-and", "m-b ALARM", "m-a ALARM");
        assertOrderedTriggering("c-ins", "m-b ALARM");
        assertOrderedTriggering("c-least", "m-b ALARM", "m-a ALARM");
        assertOrderedTriggering("c-mix", "m-b ALARM", "m-a ALARM");
        assertOrderedTriggering("c-rev", "m-b ALARM", "m-a ALARM");

        set("m-a", "OK");
        assertStates("""
                c-and OK, c-false OK, c-ins OK, c-least OK, c-mix OK, c-nest ALARM, c-not ALARM,
                c-notprec OK, c-ok ALARM, c-or ALARM, c-pct ALARM, c-prec ALARM, c-rev OK, c-true ALARM""");
        for (String name : List.of("c-and", "c-least", "c-mix", "c-not", "c-ok", "c-rev")) {
            assertTransitionedBecause(name, "m-a", "OK");
            assertOrderedTriggering(name, "m-a OK");
        }

        set("m-b", "OK");
        assertStates("""
                c-and OK, c-false OK, c-ins OK, c-least OK, c-mix ALARM, c-nest OK, c-not ALARM,
                c-notprec OK, c-ok ALARM, c-or OK, c-pct OK, c-prec ALARM, c-rev OK, c-true ALARM""");
        assertOrderedTriggering("c-or", "m-b OK", "m-a OK");
        assertOrderedTriggering("c-nest", "c-or OK");
        assertOrderedTriggering("c-mix", "m-b OK");
        assertOrderedTriggering("c-pct", "m-b OK", "m-a OK");

        set("m-a", "ALARM");
        composite("c-late", "ALARM(\"m-a\")");
        assertEquals("ALARM", composite("c-late").getStateValue());
        assertTriggering("c-late", "m-a ALARM");

        set("c-true", "OK");
        assertEquals("OK", composite("c-true").getStateValue());
        assertEquals("probe", composite("c-true").getStateReason());
        assertNull(composite("c-true").getStateReasonData());

        set("c-and", "ALARM");
        assertEquals("ALARM", composite("c-and").getStateValue());
        set("m-b", "INSUFFICIENT_DATA");
        assertEquals("OK", composite("c-and").getStateValue());
        assertTransitionedBecause("c-and", "m-b", "INSUFFICIENT_DATA");
        assertOrderedTriggering("c-and", "m-b INSUFFICIENT_DATA");

        composite("c-false", "TRUE");
        assertEquals("ALARM", composite("c-false").getStateValue());
        assertEquals(arn("c-false") + " was updated and its new alarm rule evaluates to ALARM",
                composite("c-false").getStateReason());
        assertTriggering("c-false");

        String reason = composite("c-ok").getStateReason();
        long updated = composite("c-ok").getStateUpdatedTimestamp();
        composite("c-ok", "NOT ALARM(\"m-a\")");
        assertEquals("OK", composite("c-ok").getStateValue());
        assertEquals(reason, composite("c-ok").getStateReason());
        assertEquals(updated, composite("c-ok").getStateUpdatedTimestamp());

        composite("c-not", "ALARM(\"m-a\") OR ALARM(\"m-b\")");
        assertEquals("ALARM", composite("c-not").getStateValue());
        assertEquals(arn("c-not") + " was updated and its new alarm rule evaluates to ALARM",
                composite("c-not").getStateReason());
        assertOrderedTriggering("c-not", "m-a ALARM");

        set("m-b", "OK");
        set("m-a", "OK");
        composite("c-dist", "ALARM(\"m-a\") OR ALARM(\"m-b\")");
        composite("c-ord", "ALARM(\"m-b\") AND ALARM(\"m-a\")");
        assertTriggering("c-dist", "m-a OK", "m-b OK");
        assertTriggering("c-ord", "m-a OK", "m-b OK");
        set("m-b", "INSUFFICIENT_DATA");
        assertEquals("OK", composite("c-dist").getStateValue());
        set("m-a", "ALARM");
        assertEquals("ALARM", composite("c-dist").getStateValue());
        assertOrderedTriggering("c-dist", "m-a ALARM");
        assertEquals("OK", composite("c-ord").getStateValue());

        assertRejected("Could not save the composite alarm as alarms [" + arn("does-not-exist")
                + "] in the alarm rule do not exist", () -> composite("c-bad1", "ALARM(\"does-not-exist\")"));
        assertTrue(service.describeCompositeAlarms(List.of("c-bad1"), null, REGION).isEmpty());

        composite("c-or", "ALARM(\"c-nest\")");
        assertEquals("ALARM", composite("c-or").getStateValue());
        assertEquals("ALARM", composite("c-nest").getStateValue());

        assertRejected("Cannot delete m-a as there are composite alarm(s) depending on it.", () -> delete("m-a"));
        assertRejected("Cannot delete c-or as there are composite alarm(s) depending on it.", () -> delete("c-or"));
        assertRejected("Cannot delete [2] Composite Alarms as only one composite alarm delete is allowed in single request",
                () -> delete("c-true", "c-false"));
        delete("does-not-exist", "c-ok");
        assertTrue(service.describeCompositeAlarms(List.of("c-ok"), null, REGION).isEmpty());
        delete("does-not-exist");
        assertRejected("Cannot delete [2] Composite Alarms as only one composite alarm delete is allowed in single request",
                () -> delete("c-nest", "c-or"));
        composite("c-solo", "ALARM(\"m-c\")");
        assertRejected("Cannot delete m-c as there are composite alarm(s) depending on it.", () -> delete("m-c", "c-solo"));
        assertEquals(1, service.describeAlarms(List.of("m-c"), null, REGION).size());
        assertEquals(1, service.describeCompositeAlarms(List.of("c-solo"), null, REGION).size());
    }

    @Test
    void deletingSeveralReferencedAlarmsNamesThemAll() {
        metric("x");
        metric("y");
        composite("both", "ALARM(x) OR ALARM(y)");
        assertRejected("Cannot delete x,y as there are composite alarm(s) depending on them.", () -> delete("x", "y"));
        delete("both");
        delete("x", "y");
        assertTrue(service.describeAlarms(null, null, REGION).isEmpty());
    }

    @Test
    void aCycleIsNeverEvaluatedUntilItIsBroken() {
        metric("m");
        composite("a", "ALARM(m)");
        composite("b", "ALARM(a)");
        composite("a", "ALARM(b) OR ALARM(m)");
        set("m", "ALARM");
        assertEquals("OK", composite("a").getStateValue());
        assertEquals("OK", composite("b").getStateValue());
        assertRejected("Cannot delete a as there are composite alarm(s) depending on it.", () -> delete("a"));
        assertRejected("Cannot delete b as there are composite alarm(s) depending on it.", () -> delete("b"));

        composite("a", "ALARM(m)");
        assertEquals("ALARM", composite("a").getStateValue());
        assertEquals("ALARM", composite("b").getStateValue());
        assertTransitionedBecause("b", "a", "ALARM");
    }

    @Test
    void aManualStateHoldsUntilAChildChangesState() {
        metric("child");
        metric("unrelated");
        composite("parent", "ALARM(child)");
        set("parent", "ALARM");
        set("unrelated", "ALARM");
        set("child", "OK");
        set("child", "OK");
        assertEquals("OK", composite("parent").getStateValue());
        set("parent", "ALARM");
        metric("unrelated");
        assertEquals("ALARM", composite("parent").getStateValue());
        set("child", "INSUFFICIENT_DATA");
        assertEquals("OK", composite("parent").getStateValue());
    }

    @Test
    void aParentAboveTwoChangedChildrenIsEvaluatedOnceWithBothFinalStates() {
        metric("m");
        composite("left", "ALARM(m)");
        composite("right", "ALARM(m)");
        composite("top", "ALARM(left) AND ALARM(right)");
        composite("deep", "ALARM(top) AND ALARM(left)");
        set("m", "ALARM");
        assertEquals("ALARM", composite("top").getStateValue());
        assertEquals("ALARM", composite("deep").getStateValue());
        assertEquals(Set.of("left ALARM", "right ALARM"), triggering("top"));
        assertEquals(Set.of("top ALARM", "left ALARM"), triggering("deep"));
    }

    @Test
    void anUpdateKeepsStateAndTagsWhileCreateTakesTheGivenTags() {
        metric("m");
        CompositeAlarm created = new CompositeAlarm();
        created.setAlarmName("tagged");
        created.setAlarmRule("ALARM(m)");
        created.setTags(new LinkedHashMap<>(Map.of("team", "ops")));
        service.putCompositeAlarm(created, REGION);
        service.tagResource(arn("tagged"), Map.of("added", "later"), REGION);
        set("tagged", "ALARM");

        CompositeAlarm update = new CompositeAlarm();
        update.setAlarmName("tagged");
        update.setAlarmRule("ALARM(m) OR TRUE");
        update.setAlarmDescription("updated");
        update.setTags(new LinkedHashMap<>(Map.of("ignored", "on update")));
        service.putCompositeAlarm(update, REGION);

        CompositeAlarm stored = composite("tagged");
        assertEquals("updated", stored.getAlarmDescription());
        assertEquals("ALARM", stored.getStateValue());
        assertEquals("probe", stored.getStateReason());
        assertEquals(Map.of("team", "ops", "added", "later"), service.listTagsForResource(arn("tagged"), REGION));
    }

    @Test
    void alarmNamesAreUniqueAcrossBothKinds() {
        metric("taken-by-metric");
        composite("taken-by-composite", "TRUE");
        assertRejected("An alarm named taken-by-metric already exists as a metric alarm.",
                () -> composite("taken-by-metric", "TRUE"));
        assertRejected("An alarm named taken-by-composite already exists as a composite alarm.",
                () -> metric("taken-by-composite"));
        assertEquals("TRUE", composite("taken-by-composite").getAlarmRule());
    }

    @Test
    void redefiningAMetricAlarmKeepsItsStateSoItsParentsDoNotMove() {
        metric("m");
        composite("watcher", "OK(m)");
        set("m", "OK");
        assertEquals("ALARM", composite("watcher").getStateValue());
        String reason = composite("watcher").getStateReason();
        metric("m");
        assertEquals("OK", service.describeAlarms(List.of("m"), null, REGION).getFirst().getStateValue());
        assertEquals("ALARM", composite("watcher").getStateValue());
        assertEquals(reason, composite("watcher").getStateReason());
    }

    @Test
    void referencesResolveWithinTheRegionAndCountEachAlarmOnce() {
        metric("child");
        composite("by-both", "ALARM(child) AND ALARM(\"" + arn("child") + "\")");
        assertEquals("OK", composite("by-both").getStateValue());

        String elsewhere = "arn:aws:cloudwatch:us-west-2:" + ACCOUNT + ":alarm:child";
        assertRejected("Could not save the composite alarm as alarms [" + elsewhere + "] in the alarm rule do not exist",
                () -> composite("cross-region", "ALARM(\"" + elsewhere + "\")"));
        assertTrue(service.describeCompositeAlarms(null, null, "us-west-2").isEmpty());

        IntStream.range(0, 101).forEach(index -> metric("many-" + index));
        String hundred = IntStream.range(0, 100).mapToObj(index -> "ALARM(many-" + index + ")")
                .collect(Collectors.joining(" OR "));
        composite("hundred", hundred + " OR OK(\"" + arn("many-0") + "\")");
        assertRejected("Error in AlarmRule [the rule references more than 100 alarms]",
                () -> composite("too-many", hundred + " OR ALARM(many-100)"));
    }

    @Test
    void stateReasonDataStaysWithinItsDocumentedLimitForTheWidestRule() throws Exception {
        IntStream.range(0, 100).forEach(index -> metric("wide-child-with-a-long-descriptive-name-" + index));
        composite("wide", IntStream.range(0, 100).mapToObj(index -> "ALARM(wide-child-with-a-long-descriptive-name-"
                + index + ")").collect(Collectors.joining(" OR ")));
        String data = composite("wide").getStateReasonData();
        assertTrue(data.length() <= 4000, "StateReasonData is " + data.length() + " characters");
        int listed = MAPPER.readTree(data).path("triggeringAlarms").size();
        assertTrue(listed > 0 && listed < 100, listed + " children listed");
    }

    @Test
    void namesAndRulesAreValidatedBeforeAnythingIsStored() {
        assertRejected("1 validation error detected: Value null at 'alarmName' failed to satisfy constraint: "
                + "Member must not be null", () -> composite(null, "TRUE"));
        assertRejected("1 validation error detected: Value '' at 'alarmName' failed to satisfy constraint: "
                + "Member must have length greater than or equal to 1", () -> composite("", "TRUE"));
        assertThrows(AwsException.class, () -> composite("x".repeat(256), "TRUE"));
        composite("kept", "TRUE");
        assertThrows(AwsException.class, () -> composite("kept", "TRUE AND"));
        assertEquals("TRUE", composite("kept").getAlarmRule());
        assertEquals("ALARM", composite("kept").getStateValue());
    }

    @Test
    void describeKeepsTheKindsApartAndFiltersByNameOrPrefix() {
        metric("app-metric");
        composite("app-composite", "TRUE");
        composite("other-composite", "FALSE");
        assertEquals(List.of("app-metric"), names(service.describeAlarms(null, "app-", REGION)));
        assertEquals(List.of("app-composite"), names(service.describeCompositeAlarms(null, "app-", REGION)));
        assertEquals(List.of("other-composite"),
                names(service.describeCompositeAlarms(List.of("other-composite", "app-metric"), null, REGION)));
        assertEquals(List.of("app-metric"), names(service.allAlarms()));

        CloudWatchMetricsService.DescribedAlarms byDefault = describe(List.of(), "app-", List.of(), null, null);
        assertEquals(List.of("app-metric"), names(byDefault.metricAlarms()));
        assertEquals(List.of(), byDefault.compositeAlarms());
        CloudWatchMetricsService.DescribedAlarms both = describe(List.of(), "app-",
                List.of("CompositeAlarm", "MetricAlarm"), null, null);
        assertEquals(List.of("app-metric"), names(both.metricAlarms()));
        assertEquals(List.of("app-composite"), names(both.compositeAlarms()));
        CloudWatchMetricsService.DescribedAlarms logAlarms = describe(List.of(), null, List.of("LogAlarm"), null, null);
        assertEquals(List.of(), logAlarms.metricAlarms());
        assertEquals(List.of(), logAlarms.compositeAlarms());
        assertThrows(AwsException.class, () -> describe(List.of(), null, List.of("Composite"), null, null));
    }

    @Test
    void childrenAndParentsFollowTheRulesByNameOrArn() {
        metric("leaf");
        metric("other-leaf");
        composite("inner", "ALARM(leaf)");
        composite("outer", "ALARM(\"inner\") AND OK(\"" + arn("leaf") + "\") OR AT_LEAST(1, ALARM, (other-leaf))");

        CloudWatchMetricsService.DescribedAlarms children = describe(List.of(), null, List.of(), "outer", null);
        assertEquals(List.of("leaf", "other-leaf"), names(children.metricAlarms()));
        assertEquals(List.of("inner"), names(children.compositeAlarms()));
        assertEquals(List.of("inner", "outer"), names(describe(List.of(), null, List.of(), null, "leaf").compositeAlarms()));
        assertEquals(List.of("outer"), names(describe(List.of(), null, List.of(), null, "inner").compositeAlarms()));
        assertEquals(List.of(), names(describe(List.of(), null, List.of(), null, "outer").compositeAlarms()));
        assertEquals(List.of(), names(describe(List.of(), null, List.of(), "leaf", null).metricAlarms()));
        assertEquals(List.of(), names(describe(List.of(), null, List.of(), "missing", null).metricAlarms()));
        assertEquals(List.of(), names(describe(List.of(), null, List.of(), null, "missing").compositeAlarms()));

        for (Runnable combined : List.<Runnable>of(
                () -> describe(List.of(), null, List.of(), "outer", "leaf"),
                () -> describe(List.of("leaf"), null, List.of(), "outer", null),
                () -> describe(List.of(), "l", List.of(), null, "leaf"),
                () -> describe(List.of(), null, List.of("CompositeAlarm"), null, "leaf"))) {
            assertRejected("ChildrenOfAlarmName and ParentsOfAlarmName cannot be combined with each other or with "
                    + "any parameter except MaxRecords and NextToken.", combined);
        }
    }

    @Test
    void theNamedAlarmIsNeitherItsOwnChildNorItsOwnParent() {
        metric("m");
        composite("self", "ALARM(m)");
        composite("self", "ALARM(self) OR ALARM(m)");
        assertEquals(List.of("m"), names(describe(List.of(), null, List.of(), "self", null).metricAlarms()));
        assertEquals(List.of(), names(describe(List.of(), null, List.of(), "self", null).compositeAlarms()));
        assertEquals(List.of(), names(describe(List.of(), null, List.of(), null, "self").compositeAlarms()));
        assertEquals(List.of("self"), names(describe(List.of(), null, List.of(), null, "m").compositeAlarms()));
    }

    private CloudWatchMetricsService.DescribedAlarms describe(List<String> names, String prefix, List<String> types,
                                                              String childrenOf, String parentsOf) {
        return service.describeAlarms(names, prefix, types, childrenOf, parentsOf, REGION);
    }

    @Test
    void storedAlarmsReloadAsTheirOwnKindAndOlderEntriesAsMetricAlarms(@TempDir Path directory) throws Exception {
        TypeReference<Map<String, Alarm>> type = new TypeReference<>() { };
        Map<String, Alarm> older = MAPPER.readValue("{\"legacy\":{\"alarmName\":\"old\",\"metricName\":\"CPU\","
                + "\"stateValue\":\"OK\",\"stateUpdatedTimestamp\":1700000000}}", type);
        assertInstanceOf(MetricAlarm.class, older.get("legacy"));
        assertEquals("CPU", ((MetricAlarm) older.get("legacy")).getMetricName());
        // Stored before transitions were recorded: it reports its last state update, and stores nothing new.
        assertEquals(1700000000, older.get("legacy").lastTransitionTimestamp());
        assertEquals(0, MAPPER.readTree(MAPPER.writeValueAsString(older.get("legacy")))
                .path("stateTransitionedTimestamp").asLong());

        Path file = directory.resolve("alarms.json");
        PersistentStorage<String, Alarm> store = new PersistentStorage<>(file, type);
        service = new CloudWatchMetricsService(new InMemoryStorage<>(), store, new RegionResolver(REGION, ACCOUNT));
        metric("m");
        composite("c", "ALARM(m) OR OK(m)");
        set("m", "OK");
        store.flush();

        PersistentStorage<String, Alarm> reloaded = new PersistentStorage<>(file, type);
        reloaded.load();
        service = new CloudWatchMetricsService(new InMemoryStorage<>(), reloaded, new RegionResolver(REGION, ACCOUNT));
        CompositeAlarm restored = composite("c");
        assertEquals("ALARM(m) OR OK(m)", restored.getAlarmRule());
        assertEquals("ALARM", restored.getStateValue());
        assertEquals(List.of("m"), names(service.describeAlarms(null, null, REGION)));
        set("m", "ALARM");
        assertEquals("ALARM", composite("c").getStateValue());
        assertRejected("Cannot delete m as there are composite alarm(s) depending on it.", () -> delete("m"));
    }

    private void metric(String name) {
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName(name);
        alarm.setMetricName("Probe");
        alarm.setNamespace("Composite/Probe");
        alarm.setStatistic("Sum");
        alarm.setPeriod(3600);
        alarm.setEvaluationPeriods(1);
        alarm.setThreshold(1.0);
        alarm.setComparisonOperator("GreaterThanThreshold");
        service.putMetricAlarm(alarm, REGION);
    }

    private void composite(String name, String rule) {
        CompositeAlarm alarm = new CompositeAlarm();
        alarm.setAlarmName(name);
        alarm.setAlarmRule(rule);
        alarm.setActionsEnabled(true);
        service.putCompositeAlarm(alarm, REGION);
    }

    private CompositeAlarm composite(String name) {
        List<CompositeAlarm> found = service.describeCompositeAlarms(List.of(name), null, REGION);
        assertEquals(1, found.size(), name);
        return found.getFirst();
    }

    private void set(String name, String state) {
        service.setAlarmState(name, state, "probe", null, REGION);
    }

    private void delete(String... names) {
        service.deleteAlarms(List.of(names), REGION);
    }

    private static String arn(String name) {
        return "arn:aws:cloudwatch:" + REGION + ":" + ACCOUNT + ":alarm:" + name;
    }

    private void assertStates(String expected) {
        Map<String, String> wanted = new LinkedHashMap<>();
        for (String entry : expected.replace("\n", " ").split(",")) {
            String[] parts = entry.trim().split(" ");
            wanted.put(parts[0], parts[1]);
        }
        Map<String, String> actual = new LinkedHashMap<>();
        wanted.keySet().forEach(name -> actual.put(name, composite(name).getStateValue()));
        assertEquals(wanted, actual);
    }

    private void assertTransitionedBecause(String name, String child, String childState) {
        String reason = composite(name).getStateReason();
        assertTrue(reason.matches("\\Q" + arn(child) + " transitioned to " + childState
                + " at \\E[A-Z][a-z]+day \\d{2} [A-Z][a-z]+, \\d{4} \\d{2}:\\d{2}:\\d{2} UTC"), reason);
    }

    /** Triggering children whose relative order AWS showed, which this run reproduces exactly. */
    private void assertOrderedTriggering(String name, String... expected) {
        assertEquals(List.of(expected), orderedTriggering(name));
    }

    /** Triggering children of a create or update; their order depends on sub-second timing. */
    private void assertTriggering(String name, String... expected) {
        assertEquals(Set.of(expected), triggering(name));
    }

    private Set<String> triggering(String name) {
        return new HashSet<>(orderedTriggering(name));
    }

    private List<String> orderedTriggering(String name) {
        try {
            JsonNode data = MAPPER.readTree(composite(name).getStateReasonData());
            List<String> children = new ArrayList<>();
            for (JsonNode child : data.path("triggeringAlarms")) {
                String childArn = child.path("arn").asText();
                assertTrue(child.path("state").path("timestamp").asText()
                        .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\+0000"), data.toString());
                children.add(childArn.substring(childArn.lastIndexOf(':') + 1) + " "
                        + child.path("state").path("value").asText());
            }
            return children;
        } catch (Exception e) {
            throw new AssertionError("StateReasonData of " + name + " is not JSON", e);
        }
    }

    private static void assertRejected(String message, Runnable call) {
        AwsException error = assertThrows(AwsException.class, call::run);
        assertEquals("ValidationError", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals(message, error.getMessage());
    }

    private static List<String> names(List<? extends Alarm> alarms) {
        return alarms.stream().map(Alarm::getAlarmName).sorted().toList();
    }
}
