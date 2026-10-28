package com.medidispatch.core.engine;

final class SearchRings {
    private static final double START_KM = 2.0;
    private SearchRings(){}

    static double first(double maxKm){
        return Math.min(START_KM,maxKm);
    }
    static double next(double currentKm,double maxKm){
        return Math.min(currentKm * 2,maxKm);
    }
}
