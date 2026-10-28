package com.medidispatch.core.engine;

import com.medidispatch.core.domain.Assignment;
import com.medidispatch.core.domain.Case;
import com.medidispatch.core.port.CaseQueue;

import java.util.Optional;

// the one place which decides the order , free unit first, then preempt , then try again
public class DispatcherCoordinator {
    private final Dispatcher dispatcher;
    private final Preemptor preemptor;
    private final CaseQueue queue;

    public DispatcherCoordinator(Dispatcher dispatcher, Preemptor preemptor, CaseQueue queue) {

        if(dispatcher == null || preemptor == null || queue == null){
            throw new IllegalArgumentException("Dispatcher,preemptor and queue are required");
        }
        this.dispatcher = dispatcher;
        this.preemptor = preemptor;
        this.queue = queue;
    }
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
