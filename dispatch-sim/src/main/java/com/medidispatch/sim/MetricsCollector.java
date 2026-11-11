package com.medidispatch.sim;

import com.medidispatch.core.domain.AssignmentRole;
import com.medidispatch.core.domain.AssignmentStatus;
import com.medidispatch.core.domain.Case;
import com.medidispatch.core.domain.CaseStatus;
import com.medidispatch.core.domain.RiskBand;
import com.medidispatch.core.engine.ProcessingResult;
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
 * Almost everything comes from the engine's events (the same events a dashboard would see). The one thing the
 * events do not carry is a case's band, so the collector also remembers the worst band each case reached, from
 * the results of the readings.
 */
final class MetricsCollector {

    private int readings;
    private int ignoredReadings;
    private final Map<String, RiskBand> worstBand = new HashMap<>();

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
     * Builds the report from everything the engine announced.
     *
     * @param events    every event of the run, in order
     * @param simulated how much simulated time the run covered
     * @return the report
     */
    SimulationReport report(List<DomainEvent> events, Duration simulated) {
        Map<String, Instant> alertedAt = new HashMap<>();
        Set<String> closed = new HashSet<>();
        Map<String, AssignmentRole> roleOfTrip = new HashMap<>();
        Map<String, Instant> ambulanceSentAt = new HashMap<>();
        Map<String, Instant> ambulanceArrivedAt = new HashMap<>();
        Map<String, Instant> responderArrivedAt = new HashMap<>();
        Map<String, Integer> ambulanceTrips = new HashMap<>();
        int timeouts = 0;
        int failures = 0;
        int preemptions = 0;
        int respondersSent = 0;

        for (DomainEvent e : events) {
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
                    preemptions++;                            // only preemption cancels an ambulance here
                }
            }
        }

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

        int reassigned = 0;
        for (int trips : ambulanceTrips.values()) {
            if (trips > 1) {
                reassigned++;
            }
        }

        return new SimulationReport(simulated, readings, ignoredReadings, byBand, timeouts, failures, preemptions,
                reassigned, respondersSent, leads.size(), average(leads), alertedAt.size() - closed.size());
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
