package com.medidispatch.routing;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class MinHeapTest {

    /** Random costs come out smallest first; the node travels with its cost. */
    @Test
    void popsInAscendingOrder() {
        Random dice = new Random(3);
        MinHeap heap = new MinHeap();
        List<Double> pushed = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            double key = dice.nextInt(1_000);
            pushed.add(key);
            heap.push(key, (int) key * 10);                  // the value is derived from the key, to check pairing
        }
        pushed.sort((a, b) -> Double.compare(a, b));

        for (double expected : pushed) {
            assertEquals((int) expected * 10, heap.pop());
        }
        assertTrue(heap.isEmpty());
    }

    /** It starts with room for 64 and grows by doubling; nothing is lost. */
    @Test
    void growsPastItsStartingSize() {
        MinHeap heap = new MinHeap();
        for (int i = 1_000; i > 0; i--) {
            heap.push(i, i);
        }

        assertEquals(1_000, heap.size());
        for (int i = 1; i <= 1_000; i++) {
            assertEquals(i, heap.pop());
        }
    }

    /** The same node pushed twice (a cheaper way found later) is two items: lazy deletion relies on that. */
    @Test
    void duplicatesAreKept() {
        MinHeap heap = new MinHeap();
        heap.push(5, 7);
        heap.push(2, 7);

        assertEquals(2, heap.size());
        assertEquals(7, heap.pop());
        assertEquals(7, heap.pop());
    }

    @Test
    void poppingAnEmptyHeapIsRefused() {
        assertThrows(IllegalStateException.class, () -> new MinHeap().pop());
    }
}
