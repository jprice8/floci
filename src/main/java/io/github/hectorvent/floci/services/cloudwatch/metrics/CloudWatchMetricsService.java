package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.core.storage.WriteProfile;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Alarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.CompositeAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@ApplicationScoped
public class CloudWatchMetricsService {

    private static final Logger LOG = Logger.getLogger(CloudWatchMetricsService.class);
    /** The limit AWS puts on the distinct alarms one composite alarm's rule can reference. */
    private static final int MAX_CHILD_ALARMS = 100;
    /** The length AWS documents as the most an alarm's StateReasonData can hold. */
    private static final int MAX_STATE_REASON_DATA = 4000;
    private static final Set<String> ALARM_TYPES = Set.of("CompositeAlarm", "LogAlarm", "MetricAlarm");
    /** "Friday 04 August, 2023 12:54:46 UTC", as in a composite alarm's StateReason. */
    private static final DateTimeFormatter REASON_TIME = DateTimeFormatter
            .ofPattern("EEEE dd MMMM, yyyy HH:mm:ss 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);
    /** "2023-08-04T12:54:46.000+0000", as in a composite alarm's StateReasonData. */
    private static final DateTimeFormatter REASON_DATA_TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final StorageBackend<String, MetricDatum> metricStore;
    private final StorageBackend<String, Alarm> alarmStore;
    private final RegionResolver regionResolver;
    private final AlarmSnsNotifier alarmSnsNotifier;

    public CloudWatchMetricsService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory, regionResolver, null);
    }

    @Inject
    public CloudWatchMetricsService(StorageFactory storageFactory, RegionResolver regionResolver,
                                    AlarmSnsNotifier alarmSnsNotifier) {
        // Services publish into this store every minute, SQS for every active queue, so under
        // persistent mode it is journaled rather than rewritten in full on every datum.
        this.metricStore = storageFactory.create("cloudwatchmetrics", "cwmetrics.json",
                new TypeReference<Map<String, MetricDatum>>() {}, WriteProfile.APPEND_HEAVY);
        this.alarmStore = storageFactory.create("cloudwatchmetrics", "cwalarms.json",
                new TypeReference<Map<String, Alarm>>() {});
        this.regionResolver = regionResolver;
        this.alarmSnsNotifier = alarmSnsNotifier;
    }

    CloudWatchMetricsService(StorageBackend<String, MetricDatum> metricStore,
                             StorageBackend<String, Alarm> alarmStore,
                             RegionResolver regionResolver) {
        this(metricStore, alarmStore, regionResolver, null);
    }

    CloudWatchMetricsService(StorageBackend<String, MetricDatum> metricStore,
                             StorageBackend<String, Alarm> alarmStore,
                             RegionResolver regionResolver, AlarmSnsNotifier alarmSnsNotifier) {
        this.metricStore = metricStore;
        this.alarmStore = alarmStore;
        this.regionResolver = regionResolver;
        this.alarmSnsNotifier = alarmSnsNotifier;
    }

    public void putMetricData(String namespace, List<MetricDatum> datums, String region) {
        putMetricDataForAccount(null, namespace, datums, region);
    }

    /**
     * Stores the datums in {@code accountId}'s partition rather than the caller's, for writers that
     * run outside a request, such as a metric filter publishing for a log batch a container
     * streamed on another account's behalf. A null account is the caller's own.
     */
    public void putMetricDataForAccount(String accountId, String namespace, List<MetricDatum> datums, String region) {
        long nowSeconds = Instant.now().getEpochSecond();
        for (MetricDatum datum : datums) {
            datum.setNamespace(namespace);
            if (datum.getTimestamp() == 0) {
                datum.setTimestamp(nowSeconds);
            }
            // Synthesize StatisticValues if only a scalar value was provided
            if (datum.getSampleCount() == 0 && datum.getSum() == 0) {
                datum.setSampleCount(1);
                datum.setSum(datum.getValue());
                datum.setMinimum(datum.getValue());
                datum.setMaximum(datum.getValue());
            }

            storeDatum(accountId, namespace, datum, region, UUID.randomUUID().toString());
        }
        LOG.debugv("PutMetricData: {0} datums for namespace {1}", datums.size(), namespace);
    }

    /**
     * Internal scalar publication, not an AWS PutMetricData operation. The publisher supplies a
     * canonical account, an explicit event timestamp (including epoch zero), and a stable ID for
     * this one contribution. Retrying a partially/ambiguously committed write replaces the same
     * key instead of appending another sample. Distinct contributions must have distinct IDs.
     * The caller's snapshot is never mutated or retained by the store.
     */
    public void publishMetricForAccount(String accountId, String namespace, MetricDatum datum,
                                        String region, String publicationId) {
        if (accountId == null || accountId.isBlank() || publicationId == null || publicationId.isBlank()) {
            throw new IllegalArgumentException("Internal publication requires an explicit account and publication ID");
        }
        MetricDatum sample = new MetricDatum();
        sample.setNamespace(namespace);
        sample.setMetricName(datum.getMetricName());
        sample.setUnit(datum.getUnit());
        sample.setDimensions(List.copyOf(datum.getDimensions()));
        sample.setTimestamp(datum.getTimestamp());
        sample.setValue(datum.getValue());
        sample.setSampleCount(1);
        sample.setSum(datum.getValue());
        sample.setMinimum(datum.getValue());
        sample.setMaximum(datum.getValue());
        storeDatum(accountId, namespace, sample, region, "publication-" + publicationId);
    }

    private void storeDatum(String accountId, String namespace, MetricDatum datum, String region, String id) {
        String key = region + "::" + namespace + "::" + datum.getMetricName()
                + "::" + buildDimKey(datum.getDimensions()) + "::"
                + String.format("%013d", datum.getTimestamp()) + "::" + id;
        if (accountId != null && metricStore instanceof AccountAwareStorageBackend<?> rawAware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<MetricDatum> aware = (AccountAwareStorageBackend<MetricDatum>) rawAware;
            aware.putForAccount(accountId, key, datum);
        } else {
            metricStore.put(key, datum);
        }
    }

    /**
     * Drops the {@code namespace} datapoints stamped before {@code cutoff}, in every account. A service
     * that writes its own metrics each minute, such as SQS, calls it so that history stays bounded.
     */
    public void deleteMetricDataBefore(String namespace, Instant cutoff) {
        long cutoffSeconds = cutoff.getEpochSecond();
        Predicate<MetricDatum> expired = d -> namespace.equals(d.getNamespace()) && d.getTimestamp() < cutoffSeconds;
        if (metricStore instanceof AccountAwareStorageBackend<MetricDatum> aware) {
            aware.deleteAllAccountsMatching(expired);
            return;
        }
        for (String key : List.copyOf(metricStore.keys())) {
            metricStore.get(key).filter(expired).ifPresent(ignored -> metricStore.delete(key));
        }
    }

    public record MetricIdentity(String namespace, String metricName, List<Dimension> dimensions) {}

    public List<MetricIdentity> listMetrics(String namespace, String metricName,
                                             List<Dimension> dimensions, String region) {
        String prefix = region + "::";
        if (namespace != null && !namespace.isBlank()) {
            prefix += namespace + "::";
        }

        final String finalPrefix = prefix;
        List<MetricDatum> all = metricStore.scan(k -> k.startsWith(finalPrefix));

        // De-duplicate by (namespace, metricName, dimKey)
        Map<String, MetricIdentity> deduped = new LinkedHashMap<>();
        for (MetricDatum d : all) {
            if (metricName != null && !metricName.isBlank() && !metricName.equals(d.getMetricName())) {
                continue;
            }
            if (dimensions != null && !dimensions.isEmpty() && !matchesDimensions(d.getDimensions(), dimensions)) {
                continue;
            }
            String identity = d.getNamespace() + "::" + d.getMetricName() + "::" + buildDimKey(d.getDimensions());
            deduped.putIfAbsent(identity, new MetricIdentity(d.getNamespace(), d.getMetricName(), d.getDimensions()));
        }
        return new ArrayList<>(deduped.values());
    }

    public record Datapoint(Instant timestamp, double sampleCount, double sum,
                             double average, double minimum, double maximum, String unit) {}

    public record MetricStat(
            String namespace,
            String metricName,
            List<Dimension> dimensions,
            int period,
            String stat,
            String unit
    ) {}

    public record MetricDataQuery(
            String id,
            MetricStat metricStat,
            String expression,
            String label,
            boolean returnData
    ) {}

    public record MetricDataResult(
            String id,
            String label,
            List<Instant> timestamps,
            List<Double> values,
            String statusCode
    ) {}

    public List<Datapoint> getMetricStatistics(String namespace, String metricName,
                                                List<Dimension> dimensions,
                                                Instant startTime, Instant endTime,
                                                int periodSeconds,
                                                List<String> statistics,
                                                String unit, String region) {
        String dimKey = dimensions != null ? buildDimKey(dimensions) : "";
        String prefix = region + "::" + namespace + "::" + metricName + "::" + dimKey + "::";

        long startEpoch = startTime != null ? startTime.getEpochSecond() : 0;
        long endEpoch = endTime != null ? endTime.getEpochSecond() : Long.MAX_VALUE;

        List<MetricDatum> matching = metricStore.scan(k -> {
            if (!k.startsWith(prefix)) return false;
            // Extract timestamp from key segment
            String[] parts = k.split("::");
            if (parts.length < 6) return false;
            try {
                long ts = Long.parseLong(parts[parts.length - 2]);
                return ts >= startEpoch && ts <= endEpoch;
            } catch (NumberFormatException e) {
                return false;
            }
        });

        if (unit != null && !unit.isBlank() && !"None".equals(unit)) {
            matching = matching.stream()
                    .filter(d -> unit.equals(d.getUnit()))
                    .collect(Collectors.toList());
        }

        // Group by period bucket
        Map<Long, List<MetricDatum>> buckets = new LinkedHashMap<>();
        for (MetricDatum d : matching) {
            long bucket = (d.getTimestamp() / periodSeconds) * periodSeconds;
            buckets.computeIfAbsent(bucket, k -> new ArrayList<>()).add(d);
        }

        List<Datapoint> result = new ArrayList<>();
        for (Map.Entry<Long, List<MetricDatum>> entry : buckets.entrySet()) {
            List<MetricDatum> group = entry.getValue();
            double sc = group.stream().mapToDouble(MetricDatum::getSampleCount).sum();
            double sum = group.stream().mapToDouble(MetricDatum::getSum).sum();
            double min = group.stream().mapToDouble(MetricDatum::getMinimum).min().orElse(0);
            double max = group.stream().mapToDouble(MetricDatum::getMaximum).max().orElse(0);
            double avg = sc > 0 ? sum / sc : 0;
            String resolvedUnit = group.stream()
                    .map(MetricDatum::getUnit)
                    .filter(u -> u != null && !u.isBlank())
                    .findFirst().orElse("None");
            result.add(new Datapoint(
                    Instant.ofEpochSecond(entry.getKey()),
                    sc, sum, avg, min, max, resolvedUnit
            ));
        }
        result.sort(Comparator.comparing(Datapoint::timestamp));
        return result;
    }

    public List<MetricDataResult> getMetricData(
            List<MetricDataQuery> queries,
            Instant startTime,
            Instant endTime,
            String region) {

        List<MetricDataResult> results = new ArrayList<>();

        for (MetricDataQuery query : queries) {
            if (!query.returnData()) {
                continue;
            }
            if (query.metricStat() != null) {
                MetricStat stat = query.metricStat();
                int period = stat.period() > 0 ? stat.period() : 60;

                List<Datapoint> datapoints = getMetricStatistics(
                        stat.namespace(), stat.metricName(), stat.dimensions(),
                        startTime, endTime, period,
                        List.of(stat.stat()), stat.unit(), region);

                List<Instant> timestamps = new ArrayList<>();
                List<Double> values = new ArrayList<>();
                for (Datapoint dp : datapoints) {
                    timestamps.add(dp.timestamp());
                    values.add(resolveStatValue(dp, stat.stat()));
                }

                String label = query.label() != null ? query.label() : stat.metricName();
                results.add(new MetricDataResult(query.id(), label, timestamps, values, "Complete"));
            }
            // Expression-based queries are out of scope for this implementation
        }
        return results;
    }

    /** Shared with {@link AlarmEvaluator}, which resolves the same statistic against
     * freshly-fetched datapoints when evaluating an alarm's threshold. */
    public static double resolveStatValue(Datapoint dp, String stat) {
        return switch (stat) {
            case "Average" -> dp.average();
            case "Sum" -> dp.sum();
            case "Minimum" -> dp.minimum();
            case "Maximum" -> dp.maximum();
            case "SampleCount" -> dp.sampleCount();
            default -> {
                if (stat.startsWith("p")) yield dp.maximum();
                else yield dp.average();
            }
        };
    }

    /**
     * Creates or replaces a metric alarm. An update keeps the alarm's state and tags, as AWS does:
     * the definition changes, the next evaluation moves the state if the new one disagrees, and
     * {@code Tags} apply on create only.
     */
    public synchronized void putMetricAlarm(MetricAlarm alarm, String region) {
        String key = region + "::" + alarm.getAlarmName();
        Alarm previous = alarmStore.get(key).orElse(null);
        if (previous instanceof CompositeAlarm) {
            throw new AwsException("ValidationError",
                    "An alarm named " + alarm.getAlarmName() + " already exists as a composite alarm.", 400);
        }
        if (previous != null) {
            alarm.setStateValue(previous.getStateValue());
            alarm.setStateReason(previous.getStateReason());
            alarm.setStateReasonData(previous.getStateReasonData());
            alarm.setStateUpdatedTimestamp(previous.getStateUpdatedTimestamp());
            alarm.setStateTransitionedTimestamp(previous.getStateTransitionedTimestamp());
            alarm.setTags(previous.getTags());
        } else if (alarm.getStateTransitionedTimestamp() == 0) {
            alarm.setStateTransitionedTimestamp(alarm.getStateUpdatedTimestamp());
        }
        if (alarm.getAlarmArn() == null) {
            alarm.setAlarmArn(regionResolver.buildArn("cloudwatch", region, "alarm:" + alarm.getAlarmName()));
        }
        alarm.setRegion(region);
        alarm.setAlarmConfigurationUpdatedTimestamp(Instant.now().getEpochSecond());
        alarmStore.put(key, alarm);
        LOG.infov("PutMetricAlarm: {0} in {1}", alarm.getAlarmName(), region);
    }

    /**
     * Creates or replaces a composite alarm. Every alarm its rule names must already exist in the
     * region. A new alarm is evaluated at once. An update keeps the alarm's state, reason and
     * tags (AWS ignores {@code Tags} on update), then is evaluated against the new rule and
     * changes state only if the result differs.
     */
    public void putCompositeAlarm(CompositeAlarm alarm, String region) {
        List<AlarmSnsNotifier.Notification> notifications = new ArrayList<>();
        synchronized (this) {
            String name = alarm.getAlarmName();
            requireAlarmName(name);
            CompositeAlarmRule rule = CompositeAlarmRule.parse(alarm.getAlarmRule());
            String key = region + "::" + name;
            Alarm previous = alarmStore.get(key).orElse(null);
            if (previous instanceof MetricAlarm) {
                throw new AwsException("ValidationError",
                        "An alarm named " + name + " already exists as a metric alarm.", 400);
            }
            AlarmIndex alarms = alarmIndex(region);
            List<String> missing = rule.references().stream()
                    .filter(reference -> alarms.resolve(reference) == null)
                    .map(reference -> AwsArnUtils.isArn(reference) ? reference
                            : regionResolver.buildArn("cloudwatch", region, "alarm:" + reference))
                    .toList();
            if (!missing.isEmpty()) {
                throw new AwsException("ValidationError", "Could not save the composite alarm as alarms "
                        + missing + " in the alarm rule do not exist", 400);
            }
            if (rule.references().stream().map(alarms::resolve).distinct().count() > MAX_CHILD_ALARMS) {
                throw new AwsException("ValidationError", "Error in AlarmRule [the rule references more than "
                        + MAX_CHILD_ALARMS + " alarms]", 400);
            }
            String outcome;
            if (previous instanceof CompositeAlarm existing) {
                alarm.setAlarmArn(existing.getAlarmArn());
                alarm.setStateValue(existing.getStateValue());
                alarm.setStateReason(existing.getStateReason());
                alarm.setStateReasonData(existing.getStateReasonData());
                alarm.setStateUpdatedTimestamp(existing.getStateUpdatedTimestamp());
                alarm.setStateTransitionedTimestamp(existing.getStateTransitionedTimestamp());
                alarm.setTags(existing.getTags());
                alarm.setChangedChildren(existing.getChangedChildren());
                outcome = " was updated and its new alarm rule evaluates to ";
            } else {
                alarm.setAlarmArn(regionResolver.buildArn("cloudwatch", region, "alarm:" + name));
                outcome = " was created and its alarm rule evaluates to ";
            }
            alarm.setRegion(region);
            alarm.setAlarmConfigurationUpdatedTimestamp(Instant.now().getEpochSecond());
            String oldState = alarm.getStateValue();
            String newState = evaluate(rule, alarms);
            boolean changed = !newState.equals(oldState);
            if (changed) {
                transition(alarm, newState, alarm.getAlarmArn() + outcome + newState,
                        triggeringAlarms(rule, alarms, newState, null));
            }
            alarmStore.put(key, alarm);
            LOG.infov("PutCompositeAlarm: {0} in {1}", name, region);
            if (changed) {
                notifications.addAll(prepareTransition(alarm, oldState));
                propagate(alarm, region, notifications);
            }
        }
        publish(notifications);
    }

    /** Every stored metric alarm, across all regions. Used by the background {@link AlarmEvaluator}
     * tick, which has no per-request region to scope a lookup to. */
    public List<MetricAlarm> allAlarms() {
        return alarmStore.scan(k -> true).stream()
                .filter(MetricAlarm.class::isInstance).map(MetricAlarm.class::cast).toList();
    }

    public List<MetricAlarm> describeAlarms(List<String> alarmNames, String alarmNamePrefix, String region) {
        return matchingAlarms(alarmNames, alarmNamePrefix, region, MetricAlarm.class);
    }

    /** What DescribeAlarms answers: its metric alarms and its composite alarms, either list possibly empty. */
    public record DescribedAlarms(List<MetricAlarm> metricAlarms, List<CompositeAlarm> compositeAlarms) {}

    /** The filters of a DescribeAlarms request, each null or empty when the request leaves it out. */
    public record AlarmFilter(List<String> alarmNames, String alarmNamePrefix, List<String> alarmTypes,
                              String stateValue, String actionPrefix, String childrenOf, String parentsOf) {}

    /**
     * DescribeAlarms. Without {@code alarmTypes} it returns metric alarms only, as AWS does;
     * {@code stateValue} and {@code actionPrefix} narrow either kind to alarms in that state, or
     * with an action of any state starting with that prefix.
     * {@code childrenOf} names a composite alarm and returns the alarms its rule names;
     * {@code parentsOf} names any alarm and returns the composite alarms whose rules name it.
     * Neither returns the alarm it names, even one whose rule names itself. Either one excludes
     * every other filter. Full alarms are returned in both cases, where AWS returns only their
     * names, ARNs and states.
     */
    public synchronized DescribedAlarms describeAlarms(AlarmFilter filter, String region) {
        validateAlarmTypes(filter.alarmTypes());
        if (filter.childrenOf() != null || filter.parentsOf() != null) {
            if ((filter.childrenOf() != null && filter.parentsOf() != null) || !filter.alarmNames().isEmpty()
                    || filter.alarmNamePrefix() != null || !filter.alarmTypes().isEmpty()
                    || filter.stateValue() != null || filter.actionPrefix() != null) {
                throw new AwsException("ValidationError", "ChildrenOfAlarmName and ParentsOfAlarmName cannot be "
                        + "combined with each other or with any parameter except MaxRecords and NextToken.", 400);
            }
            return filter.childrenOf() != null
                    ? childrenOf(filter.childrenOf(), region) : parentsOf(filter.parentsOf(), region);
        }
        List<String> types = filter.alarmTypes();
        List<MetricAlarm> metricAlarms = types.isEmpty() || types.contains("MetricAlarm")
                ? describeAlarms(filter.alarmNames(), filter.alarmNamePrefix(), region) : List.of();
        List<CompositeAlarm> compositeAlarms = types.contains("CompositeAlarm")
                ? describeCompositeAlarms(filter.alarmNames(), filter.alarmNamePrefix(), region) : List.of();
        return new DescribedAlarms(
                metricAlarms.stream().filter(alarm -> matches(alarm, filter)).toList(),
                compositeAlarms.stream().filter(alarm -> matches(alarm, filter)).toList());
    }

    private static boolean matches(Alarm alarm, AlarmFilter filter) {
        String prefix = filter.actionPrefix();
        return (filter.stateValue() == null || filter.stateValue().equals(alarm.getStateValue()))
                && (prefix == null || Stream.of(alarm.getAlarmActions(), alarm.getOkActions(),
                        alarm.getInsufficientDataActions()).flatMap(List::stream).anyMatch(a -> a.startsWith(prefix)));
    }

    private DescribedAlarms childrenOf(String name, String region) {
        AlarmIndex alarms = alarmIndex(region);
        Alarm named = alarms.byName().get(name);
        CompositeAlarmRule rule = named instanceof CompositeAlarm composite ? parseStored(composite) : null;
        Set<Alarm> children = new LinkedHashSet<>();
        if (rule != null) {
            rule.references().stream().map(alarms::resolve).filter(Objects::nonNull).forEach(children::add);
            children.remove(named);
        }
        return new DescribedAlarms(
                children.stream().filter(MetricAlarm.class::isInstance).map(MetricAlarm.class::cast).toList(),
                children.stream().filter(CompositeAlarm.class::isInstance).map(CompositeAlarm.class::cast).toList());
    }

    private DescribedAlarms parentsOf(String name, String region) {
        AlarmIndex alarms = alarmIndex(region);
        Alarm child = alarms.byName().get(name);
        List<CompositeAlarm> parents = new ArrayList<>();
        if (child != null) {
            for (CompositeAlarm composite : alarms.composites()) {
                CompositeAlarmRule rule = composite == child ? null : parseStored(composite);
                if (rule != null && rule.references().stream().anyMatch(reference -> alarms.resolve(reference) == child)) {
                    parents.add(composite);
                }
            }
        }
        return new DescribedAlarms(List.of(), parents);
    }

    public List<CompositeAlarm> describeCompositeAlarms(List<String> alarmNames, String alarmNamePrefix,
                                                        String region) {
        return matchingAlarms(alarmNames, alarmNamePrefix, region, CompositeAlarm.class);
    }

    private <T extends Alarm> List<T> matchingAlarms(List<String> alarmNames, String alarmNamePrefix,
                                                     String region, Class<T> type) {
        String prefix = region + "::";
        List<T> all = alarmStore.scan(k -> k.startsWith(prefix)).stream()
                .filter(type::isInstance).map(type::cast).toList();

        if (alarmNames != null && !alarmNames.isEmpty()) {
            return all.stream().filter(a -> alarmNames.contains(a.getAlarmName())).toList();
        }
        if (alarmNamePrefix != null && !alarmNamePrefix.isBlank()) {
            return all.stream().filter(a -> a.getAlarmName().startsWith(alarmNamePrefix)).toList();
        }
        return all;
    }

    /** Rejects a {@code DescribeAlarms} {@code AlarmTypes} value the API does not define. */
    private static void validateAlarmTypes(List<String> alarmTypes) {
        for (String type : alarmTypes) {
            if (!ALARM_TYPES.contains(type)) {
                throw new AwsException("ValidationError", "1 validation error detected: Value '" + alarmTypes
                        + "' at 'alarmTypes' failed to satisfy constraint: Member must satisfy constraint: "
                        + "[Member must satisfy enum value set: [CompositeAlarm, LogAlarm, MetricAlarm]]", 400);
            }
        }
    }

    /**
     * Deletes the named alarms, or none of them. AWS refuses a request that names more than one
     * composite alarm, or any alarm a composite alarm's rule references, even when that composite
     * alarm is deleted by the same request. Names that match no alarm are ignored.
     */
    public synchronized void deleteAlarms(List<String> alarmNames, String region) {
        AlarmIndex alarms = alarmIndex(region);
        List<Alarm> existing = alarmNames.stream().distinct()
                .map(alarms.byName()::get).filter(Objects::nonNull).toList();
        long composites = existing.stream().filter(CompositeAlarm.class::isInstance).count();
        if (composites > 1) {
            throw new AwsException("ValidationError", "Cannot delete [" + composites
                    + "] Composite Alarms as only one composite alarm delete is allowed in single request", 400);
        }
        Set<String> referenced = new HashSet<>();
        for (CompositeAlarm composite : alarms.composites()) {
            CompositeAlarmRule rule = parseStored(composite);
            if (rule != null) {
                rule.references().stream().map(alarms::resolve).filter(Objects::nonNull)
                        .map(Alarm::getAlarmArn).forEach(referenced::add);
            }
        }
        List<String> blocked = existing.stream().filter(alarm -> referenced.contains(alarm.getAlarmArn()))
                .map(Alarm::getAlarmName).toList();
        if (!blocked.isEmpty()) {
            throw new AwsException("ValidationError", "Cannot delete " + String.join(",", blocked)
                    + " as there are composite alarm(s) depending on " + (blocked.size() == 1 ? "it." : "them."), 400);
        }
        for (String name : alarmNames) {
            alarmStore.delete(region + "::" + name);
        }
        LOG.infov("Deleted alarms: {0} in {1}", alarmNames, region);
    }

    public void setAlarmState(String alarmName, String stateValue, String stateReason, String stateReasonData, String region) {
        List<AlarmSnsNotifier.Notification> notifications = new ArrayList<>();
        synchronized (this) {
            String key = region + "::" + alarmName;
            Alarm alarm = alarmStore.get(key)
                    .orElseThrow(() -> new AwsException("ResourceNotFound", "Alarm not found: " + alarmName, 404));

            String oldState = alarm.getStateValue();
            long now = Instant.now().getEpochSecond();
            alarm.setStateValue(stateValue);
            alarm.setStateReason(stateReason);
            alarm.setStateReasonData(stateReasonData);
            alarm.setStateUpdatedTimestamp(now);
            boolean changed = !Objects.equals(oldState, stateValue);
            if (changed) {
                alarm.setStateTransitionedTimestamp(now);
                if (alarm instanceof CompositeAlarm composite) {
                    composite.getChangedChildren().clear();
                }
            }

            alarmStore.put(key, alarm);
            LOG.infov("SetAlarmState: {0} -> {1}", alarmName, stateValue);
            if (changed) {
                notifications.addAll(prepareTransition(alarm, oldState));
                propagate(alarm, region, notifications);
            }
        }
        publish(notifications);
    }

    /**
     * Re-evaluates the composite alarms above {@code changed}, whose state just changed. Each is
     * evaluated at most once and only after its own composite children, so it sees their final
     * states, and only when one of its children changed state during this pass. A composite alarm
     * on a cycle is not evaluated: AWS stops evaluation where it detects a cycle.
     */
    private void propagate(Alarm changed, String region, List<AlarmSnsNotifier.Notification> notifications) {
        AlarmIndex alarms = alarmIndex(region);
        Map<String, CompositeAlarmRule> rules = new HashMap<>();
        Map<String, Set<String>> parents = new HashMap<>();
        Map<String, Set<String>> compositeChildren = new HashMap<>();
        for (CompositeAlarm composite : alarms.composites()) {
            CompositeAlarmRule rule = parseStored(composite);
            if (rule == null) {
                continue;
            }
            rules.put(composite.getAlarmArn(), rule);
            for (String reference : rule.references()) {
                Alarm child = alarms.resolve(reference);
                if (child == null) {
                    continue;
                }
                parents.computeIfAbsent(child.getAlarmArn(), arn -> new LinkedHashSet<>()).add(composite.getAlarmArn());
                if (child instanceof CompositeAlarm) {
                    compositeChildren.computeIfAbsent(composite.getAlarmArn(), arn -> new LinkedHashSet<>())
                            .add(child.getAlarmArn());
                }
            }
        }

        Set<String> ancestors = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>(parents.getOrDefault(changed.getAlarmArn(), Set.of()));
        while (!pending.isEmpty()) {
            String next = pending.removeFirst();
            if (ancestors.add(next)) {
                pending.addAll(parents.getOrDefault(next, Set.of()));
            }
        }
        Set<String> evaluable = ancestors.stream().filter(arn -> !onCycle(arn, compositeChildren))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Integer> unevaluatedChildren = new HashMap<>();
        Deque<String> ready = new ArrayDeque<>();
        for (String arn : evaluable) {
            int count = (int) compositeChildren.getOrDefault(arn, Set.of()).stream().filter(evaluable::contains).count();
            unevaluatedChildren.put(arn, count);
            if (count == 0) {
                ready.add(arn);
            }
        }

        Set<CompositeAlarm> touched = new LinkedHashSet<>();
        Set<String> transitioned = new HashSet<>(Set.of(changed.getAlarmArn()));
        recordChange(changed, parents, alarms, touched);
        while (!ready.isEmpty()) {
            String arn = ready.removeFirst();
            CompositeAlarm composite = (CompositeAlarm) alarms.withArn(arn);
            Alarm trigger = latestChange(composite, transitioned, alarms);
            String oldState = composite.getStateValue();
            String newState = trigger == null ? oldState : evaluate(rules.get(arn), alarms);
            if (!newState.equals(oldState)) {
                String reasonData = triggeringAlarms(rules.get(arn), alarms, newState, composite.getChangedChildren());
                transition(composite, newState, trigger.getAlarmArn() + " transitioned to " + trigger.getStateValue()
                        + " at " + REASON_TIME.format(Instant.ofEpochSecond(trigger.lastTransitionTimestamp())),
                        reasonData);
                touched.add(composite);
                notifications.addAll(prepareTransition(composite, oldState));
                transitioned.add(arn);
                recordChange(composite, parents, alarms, touched);
            }
            for (String parent : parents.getOrDefault(arn, Set.of())) {
                if (evaluable.contains(parent) && unevaluatedChildren.merge(parent, -1, Integer::sum) == 0) {
                    ready.add(parent);
                }
            }
        }
        for (CompositeAlarm composite : touched) {
            alarmStore.put(region + "::" + composite.getAlarmName(), composite);
        }
    }

    /** Notes on each composite parent of {@code changed} that this child changed state. */
    private static void recordChange(Alarm changed, Map<String, Set<String>> parents, AlarmIndex alarms,
                                     Set<CompositeAlarm> touched) {
        for (String parentArn : parents.getOrDefault(changed.getAlarmArn(), Set.of())) {
            CompositeAlarm parent = (CompositeAlarm) alarms.withArn(parentArn);
            parent.getChangedChildren().remove(changed.getAlarmArn());
            parent.getChangedChildren().add(changed.getAlarmArn());
            touched.add(parent);
        }
    }

    /** The child of {@code composite} that most recently changed state during this pass, if any. */
    private static Alarm latestChange(CompositeAlarm composite, Set<String> transitioned, AlarmIndex alarms) {
        List<String> changedChildren = composite.getChangedChildren();
        for (int index = changedChildren.size() - 1; index >= 0; index--) {
            if (transitioned.contains(changedChildren.get(index))) {
                return alarms.withArn(changedChildren.get(index));
            }
        }
        return null;
    }

    private static boolean onCycle(String arn, Map<String, Set<String>> compositeChildren) {
        Deque<String> pending = new ArrayDeque<>(compositeChildren.getOrDefault(arn, Set.of()));
        Set<String> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            String next = pending.removeFirst();
            if (next.equals(arn)) {
                return true;
            }
            if (seen.add(next)) {
                pending.addAll(compositeChildren.getOrDefault(next, Set.of()));
            }
        }
        return false;
    }

    private static String evaluate(CompositeAlarmRule rule, AlarmIndex alarms) {
        return rule.evaluate(stateLookup(alarms)) ? "ALARM" : "OK";
    }

    private static Function<String, String> stateLookup(AlarmIndex alarms) {
        return reference -> {
            Alarm alarm = alarms.resolve(reference);
            return alarm == null ? null : alarm.getStateValue();
        };
    }

    /**
     * The {@code StateReasonData} CloudWatch writes on a composite alarm's transition: the child
     * alarms whose own condition agrees with the new state, newest state first. On a create or
     * update that is every such child; on a child's transition only those among
     * {@code changedChildren}, the children that changed since the alarm last changed state.
     * Only the newest children that fit within the documented length are listed.
     */
    private static String triggeringAlarms(CompositeAlarmRule rule, AlarmIndex alarms, String state,
                                           List<String> changedChildren) {
        Set<String> agreeing = rule.agreeing(stateLookup(alarms), "ALARM".equals(state)).stream()
                .map(alarms::resolve).filter(Objects::nonNull).map(Alarm::getAlarmArn)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<Alarm> triggering = new ArrayList<>();
        if (changedChildren == null) {
            agreeing.stream().map(alarms::withArn)
                    .sorted(Comparator.comparingLong(Alarm::lastTransitionTimestamp).reversed())
                    .forEach(triggering::add);
        } else {
            for (int index = changedChildren.size() - 1; index >= 0; index--) {
                if (agreeing.contains(changedChildren.get(index))) {
                    triggering.add(alarms.withArn(changedChildren.get(index)));
                }
            }
        }
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        ArrayNode list = data.putArray("triggeringAlarms");
        for (Alarm child : triggering) {
            ObjectNode entry = list.addObject();
            entry.put("arn", child.getAlarmArn());
            entry.putObject("state").put("value", child.getStateValue())
                    .put("timestamp", REASON_DATA_TIME.format(Instant.ofEpochSecond(child.lastTransitionTimestamp())));
            if (data.toString().length() > MAX_STATE_REASON_DATA) {
                list.remove(list.size() - 1);
                break;
            }
        }
        return data.toString();
    }

    private static void transition(Alarm alarm, String state, String reason, String reasonData) {
        long now = Instant.now().getEpochSecond();
        alarm.setStateValue(state);
        alarm.setStateReason(reason);
        alarm.setStateReasonData(reasonData);
        alarm.setStateUpdatedTimestamp(now);
        alarm.setStateTransitionedTimestamp(now);
        if (alarm instanceof CompositeAlarm composite) {
            composite.getChangedChildren().clear();
        }
    }

    /** A rule stored before it was validated as strictly as it is now is skipped rather than fatal. */
    private static CompositeAlarmRule parseStored(CompositeAlarm composite) {
        try {
            return CompositeAlarmRule.parse(composite.getAlarmRule());
        } catch (AwsException e) {
            LOG.warnv("Not evaluating composite alarm {0}: its stored rule does not parse: {1}",
                    composite.getAlarmName(), e.getMessage());
            return null;
        }
    }

    private static void requireAlarmName(String name) {
        if (name == null) {
            throw new AwsException("ValidationError", "1 validation error detected: Value null at 'alarmName' "
                    + "failed to satisfy constraint: Member must not be null", 400);
        }
        if (name.isEmpty() || name.length() > 255) {
            throw new AwsException("ValidationError", "1 validation error detected: Value '" + name
                    + "' at 'alarmName' failed to satisfy constraint: Member must have length "
                    + (name.isEmpty() ? "greater than or equal to 1" : "less than or equal to 255"), 400);
        }
    }

    private AlarmIndex alarmIndex(String region) {
        Map<String, Alarm> byName = new LinkedHashMap<>();
        Map<String, Alarm> byArn = new HashMap<>();
        for (Alarm alarm : alarmStore.scan(k -> k.startsWith(region + "::"))) {
            byName.put(alarm.getAlarmName(), alarm);
            if (alarm.getAlarmArn() != null) {
                byArn.put(alarm.getAlarmArn(), alarm);
            }
        }
        return new AlarmIndex(byName, byArn);
    }

    /** A region's alarms by name and by ARN, the two ways a composite alarm's rule names one. */
    private record AlarmIndex(Map<String, Alarm> byName, Map<String, Alarm> byArn) {

        Alarm resolve(String reference) {
            return AwsArnUtils.isArn(reference) ? byArn.get(reference) : byName.get(reference);
        }

        Alarm withArn(String arn) {
            return byArn.get(arn);
        }

        List<CompositeAlarm> composites() {
            return byName.values().stream()
                    .filter(CompositeAlarm.class::isInstance).map(CompositeAlarm.class::cast).toList();
        }
    }

    private List<AlarmSnsNotifier.Notification> prepareTransition(Alarm alarm, String oldState) {
        return alarmSnsNotifier == null ? List.of() : alarmSnsNotifier.prepareTransition(alarm, oldState);
    }

    /** Publishes outside the service monitor, so a slow topic never holds up other alarm changes. */
    private void publish(List<AlarmSnsNotifier.Notification> notifications) {
        if (alarmSnsNotifier != null && !notifications.isEmpty()) {
            alarmSnsNotifier.publish(notifications);
        }
    }

    /**
     * Resolves the alarm an ARN names, or reports that nothing does.
     *
     * <p>CloudWatch's three tag operations each declare {@code ResourceNotFoundException},
     * which is a different shape from the {@code ResourceNotFound} that {@code SetAlarmState}
     * and {@code GetDashboard} declare; the SDK maps the two codes to two exception classes,
     * so the tag path uses the longer one rather than the code used elsewhere in this service.
     *
     * <p>The message names the ARN rather than asserting it was an alarm, because both tag
     * handlers route every ARN that is not a dashboard and not a metric stream here. That
     * includes kinds AWS considers taggable and Floci does not serve, a Contributor Insights
     * {@code insight-rule/} ARN being the one AWS documents; reporting that no resource
     * matches the ARN is true of those, where "alarm not found" would not be.
     */
    private Alarm requireAlarm(String resourceArn, String region) {
        return alarmStore.scan(k -> k.startsWith(region + "::"))
                .stream()
                .filter(a -> a.getAlarmArn() != null && a.getAlarmArn().equals(resourceArn))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No CloudWatch resource matches the ARN " + resourceArn + ".", 404));
    }

    /** A copy, taken under the monitor that alarm changes hold, so a caller never reads a map mid-change. */
    public synchronized Map<String, String> listTagsForResource(String resourceArn, String region) {
        return new LinkedHashMap<>(requireAlarm(resourceArn, region).getTags());
    }

    public synchronized void tagResource(String resourceArn, Map<String, String> tags, String region) {
        Alarm alarm = requireAlarm(resourceArn, region);
        alarm.getTags().putAll(tags);
        alarmStore.put(region + "::" + alarm.getAlarmName(), alarm);
    }

    public synchronized void untagResource(String resourceArn, List<String> tagKeys, String region) {
        Alarm alarm = requireAlarm(resourceArn, region);
        tagKeys.forEach(alarm.getTags()::remove);
        alarmStore.put(region + "::" + alarm.getAlarmName(), alarm);
    }

    // ──────────────────────────── Helpers ────────────────────────────

    static String buildDimKey(List<Dimension> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) {
            return "";
        }
        return dimensions.stream()
                .sorted(Comparator.comparing(Dimension::name))
                .map(d -> d.name() + "=" + d.value())
                .collect(Collectors.joining(","));
    }

    private boolean matchesDimensions(List<Dimension> actual, List<Dimension> required) {
        String requiredKey = buildDimKey(required);
        String actualKey = buildDimKey(actual);
        return actualKey.contains(requiredKey);
    }
}
