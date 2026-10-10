package com.medidispatch.core.domain;

public record Location(double latitude , double longitude) {
    private static final Double EARTH_RADIUS_KM = 6371.0;
    public Location{
        if(latitude < -90 || latitude > 90){
            throw new IllegalArgumentException("Latitude can only be from -90 to 90 , got "+ latitude);
        }
        if(longitude < -180 || longitude > 180){
            throw new IllegalArgumentException("Longitude must be between -180 to 180 , got " +longitude);
        }
    }
    public double distanceKmTo(Location other){
        double diffLon = Math.toRadians(other.longitude  - longitude);
        double diffLat = Math.toRadians(other.latitude - latitude);
        double a = (Math.sin(diffLat/ 2) * Math.sin(diffLat/ 2)) + (Math.cos(Math.toRadians(latitude)) * Math.cos(Math.toRadians(other.latitude))) * (Math.sin(diffLon/2) * Math.sin(diffLon/2));
        double c  = 2 *  Math.atan2(Math.sqrt(a),Math.sqrt(1-a));
        return EARTH_RADIUS_KM*c;
    }
}
