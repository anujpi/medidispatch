package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.domain.Case;
import com.medidispatch.core.port.CaseQueue;

import java.util.Optional;

/**
 * The one place that decides the order of a dispatch attempt: a free unit first, then preemption,
 * then a second try; and once a main-response unit is on its way, a first responder if one is closer.
 * <p>
 * Free units are always used before anyone is bumped. Known limit: between freeing a unit and the second
 * try, another thread could take that unit; this is safe while dispatching runs in a single loop.
 */
public class DispatchCoordinator {
    private final Dispatcher dispatcher;
    private final Preemptor preemptor;
    private final SupportDispatcher support;
    private final CaseQueue queue;

    /**
     * Creates the coordinator.
     *
     * @param dispatcher sends free main-response units
     * @param preemptor  frees a unit from a less urgent case
     * @param support    sends first responders alongside the main response
     * @param queue      used to see which case could not be served
     * @throws IllegalArgumentException if any argument is missing
     */
    public DispatchCoordinator(Dispatcher dispatcher, Preemptor preemptor, SupportDispatcher support, CaseQueue queue) {
        if(dispatcher == null || preemptor == null || support == null || queue == null){
            throw new IllegalArgumentException("Dispatcher, preemptor, support and queue are required");
        }
        this.dispatcher = dispatcher;
        this.preemptor = preemptor;
        this.support = support;
        this.queue = queue;
    }

    /**
     * Serves the most urgent waiting case, preempting a less urgent trip if no unit is free, then sends a
     * first responder if the case's plan wants one and one is closer than the main-response unit.
     *
     * @return the new main-response (PRIMARY) trip, or empty if the queue is empty or nobody could be sent
     *         or taken. A first responder's trip is not returned; it is announced and stored like any trip.
     */
    public Optional<Assignment> dispatchNext(){
        Optional<Assignment> primary = sendPrimary();
        if (primary.isPresent()) {
            support.sendSupportFor(primary.get());
        }
        return primary;
    }

    // free unit first, then preempt and try again
    private Optional<Assignment> sendPrimary() {
        Optional<Assignment> normal = dispatcher.dispatchNext();
        if(normal.isPresent()){
            return normal;
        }
        Optional<Case> waiting = queue.peekNext();
        if(waiting.isEmpty()){
            return Optional.empty();
        }
        if(!preemptor.makeRoomFor(waiting.get())){
            return Optional.empty();
        }
        return dispatcher.dispatchNext();
    }
}
