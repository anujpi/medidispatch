package com.medidispatch.service.api;

import com.medidispatch.service.DispatchService;
import com.medidispatch.service.FacilityInfo;
import com.medidispatch.service.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * HTTP endpoints for hospitals and clinics. The operator adds them; each hospital switches its own diversion.
 */
// ponytail: no login yet, so any caller can act for any hospital (BACKLOG: hospital access control).
@RestController
@RequestMapping("/api/facilities")
public class FacilityController {

    private final DispatchService service;

    /**
     * @param service the single front door to the engine
     */
    public FacilityController(DispatchService service) {
        this.service = service;
    }

    /**
     * {@code POST /api/facilities}: adds a hospital or clinic. 201, 400 bad values (for example an accepting
     * clinic), 409 id taken.
     *
     * @param request the facility
     * @return the stored facility
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FacilityInfo register(@RequestBody FacilityRequest request) {
        return service.registerFacility(request.toFacility());
    }

    /**
     * {@code GET /api/facilities}.
     *
     * @return every hospital and clinic, sorted by id
     */
    @GetMapping
    public List<FacilityInfo> all() {
        return service.facilities();
    }

    /**
     * {@code GET /api/facilities/{id}}. 200 or 404.
     *
     * @param id the facility
     * @return the facility
     */
    @GetMapping("/{id}")
    public FacilityInfo find(@PathVariable String id) {
        return service.findFacility(id).orElseThrow(() -> new NotFoundException("Facility " + id + " not found"));
    }

    /**
     * {@code PUT /api/facilities/{id}/accepting} with {@code {"accepting": false}}: a hospital declares diversion,
     * or ends it with {@code true}. PUT because it sets a value: sending it twice is harmless. 200, 400 (a clinic
     * cannot accept), 404.
     *
     * @param id      the facility
     * @param request the new value
     * @return the updated facility
     */
    @PutMapping("/{id}/accepting")
    public FacilityInfo setAccepting(@PathVariable String id, @RequestBody AcceptingRequest request) {
        return service.setAccepting(id, request.accepting());
    }
}
