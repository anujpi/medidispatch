package com.medidispatch.sim;

/**
 * Runs one simulation with the default city and prints the report.
 * <p>
 * Optional first argument: the seed, for example {@code 7}, to get a different but repeatable city.
 */
public final class SimulatorMain {

    private SimulatorMain() {}

    /**
     * Runs the simulation.
     *
     * @param args optional: the seed
     */
    public static void main(String[] args) {
        SimulationConfig config = SimulationConfig.defaults();
        if (args.length > 0) {
            long seed = Long.parseLong(args[0]);
            config = new SimulationConfig(seed, config.duration(), config.tick(), config.patientCount(),
                    config.deteriorationChance(), config.alsCount(), config.blsCount(), config.nurseCount(),
                    config.doctorCount(), config.noAckChance(), config.breakdownChance(), config.signalLossChance());
        }
        long started = System.currentTimeMillis();
        SimulationReport report = new Simulation(config).run();
        System.out.print(report.toText());
        System.out.printf("(seed %d, ran in %d ms)%n", config.seed(), System.currentTimeMillis() - started);
    }
}
