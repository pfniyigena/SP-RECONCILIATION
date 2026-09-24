package nika.tax.reporter.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityNotFoundException;

/**
 * Added alongside CardTransactionService.getOrThrow switching from ResponseStatusException to
 * EntityNotFoundException. Unlike ResponseStatusException, EntityNotFoundException isn't
 * natively understood by Spring MVC's exception resolution — with no handler, it would
 * propagate as an uncaught exception and produce a 500, not the 404 the person navigating to a
 * missing transaction actually needs to see. This re-throws it as the ResponseStatusException
 * Spring already knows how to render correctly, rather than building new custom error-page
 * templates for what's otherwise identical "not found" handling to everywhere else in this app.
 *
 * Deliberately not applied to every getOrThrow in this project — the others still use
 * ResponseStatusException directly (already correctly handled with no handler needed); this
 * only exists because THIS ONE service's getOrThrow now uses a different exception type.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    public void handleEntityNotFound(EntityNotFoundException ex) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
    }
}
