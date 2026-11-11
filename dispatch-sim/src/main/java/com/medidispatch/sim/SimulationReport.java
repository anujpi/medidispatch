package com.medidispatch.sim;

import com.medidispatch.core.domain.RiskBand;

import java.time.Duration;
import java.util.Map;

/**
 * The results of one simulated run.
 *
 * @param simulated                  how much simulated time the report covers
 * @param readings                   vitals readings sent to the engine
 * @param ignoredReadings            readings the engine ignored (duplicates, late, unknown patient and so on)
 * @param byBand                     case statistics per urgency band; a band with no cases is left out
 * @param timeouts                   trips that timed out because the unit never answered
 * @param failures                   trips that failed (broke down, lost signal)
 * @param preemptions                ambulance trips taken away for a worse case
 * @param reassignedCases            cases that needed more than one ambulance
 * @param firstRespondersSent        first responders sent alongside an ambulance
 * @param firstRespondersFirst       first responders who reached the patient before the ambulance
 * @param averageLeadMinutes         how much earlier those first responders arrived, on average
 * @param casesOpenAtEnd             cases not closed when the run ended
 */
public record SimulationReport(Duration simulated,
                               int readings,
                               int ignoredReadings,
                               Map<RiskBand, BandStats> byBand,
                               int timeouts,
                               int failures,
                               int preemptions,
                               int reassignedCases,
                               int firstRespondersSent,
                               int firstRespondersFirst,
                               double averageLeadMinutes,
                               int casesOpenAtEnd) {

    /**
     * Case statistics for one urgency band. A case counts in the worst band it reached.
     *
     * @param cases               cases that reached this band
     * @param served              cases whose ambulance arrived
     * @param averageWaitMinutes  average time from alert to an ambulance being sent
     * @param maxWaitMinutes      longest such wait
     * @param averageResponseMinutes average time from alert to the ambulance arriving
     * @param maxResponseMinutes  longest such time
     */
    public record BandStats(int cases,
                            int served,
                            double averageWaitMinutes,
                            double maxWaitMinutes,
                            double averageResponseMinutes,
                            double maxResponseMinutes) {}

    /** Makes {@code byBand} an unmodifiable copy. */
    public SimulationReport {
        byBand = Map.copyOf(byBand);
    }

    /**
     * Counts every case in every band.
     *
     * @return the number of cases opened during the run
     */
    public int totalCases() {
        int total = 0;
        for (BandStats stats : byBand.values()) {
            total += stats.cases();
        }
        return total;
    }

    /**
     * The report as a readable table for the console.
     *
     * @return several lines of text
     */
    public String toText() {
        StringBuilder text = new StringBuilder();
        text.append(String.format("MediDispatch simulation, %d min simulated%n", simulated.toMinutes()));
        text.append(String.format("Readings: %d (%d ignored)%n%n", readings, ignoredReadings));
        text.append(String.format("%-9s %6s %7s %10s %10s %13s %13s%n",
                "Band", "Cases", "Served", "Avg wait", "Max wait", "Avg response", "Max response"));
        RiskBand[] worstFirst = {RiskBand.CRITICAL, RiskBand.HIGH, RiskBand.MEDIUM, RiskBand.LOW};
        for (RiskBand band : worstFirst) {
            BandStats s = byBand.get(band);
            if (s == null) {
                continue;
            }
            text.append(String.format("%-9s %6d %7d %6.1f min %6.1f min %9.1f min %9.1f min%n",
                    band, s.cases(), s.served(), s.averageWaitMinutes(), s.maxWaitMinutes(),
                    s.averageResponseMinutes(), s.maxResponseMinutes()));
        }
        text.append(String.format("%nTimeouts %d, failures %d, preemptions %d, cases reassigned %d%n",
                timeouts, failures, preemptions, reassignedCases));
        text.append(String.format("First responders sent %d, arrived before the ambulance %d, average lead %.1f min%n",
                firstRespondersSent, firstRespondersFirst, averageLeadMinutes));
        text.append(String.format("Cases still open at the end: %d%n", casesOpenAtEnd));
        return text.toString();
    }
}
