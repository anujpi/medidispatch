package com.medidispatch.service.api;

import com.medidispatch.service.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns exceptions into HTTP error responses, in one place for every controller.
 *
 * <p>{@code @RestControllerAdvice} means: when any controller method throws, Spring looks here for an
 * {@code @ExceptionHandler} for that exception type, and sends back what it returns. The body is a
 * {@link ProblemDetail}, the standard error format (RFC 9457) that Spring already knows how to write:
 * <pre>{"type":"about:blank","title":"Conflict","status":409,"detail":"Trip t-17 is DONE ...","instance":"/api/..."}</pre>
 *
 * <p>The status code tells the client whether retrying can help: a 4xx means "no, fix the request", so a phone
 * with a weak signal knows not to keep resending it.
 */
@RestControllerAdvice
public class ApiErrors {

    /**
     * @param e the trip, unit or case that does not exist
     * @return 404 Not Found
     */
    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /**
     * The engine throws IllegalStateException when something exists but the step is not allowed now (arrive
     * before accept, a duplicate id, a trip that was taken away).
     *
     * @param e what was refused
     * @return 409 Conflict
     */
    // ponytail: every IllegalStateException becomes a 409, so a real bug that throws one would show as 409 instead
    // of 500. Fine while the engine uses it only for refused moves; add a dedicated exception if that changes.
    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    /**
     * Thrown by the engine's constructors for bad values (blank id, latitude 200, heart rate 999) when the
     * controller builds engine objects.
     *
     * @param e what was wrong
     * @return 400 Bad Request
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badValue(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * The body could not be turned into the request record: broken JSON, an unknown unit type, or a record
     * constructor that refused a value while the JSON was being read.
     *
     * @param e the reading error; the useful message is in its deepest cause
     * @return 400 Bad Request
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpMessageNotReadableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMostSpecificCause().getMessage());
    }
}
