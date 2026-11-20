package com.medidispatch.service.api;

import com.medidispatch.service.DispatchService;
import com.medidispatch.service.NotFoundException;
import com.medidispatch.service.TripInfo;
import com.medidispatch.service.UnitInfo;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * HTTP endpoints for units (ambulances, nurses, doctors): registering them, their position, their current trip,
 * and putting them back in service.
 */
@RestController
@RequestMapping("/api/units")
public class UnitController {

    private final DispatchService service;

    /**
     * @param service the front door to the engine
     */
    public UnitController(DispatchService service) {
        this.service = service;
    }

    /**
     * {@code POST /api/units}: registers a unit. 201 Created with the new unit, 409 if the id exists, 400 if the
     * body is bad.
     *
     * @param request the id, type and position
     * @return the new unit
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UnitInfo register(@RequestBody UnitRequest request) {
        return service.registerUnit(request.id(), request.type(), request.position().toLocation());
    }

    /**
     * {@code GET /api/units/{id}}: one unit. 404 if there is none.
     *
     * @param id the unit
     * @return the unit
     */
    @GetMapping("/{id}")
    public UnitInfo find(@PathVariable String id) {
        return service.findUnit(id).orElseThrow(() -> new NotFoundException("Unit " + id + " not found"));
    }

    /**
     * {@code GET /api/units/{id}/trip}: the crew app asks "do I have a job?". 200 with the trip, or
     * 204 No Content when there is none: having no job is normal, not an error. 404 for an unknown unit.
     *
     * <p>{@link ResponseEntity} is used here because the status code depends on the answer; the other methods
     * always succeed with the same code, so a plain return value and {@code @ResponseStatus} are enough there.
     *
     * @param id the unit
     * @return the trip, or an empty 204 response
     */
    @GetMapping("/{id}/trip")
    public ResponseEntity<TripInfo> currentTrip(@PathVariable String id) {
        find(id);
        Optional<TripInfo> trip = service.activeTripOf(id);
        if (trip.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(trip.get());
    }

    /**
     * {@code PUT /api/units/{id}/position}: the crew phone reports where the unit is. 204, or 404 for an unknown
     * unit.
     *
     * <p>PUT, not POST: it means "set the position to this", so sending the same request twice has the same
     * result as sending it once. A retry after a lost response is harmless by design.
     *
     * @param id    the unit
     * @param where the position
     */
    @PutMapping("/{id}/position")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reportPosition(@PathVariable String id, @RequestBody Coordinates where) {
        service.reportPosition(id, where.toLocation());
    }

    /**
     * {@code POST /api/units/{id}/back-in-service}: an OFFLINE unit is ready again. 200 with the unit, 404 for an
     * unknown unit, 409 if it is not OFFLINE.
     *
     * @param id the unit
     * @return the updated unit
     */
    @PostMapping("/{id}/back-in-service")
    public UnitInfo backInService(@PathVariable String id) {
        return service.backInService(id);
    }
}
