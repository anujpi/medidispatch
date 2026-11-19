package com.medidispatch.service;

import com.medidispatch.core.engine.ProcessingResult;

/**
 * What happened to one vitals reading.
 *
 * @param outcome  ACCEPTED, or why the reading was ignored (unknown patient, too old, duplicate and so on)
 * @param openCase the patient's open case after this reading, or {@code null} if they have none
 */
public record ReadingResult(ProcessingResult.Outcome outcome, CaseInfo openCase) {
}
