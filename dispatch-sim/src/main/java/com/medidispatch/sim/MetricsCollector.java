package com.medidispatch.sim;

import com.medidispatch.core.domain.AssignmentRole;
import com.medidispatch.core.domain.AssignmentStatus;
import com.medidispatch.core.domain.Case;
import com.medidispatch.core.domain.CaseStatus;
import com.medidispatch.core.domain.RiskBand;
import com.medidispatch.core.engine.ProcessingResult;
import com.medidispatch.core.event.AssignmentRoleChanged;
import com.medidispatch.core.event.AssignmentStatusChanged;
import com.medidispatch.core.event.CaseStatusChanged;
import com.medidispatch.core.event.DomainEvent;
import com.medidispatch.core.event.UnitAssigned;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns what happened during a run into a {@link SimulationReport}.
 * <p>
 * It is fed as things happen: every reading result ({@link #onReading(ProcessingResult)}) and every engine event
 * ({@link #onEvent(DomainEvent)}), each exactly once. It keeps running notes instead of re-reading the whole
 * history, so a run can go on for days without the report getting slower to build. The one thing the events do
 * not carry is a case's band, so the worst band each case reached comes from the reading results.
 */
final class MetricsCollector {

    private int readings;
    private int ignoredReadings;
    private final Map<String, RiskBand> worstBand = new HashMap<>();

    // running notes, filled in by onEvent
    private final Map<String, Instant> alertedAt = new HashMap<>();
    private final Set<String> closed = new HashSet<>();
    private final Map<String, AssignmentRole> roleOfTrip = new HashMap<>();
    private final Map<String, Instant> ambulanceSentAt = new HashMap<>();
    private final Map<String, Instant> ambulanceArrivedAt = new HashMap<>();
    private final Map<String, Instant> responderArrivedAt = new HashMap<>();
    private final Map<String, Integer> ambulanceTrips = new HashMap<>();
    private final Map<String, Integer> upgrades = new HashMap<>();
    private int timeouts;
    private int failures;
    private int preemptions;
    private int respondersSent;

    /**
     * Records what happened to one reading.
     *
     * @param result the engine's answer to the reading
     */
    void onReading(ProcessingResult result) {
        readings++;
        if (!result.isAccepted()) {
            ignoredReadings++;
            return;
        }
        if (result.currentCase().isPresent()) {
            Case c = result.currentCase().get();
            RiskBand seen = worstBand.get(c.id());
            if (seen == null || c.band().compareTo(seen) > 0) {
                worstBand.put(c.id(), c.band());
            }
        }
    }

    /**
     * Records one engine event. Each event must be passed exactly once, in the order it was published.
     *
     * @param e what the engine announced
     */
    void onEvent(DomainEvent e) {
        if (e instanceof CaseStatusChanged changed) {
            if (changed.to() == CaseStatus.ALERTED) {
                alertedAt.putIfAbsent(changed.caseId(), changed.occurredAt());
            }
            if (changed.to() == CaseStatus.CLOSED) {
                closed.add(changed.caseId());
            }
        } else if (e instanceof UnitAssigned assigned) {
            roleOfTrip.put(assigned.assignmentId(), assigned.role());
            if (assigned.role() == AssignmentRole.PRIMARY) {
                ambulanceSentAt.putIfAbsent(assigned.caseId(), assigned.occurredAt());
                ambulanceTrips.put(assigned.caseId(), ambulanceTrips.getOrDefault(assigned.caseId(), 0) + 1);
            } else {
                respondersSent++;
            }
        } else if (e instanceof AssignmentStatusChanged changed) {
            AssignmentRole role = roleOfTrip.get(changed.assignmentId());
            if (changed.to() == AssignmentStatus.ARRIVED) {
                if (role == AssignmentRole.PRIMARY) {
                    ambulanceArrivedAt.putIfAbsent(changed.caseId(), changed.occurredAt());
                } else {
                    responderArrivedAt.putIfAbsent(changed.caseId(), changed.occurredAt());
                }
            } else if (changed.to() == AssignmentStatus.TIMED_OUT) {
                timeouts++;
            } else if (changed.to() == AssignmentStatus.FAILED) {
                failures++;
            } else if (changed.to() == AssignmentStatus.CANCELLED && role == AssignmentRole.PRIMARY) {
                preemptions++;                                // only preemption cancels an ambulance here
            }
        } else if (e instanceof AssignmentRoleChanged changed) {
            roleOfTrip.put(changed.assignmentId(), changed.to());   // the unit is a first responder now
            upgrades.put(changed.caseId(), upgrades.getOrDefault(changed.caseId(), 0) + 1);
            respondersSent++;                                 // kept on the case as a first responder
        }
    }

    /**
     * Builds the report from the notes so far.
     *
     * @param simulated how much simulated time the run has covered
     * @return the report
     */
    SimulationReport report(Duration simulated) {
        // group each case's times by the worst band it reached
        Map<RiskBand, List<Double>> waits = new EnumMap<>(RiskBand.class);
        Map<RiskBand, List<Double>> responses = new EnumMap<>(RiskBand.class);
        Map<RiskBand, Integer> caseCounts = new EnumMap<>(RiskBand.class);
        for (Map.Entry<String, Instant> alert : alertedAt.entrySet()) {
            String caseId = alert.getKey();
            RiskBand band = worstBand.getOrDefault(caseId, RiskBand.MEDIUM);
            caseCounts.put(band, caseCounts.getOrDefault(band, 0) + 1);
            if (ambulanceSentAt.containsKey(caseId)) {
                add(waits, band, minutesBetween(alert.getValue(), ambulanceSentAt.get(caseId)));
            }
            if (ambulanceArrivedAt.containsKey(caseId)) {
                add(responses, band, minutesBetween(alert.getValue(), ambulanceArrivedAt.get(caseId)));
            }
        }
        Map<RiskBand, SimulationReport.BandStats> byBand = new EnumMap<>(RiskBand.class);
        for (Map.Entry<RiskBand, Integer> count : caseCounts.entrySet()) {
            List<Double> w = waits.getOrDefault(count.getKey(), List.of());
            List<Double> r = responses.getOrDefault(count.getKey(), List.of());
            byBand.put(count.getKey(), new SimulationReport.BandStats(count.getValue(), r.size(),
                    average(w), max(w), average(r), max(r)));
        }

        // first responders who beat the ambulance, and by how much
        List<Double> leads = new ArrayList<>();
        for (Map.Entry<String, Instant> responder : responderArrivedAt.entrySet()) {
            Instant ambulance = ambulanceArrivedAt.get(responder.getKey());
            if (ambulance != null && responder.getValue().isBefore(ambulance)) {
                leads.add(minutesBetween(responder.getValue(), ambulance));
            }
        }

        // a case was reassigned if it lost a unit; an upgrade to a better unit does not count as losing one
        int reassigned = 0;
        int upgraded = 0;
        for (Map.Entry<String, Integer> trips : ambulanceTrips.entrySet()) {
            int caseUpgrades = upgrades.getOrDefault(trips.getKey(), 0);
            upgraded += caseUpgrades;
            if (trips.getValue() - 1 - caseUpgrades > 0) {
                reassigned++;
            }
        }

        return new SimulationReport(simulated, readings, ignoredReadings, byBand, timeouts, failures, preemptions,
                reassigned, upgraded, respondersSent, leads.size(), average(leads), alertedAt.size() - closed.size());
    }

    private static void add(Map<RiskBand, List<Double>> map, RiskBand band, double value) {
        List<Double> values = map.get(band);
        if (values == null) {
            values = new ArrayList<>();
            map.put(band, values);
        }
        values.add(value);
    }

    private static double minutesBetween(Instant from, Instant to) {
        return Duration.between(from, to).toSeconds() / 60.0;
    }

    private static double average(List<Double> values) {
        if (values.isEmpty()) {
            return 0;
        }
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.size();
    }

    private static double max(List<Double> values) {
        double max = 0;
        for (double v : values) {
            max = Math.max(max, v);
        }
        return max;
    }
}
