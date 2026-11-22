package com.medidispatch.core.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * One set of vital signs for a patient, taken at a single moment.
 * <p>
 * Only the patient, the time and the consciousness level are required. Every measurement is optional
 * ({@code null} means "not measured"), because real devices do not always send everything. Values that
 * are present are range-checked, so an impossible reading never enters the engine.
 *
 * @param patientId     the patient the reading belongs to; never blank
 * @param timeStamp     when the reading was taken; kept to the microsecond (finer digits are dropped)
 * @param heartRate     beats per minute, 0 to 300
 * @param spo2          blood oxygen saturation in percent, 0 to 100
 * @param systolicBp    systolic blood pressure in mmHg, 30 to 300
 * @param diastolicBp   diastolic blood pressure in mmHg, 10 to 200, never above the systolic value
 * @param respRate      breaths per minute, 0 to 45
 * @param temperature   body temperature in degrees Celsius, 25 to 45
 * @param consciousness level of consciousness on the ACVPU scale
 */
public record VitalsReading(String patientId,
                            Instant timeStamp,
                            Integer heartRate,
                            Integer spo2,
                            Integer systolicBp,
                            Integer diastolicBp,
                            Integer respRate,
                            Double temperature,
                            Consciousness consciousness) {
   /**
    * Validates the reading.
    *
    * @throws IllegalArgumentException if a required field is missing, the patient id is blank,
    *                                  or a measurement is outside its allowed range
    */
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
//      }
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
       // Kept to the microsecond, the precision Postgres stores. Finer digits (Instant.now() on Windows has 100 ns)
       // would be ROUNDED by the database, so a resent reading could look slightly older than the stored copy
       // (OUT_OF_ORDER instead of DUPLICATE). Below a microsecond is noise for a vital sign anyway.
       timeStamp = timeStamp.truncatedTo(ChronoUnit.MICROS);
       if(respRate != null && (respRate < 0 || respRate > 45)){
           throw new IllegalArgumentException("RespRate must be 0 - 45, got" + respRate);
       }
   }
}
