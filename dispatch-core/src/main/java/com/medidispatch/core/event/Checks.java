package com.medidispatch.core.event;

final class Checks {
    private Checks(){}
    static void id(String value,String name){
        if(value == null || value.isBlank()){
            throw new IllegalArgumentException(name + "is required");
        }
    }
    static void notNull(Object value,String name){
        if(value == null){
            throw new IllegalArgumentException(name +"is required");
        }
    }
}
