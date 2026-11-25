package com.medidispatch.service.api;

import com.medidispatch.service.CaseInfo;
import com.medidispatch.service.DispatchService;
import com.medidispatch.service.TripInfo;
import com.medidispatch.service.UnitInfo;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP endpoints for the dispatcher's overrides: cancel a case, send a chosen unit, take a unit out of service.
 * Kept together so the login (SEC) can give them to the operator role with one rule. Each needs a reason and is safe
 * to repeat; 404 for an unknown case or unit, 409 when the action is not allowed now, 400 for a broken body.
 */
@RestController
@RequestMapping("/api")
public class OverrideController {

    private final DispatchService service;

    /**
     * @param service the front door to the engine
     */
    public OverrideController(DispatchService service) {
        this.service = service;
    }

    /**
     * {@code POST /api/cases/{id}/cancel {"reason": "..."}}: the case is called off and its units freed.
     *
     * @param id      the case
     * @param request the reason
     * @return the case, CANCELLED
     */
    @PostMapping("/cases/{id}/cancel")
    public CaseInfo cancel(@PathVariable String id, @RequestBody OverrideRequest request) {
        return service.cancelCase(id, request.reason());
    }

    /**
     * {@code POST /api/cases/{id}/send {"unitId": "als-7", "reason": "..."}}: the chosen unit becomes the case's
     * main response, replacing the engine's choice.
     *
     * @param id      the case
     * @param request the unit and the reason
     * @return the unit's trip
     */
    @PostMapping("/cases/{id}/send")
    public TripInfo send(@PathVariable String id, @RequestBody OverrideRequest request) {
        if (request.unitId() == null || request.unitId().isBlank()) {
            throw new IllegalArgumentException("unitId is required");
        }
        return service.sendUnit(id, request.unitId(), request.reason());
    }

    /**
     * {@code POST /api/units/{id}/out-of-service {"reason": "..."}}: the unit goes offline; its trip, if any, fails
     * and the case waits for the next unit. Undo with {@code POST /api/units/{id}/back-in-service}.
     *
     * @param id      the unit
     * @param request the reason
     * @return the unit, OFFLINE
     */
    @PostMapping("/units/{id}/out-of-service")
    public UnitInfo outOfService(@PathVariable String id, @RequestBody OverrideRequest request) {
        return service.takeOutOfService(id, request.reason());
    }
}
