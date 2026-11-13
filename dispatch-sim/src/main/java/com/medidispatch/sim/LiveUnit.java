package com.medidispatch.sim;

import com.medidispatch.core.domain.AssignmentRole;
import com.medidispatch.core.domain.AssignmentStatus;
import com.medidispatch.core.domain.Location;
import com.medidispatch.sim.SimulatedUnit.Behaviour;
import com.medidispatch.sim.SimulatedUnit.TickResult;
import com.medidispatch.sim.WorldSnapshot.UnitState;
import com.medidispatch.sim.WorldSnapshot.UnitView;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A unit in the live simulation: a crew's app that receives trips, drives, reports its position and arrival, and
 * sometimes misbehaves (never answers, breaks down, loses signal). A failed unit is repaired after the configured
 * time and reports that it is back in service.
 * <p>
 * Threads: it runs as a repeating task on the agent pool ({@link #run()}). The engine thread hands it trip orders
 * through a thread-safe inbox ({@link #order(TripOrder)}), which {@code run()} reads. What the window shows is
 * published as an immutable {@link UnitView} in a {@code volatile} field, so any thread can read it safely.
 */
final class LiveUnit implements Runnable {

    /**
     * A message from the engine to the unit.
     *
     * @param kind         a new trip, a role change on the current trip, or the end of a trip
     * @param assignmentId the trip it is about
     * @param target       where to go (START only)
     * @param role         the trip's role (START and ROLE)
     * @param finalStatus  how the trip ended (END only)
     */
    record TripOrder(Kind kind, String assignmentId, Location target, AssignmentRole role,
                     AssignmentStatus finalStatus) {
        enum Kind { START, ROLE, END }
    }

    private final SimulatedUnit unit;
    private final ScaledClock clock;
    private final LiveEngine engine;
    private final Random dice;
    private final LiveConfig config;
    private final ConcurrentLinkedQueue<TripOrder> inbox = new ConcurrentLinkedQueue<>();

    private AssignmentRole role;              // role on the current trip, null when free
    private Instant lastTick;
    private Instant backInServiceAt;          // set while out of service after a timeout or failure
    private volatile UnitView view;

    /**
     * Creates a free unit.
     *
     * @param unit   the moving part (position, speed, behaviour on a trip)
     * @param clock  the live simulation's clock
     * @param engine where answers, positions and arrivals are sent
     * @param dice   this unit's own randomness
     * @param config failure chances and repair time
     */
    LiveUnit(SimulatedUnit unit, ScaledClock clock, LiveEngine engine, Random dice, LiveConfig config) {
        this.unit = unit;
        this.clock = clock;
        this.engine = engine;
        this.dice = dice;
        this.config = config;
        this.lastTick = clock.instant();
        publishView();
    }

    /**
     * Hands the unit a message from the engine. Safe to call from any thread.
     *
     * @param order the message
     */
    void order(TripOrder order) {
        inbox.add(order);
    }

    /**
     * What the window should show for this unit. Safe to call from any thread.
     *
     * @return the unit as it was at the end of its latest beat
     */
    UnitView view() {
        return view;
    }

    String id() {
        return unit.id();
    }

    /** One beat: read new orders, come back from repair if due, move, report. Runs on the agent pool. */
    @Override
    public synchronized void run() {
        Instant now = clock.instant();
        Duration elapsed = Duration.between(lastTick, now);
        lastTick = now;
        readOrders(now);
        if (backInServiceAt != null && !now.isBefore(backInServiceAt)) {
            backInServiceAt = null;
            engine.position(unit.id(), unit.position());      // the engine may have an old position for it
            engine.backInService(unit.id());
        }
        if (elapsed.isPositive()) {                           // nothing moves while the clock is paused
            TickResult result = unit.tick(elapsed);
            String trip = unit.assignmentId();
            if (result == TickResult.ACKNOWLEDGED) {
                engine.answered(trip);
            }
            if (unit.reportsPosition()) {
                engine.position(unit.id(), unit.position());
            }
            if (result == TickResult.ARRIVED) {
                engine.arrived(trip);
            }
        }
        publishView();
    }

    private void readOrders(Instant now) {
        TripOrder order = inbox.poll();
        while (order != null) {
            switch (order.kind()) {
                case START -> {
                    unit.assign(order.assignmentId(), order.target(), behaviour());
                    role = order.role();
                }
                case ROLE -> {
                    if (order.assignmentId().equals(unit.assignmentId())) {
                        role = order.role();
                    }
                }
                case END -> {
                    if (order.assignmentId().equals(unit.assignmentId())) {
                        unit.free();
                        role = null;
                        if (order.finalStatus() == AssignmentStatus.TIMED_OUT
                                || order.finalStatus() == AssignmentStatus.FAILED) {
                            backInServiceAt = now.plus(config.repairTime());   // the engine took it offline
                        }
                    }
                }
            }
            order = inbox.poll();
        }
    }

    // one roll per trip: never answers, breaks down, loses signal, or normal
    private Behaviour behaviour() {
        double roll = dice.nextDouble();
        if (roll < config.noAckChance()) {
            return Behaviour.NEVER_ACKNOWLEDGES;
        }
        if (roll < config.noAckChance() + config.breakdownChance()) {
            return Behaviour.BREAKS_DOWN;
        }
        if (roll < config.noAckChance() + config.breakdownChance() + config.signalLossChance()) {
            return Behaviour.LOSES_SIGNAL;
        }
        return Behaviour.NORMAL;
    }

    private void publishView() {
        UnitState state;
        if (backInServiceAt != null) {
            state = UnitState.OFFLINE;
        } else if (!unit.busy()) {
            state = UnitState.FREE;
        } else if (unit.broken()) {
            state = UnitState.BROKEN_DOWN;
        } else if (unit.silent()) {
            state = UnitState.NO_SIGNAL;
        } else if (!unit.acknowledged()) {
            state = UnitState.ANSWERING;
        } else {
            state = unit.arrived() ? UnitState.ON_SCENE : UnitState.DRIVING;
        }
        Optional<Location> target = unit.busy() ? Optional.of(unit.target()) : Optional.empty();
        view = new UnitView(unit.id(), unit.type(), unit.position(), state, target, Optional.ofNullable(role));
    }
}
