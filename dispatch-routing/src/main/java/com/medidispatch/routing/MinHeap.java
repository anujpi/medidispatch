package com.medidispatch.routing;

import java.util.Arrays;

/**
 * A binary min-heap of (cost, node) pairs in two plain arrays: the router's priority queue. {@code pop} always gives
 * the node with the smallest cost; pushing and popping cost about log n steps.
 * <p>
 * The tree is implicit in the arrays: item {@code i}'s children are at {@code 2i+1} and {@code 2i+2}, its parent at
 * {@code (i-1)/2}, and the smallest cost is always at 0. {@code push} adds at the end and sifts the item up past
 * bigger parents; {@code pop} takes the top, moves the last item to the root and sifts it down past smaller children.
 * Both hold the moving item aside and write it once at the end instead of swapping at every level.
 * <p>
 * No decrease-key: the router pushes a node again when it finds a cheaper way and skips the stale copies when they
 * surface ("lazy deletion"). Plain arrays instead of {@code java.util.PriorityQueue}: no boxing of every number.
 */
final class MinHeap {

    private double[] keys = new double[64];
    private int[] values = new int[64];
    private int size;

    /**
     * @param key   the cost
     * @param value the node
     */
    void push(double key, int value) {
        if (size == keys.length) {
            keys = Arrays.copyOf(keys, size * 2);
            values = Arrays.copyOf(values, size * 2);
        }
        int i = size++;
        while (i > 0) {
            int parent = (i - 1) / 2;
            if (keys[parent] <= key) {
                break;
            }
            keys[i] = keys[parent];                           // the bigger parent moves down
            values[i] = values[parent];
            i = parent;
        }
        keys[i] = key;
        values[i] = value;
    }

    /**
     * @return the node with the smallest cost (removed from the heap)
     * @throws IllegalStateException if the heap is empty
     */
    int pop() {
        if (size == 0) {
            throw new IllegalStateException("the heap is empty");
        }
        int top = values[0];
        size--;
        double key = keys[size];                              // the last item, to be placed from the root down
        int value = values[size];
        int i = 0;
        while (true) {
            int child = 2 * i + 1;
            if (child >= size) {
                break;
            }
            if (child + 1 < size && keys[child + 1] < keys[child]) {
                child++;                                      // the smaller of the two children
            }
            if (key <= keys[child]) {
                break;
            }
            keys[i] = keys[child];                            // the smaller child moves up
            values[i] = values[child];
            i = child;
        }
        keys[i] = key;
        values[i] = value;
        return top;
    }

    /** @return {@code true} if there is nothing to pop */
    boolean isEmpty() {
        return size == 0;
    }

    /** @return how many items are in the heap */
    int size() {
        return size;
    }
}
