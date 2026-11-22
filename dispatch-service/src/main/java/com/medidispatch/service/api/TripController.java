package com.medidispatch.service.api;

import com.medidispatch.service.DispatchService;
import com.medidispatch.service.NotFoundException;
import com.medidispatch.service.TripInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP endpoints for the crew's steps on a trip: accept, arrive, transport (to hospital), complete.
 *
 * <p>The steps are action URLs ({@code POST /api/trips/t-17/accept}) rather than "set the status"
 * ({@code PUT /api/trips/t-17 {"status": "DONE"}}): a trip is a state machine, so the client asks for an action
 * and the server decides whether it is allowed. Each step is safe to repeat (see {@link DispatchService}), so a
 * phone may retry after a lost response.
 */
@RestController
@RequestMapping("/api/trips")
public class TripController {

    private final DispatchService service;

    /**
     * @param service the front door to the engine
     */
    public TripController(DispatchService service) {
        this.service = service;
    }

    /**
     * {@code GET /api/trips/{id}}: one trip. 404 if there is none.
     *
     * @param id the trip
     * @return the trip
     */
    @GetMapping("/{id}")
    public TripInfo find(@PathVariable String id) {
        return service.findTrip(id).orElseThrow(() -> new NotFoundException("Trip " + id + " not found"));
    }

    /**
     * {@code POST /api/trips/{id}/accept}: the crew is on the way. 200, 404 unknown trip, 409 not allowed now.
     *
     * @param id the trip
     * @return the trip, EN_ROUTE
     */
    @PostMapping("/{id}/accept")
    public TripInfo accept(@PathVariable String id) {
        return service.acceptTrip(id);
    }

    /**
     * {@code POST /api/trips/{id}/arrive}: the crew reached the patient. 200, 404 unknown trip, 409 not allowed now.
     *
     * @param id the trip
     * @return the trip, ARRIVED
     */
    @PostMapping("/{id}/arrive")
    public TripInfo arrive(@PathVariable String id) {
        return service.arrive(id);
    }

    /**
     * {@code POST /api/trips/{id}/transport}: the crew takes the patient to hospital, with a body such as
     * {@code {"needs": ["STROKE"]}}; the engine picks the hospital. The body may be left out: nothing special is
     * needed. Safe to repeat. 200, 404 unknown trip, 409 not allowed (not the main ambulance, not arrived, or no
     * hospital registered).
     *
     * @param id      the trip
     * @param request what the patient needs; may be absent
     * @return the trip, TRANSPORTING, with its hospital and needs
     */
    @PostMapping("/{id}/transport")
    public TripInfo transport(@PathVariable String id, @RequestBody(required = false) TransportRequest request) {
        TransportRequest body = request == null ? new TransportRequest(null) : request;
        return service.startTransport(id, body.needs());
    }

    /**
     * {@code POST /api/trips/{id}/complete}: the crew is done, on scene or after the handover at the hospital; for
     * the main unit this also closes the case. 200, 404 unknown trip, 409 not allowed now.
     *
     * @param id the trip
     * @return the trip, DONE
     */
    @PostMapping("/{id}/complete")
    public TripInfo complete(@PathVariable String id) {
        return service.completeTrip(id);
    }
}
