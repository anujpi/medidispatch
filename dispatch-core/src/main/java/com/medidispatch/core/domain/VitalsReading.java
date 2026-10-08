package com.medidispatch.core.domain;

import java.time.Instant;

public record VitalsReading(String patientId,
                            Instant timeStamp,
                            Integer heartRate,
                            Integer spo2,
                            Integer systolicBp,
                            Integer diastolicBp,
                            Integer respRate,
                            Double temperature,
                            Consciousness consciousness) {
   public VitalsReading{
       if(patientId !=null && patientId.isBlank()){
           throw new IllegalArgumentException("PatientId cant be empty");
       }
       if(spo2 != null && (spo2 < 0 || spo2 > 100)){
           throw new IllegalArgumentException("Spo2 must be 0 - 100 , got "+spo2);
       }
       if(heartRate != null && (heartRate < 0 || heartRate > 300)){
           throw new IllegalArgumentException("HeartRate must be 0 - 300, got"+ heartRate);
       }
       if(systolicBp != null && (systolicBp < 30 || systolicBp > 300)){
        throw  new IllegalArgumentException("SystolicBp must be 30 - 300 , got"+systolicBp);
       }
       // cant really put this in one if cuz systolicBp can produce null condition and to fix that
       // i need to write another line in the condition , so its better to just keep them separate
//       if(diastolicBp != null && (diastolicBp < 10 || diastolicBp > 200) || (diastolicBp != null && systolicBp != null && diastolicBp > systolicBp)){
//           throw new IllegalArgumentException(diastolicBp > systolicBp ?"DiastolicBp cant be higher than systolicBp":"DiastolicBp must be 10 - 200, got"+diastolicBp);
//       }
       if (diastolicBp != null && (diastolicBp < 10 || diastolicBp > 200)) {
           throw new IllegalArgumentException("DiastolicBp must be 10 - 200, got " + diastolicBp);
       }
       if (systolicBp != null && diastolicBp != null && diastolicBp > systolicBp) {
           throw new IllegalArgumentException("DiastolicBp can't be higher than systolicBp");
       }
       if(temperature!=null && (temperature < 25 || temperature > 45)){
           throw new IllegalArgumentException("Temperature must be 25-45 , got "+temperature);
       }
       if(patientId == null || timeStamp == null || consciousness == null){
           throw new IllegalArgumentException("PatientId,TimeStamp,Consciousness cant be null");
       }
       if(respRate != null && (respRate < 0 || respRate > 45)){
           throw new IllegalArgumentException("RespRate must be 0 - 45, got" + respRate);
       }
   }
}
