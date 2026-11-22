package com.medidispatch.service.api;

import com.medidispatch.core.domain.VitalsReading;
import com.medidispatch.service.CaseInfo;
import com.medidispatch.service.DispatchService;
import com.medidispatch.service.NotFoundException;
import com.medidispatch.service.PatientInfo;
import com.medidispatch.service.ReadingResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP endpoints for the patient side: registering patients, their vitals readings and their cases.
 *
 * <p>{@code @RestController} tells Spring that every method answers an HTTP request and that the returned object
 * is written to the response as JSON. Like every controller here, it only translates: all decisions are made by
 * {@link DispatchService}, and errors are turned into status codes by {@link ApiErrors}.
 */
@RestController
@RequestMapping("/api")
public class PatientController {

    private final DispatchService service;

    /**
     * @param service the front door to the engine
     */
    public PatientController(DispatchService service) {
        this.service = service;
    }

    /**
     * {@code POST /api/patients}: registers a patient. 201 Created, 409 if the id exists, 400 if the body is bad.
     *
     * @param request the id and home position
     */
    @PostMapping("/patients")
    @ResponseStatus(HttpStatus.CREATED)
    public void register(@RequestBody PatientRequest request) {
        service.registerPatient(request.toPatient());
    }

    /**
     * {@code GET /api/patients/{id}/case}: the patient's open case. 404 if they have none.
     *
     * @param id the patient
     * @return the open case
     */
    /**
     * {@code GET /api/patients/{id}}: the patient's full record, including the emergency contact. 200 or 404.
     *
     * @param id the patient
     * @return the patient
     */
    // ponytail: personal data with no login in front of it yet (BACKLOG: access control, the security piece).
    @GetMapping("/patients/{id}")
    public PatientInfo find(@PathVariable String id) {
        return service.findPatient(id).orElseThrow(() -> new NotFoundException("Patient " + id + " not found"));
    }

    @GetMapping("/patients/{id}/case")
    public CaseInfo openCase(@PathVariable String id) {
        return service.openCaseOf(id).orElseThrow(() -> new NotFoundException("Patient " + id + " has no open case"));
    }

    /**
     * {@code POST /api/readings}: one vitals reading from a patient's device.
     *
     * <p>Always 200 when the body is valid, even if the reading was ignored: the outcome (ACCEPTED, DUPLICATE,
     * TOO_OLD...) is in the body. A device that sends the same reading twice gets DUPLICATE, not an error, so it
     * never retries something that cannot succeed. 400 only for a body that is not a valid reading.
     *
     * @param reading the reading; the JSON field names are the {@link VitalsReading} record's
     * @return what happened to it, and the patient's open case if any
     */
    @PostMapping("/readings")
    public ReadingResult submit(@RequestBody VitalsReading reading) {
        return service.submitReading(reading);
    }

    /**
     * {@code GET /api/cases/{id}}: one case. 404 if there is none.
     *
     * @param id the case
     * @return the case
     */
    @GetMapping("/cases/{id}")
    public CaseInfo findCase(@PathVariable String id) {
        return service.findCase(id).orElseThrow(() -> new NotFoundException("Case " + id + " not found"));
    }
}
