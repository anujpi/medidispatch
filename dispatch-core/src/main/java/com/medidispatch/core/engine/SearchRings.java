package com.medidispatch.core.engine;

/**
 * The radius steps for searching around a patient: look close first and widen only if nothing close
 * worked (2, 4, 8, 16 km and so on, never past the maximum).
 * <p>
 * Shared by {@link Dispatcher} and {@link Preemptor} so both search the same way.
 */
final class SearchRings {
    private static final double START_KM = 2.0;
    private SearchRings(){}

    /**
     * The first radius to try.
     *
     * @param maxKm the largest radius allowed
     * @return 2 km, or {@code maxKm} if that is smaller
     */
    static double first(double maxKm){
        return Math.min(START_KM,maxKm);
    }

    /**
     * The radius after {@code currentKm}.
     *
     * @param currentKm the radius just tried
     * @param maxKm     the largest radius allowed
     * @return double the current radius, capped at {@code maxKm}
     */
    static double next(double currentKm,double maxKm){
        return Math.min(currentKm * 2,maxKm);
    }
}
