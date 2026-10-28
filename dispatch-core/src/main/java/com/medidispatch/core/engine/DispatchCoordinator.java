package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.domain.Case;
import com.medidispatch.core.port.CaseQueue;

import java.util.Optional;

/**
 * The one place that decides the order of a dispatch attempt: a free unit first, then preemption,
 * then a second try.
 * <p>
 * Free units are always used before anyone is bumped. Known limit: between freeing a unit and the second
 * try, another thread could take that unit; this is safe while dispatching runs in a single loop.
 */
public class DispatchCoordinator {
    private final Dispatcher dispatcher;
    private final Preemptor preemptor;
    private final CaseQueue queue;

    /**
     * Creates the coordinator.
     *
     * @param dispatcher sends free units
     * @param preemptor  frees a unit from a less urgent case
     * @param queue      used to see which case could not be served
     * @throws IllegalArgumentException if any argument is missing
     */
    public DispatchCoordinator(Dispatcher dispatcher, Preemptor preemptor, CaseQueue queue) {

        if(dispatcher == null || preemptor == null || queue == null){
            throw new IllegalArgumentException("Dispatcher,preemptor and queue are required");
        }
        this.dispatcher = dispatcher;
        this.preemptor = preemptor;
        this.queue = queue;
    }
    /**
     * Serves the most urgent waiting case, preempting a less urgent trip if no unit is free.
     *
     * @return the new trip, or empty if the queue is empty or nobody could be sent or taken
     */
    public Optional<Assignment> dispatchNext(){
        // free unit existed
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
