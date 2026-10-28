package com.medidispatch.core.memory;

import com.medidispatch.core.domain.Case;
import com.medidispatch.core.domain.RiskBand;
import com.medidispatch.core.port.CaseQueue;

import java.time.Instant;
import java.util.*;

public class InMemoryCaseQueue implements CaseQueue {

    private static int compare(Entry a, Entry b) {
        int byBand = b.band().compareTo(a.band());
        // 1. Higher band first
        if (byBand != 0) return byBand;
        int byTime = a.openedAt().compareTo(b.openedAt());
        // 2. older case first
        if (byTime != 0) return byTime;
        // 3. tie-break
        return a.id().compareTo(b.id());
    }

    private record Entry(String id, RiskBand band, Instant openedAt, Case caseRef){}
    private static final Comparator<Entry> MOST_URGENT_FIRST = InMemoryCaseQueue::compare;
    private final PriorityQueue<Entry> heap = new PriorityQueue<>(MOST_URGENT_FIRST);
    private final Map<String,Entry> byId = new HashMap<>();

    @Override
    public void enqueue(Case c) {
        if(c == null){
            throw new IllegalArgumentException("case is required");
        }
        // if the same id is queued before , then replace it
        remove(c.id());
        Entry entry = new Entry(c.id(),c.band(),c.openedAt(),c);
        heap.add(entry);
        byId.put(c.id(),entry);
    }

    @Override
    public Optional<Case> pollNext() {
        Entry entry = heap.poll();
        if(entry == null) return Optional.empty();
        byId.remove(entry.id());
        return Optional.of(entry.caseRef());
    }

    @Override
    public boolean remove(String caseId) {
        if(caseId == null){
            throw new IllegalArgumentException("CaseId is required");
        }
        if(!byId.containsKey(caseId)){
            return false;
        }
        Entry entry = byId.get(caseId);
        byId.remove(caseId);
        heap.remove(entry);
        return true;
    }

    @Override
    public int size() {
        return byId.size();
    }

    @Override
    public Optional<Case> peekNext() {
        Entry entry = heap.peek();
        if(entry == null) return Optional.empty();
        return  Optional.of(entry.caseRef());
    }
}
