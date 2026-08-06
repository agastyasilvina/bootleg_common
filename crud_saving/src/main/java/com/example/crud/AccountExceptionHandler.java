package com.example.crud;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the service's failure modes onto statuses.
 *
 * <p>These only arrive here because the service raises them as {@code onError} signals rather than
 * throwing during assembly — an assembly-time throw never reaches an advice at all.
 */
@RestControllerAdvice(assignableTypes = AccountController.class)
public class AccountExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onBadPayload(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(AccountService.ApplicationNotFoundException.class)
    public ProblemDetail onMissingApplication(AccountService.ApplicationNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /**
     * Two concurrent requests for one application both deciding an account number is new. The
     * unique constraint stops that becoming a duplicate row; the loser gets a 409 and can retry,
     * by which point the winner's rows are visible and the retry updates instead of inserting.
     *
     * <p>Also covers a stale application id failing the foreign key.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail onConflict(DataIntegrityViolationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Accounts for this application were modified concurrently. Retry the request.");
    }
}
