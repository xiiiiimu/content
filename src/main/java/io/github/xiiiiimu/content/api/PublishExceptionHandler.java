package io.github.xiiiiimu.content.api;

import io.github.xiiiiimu.content.domain.PublishFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = PublishController.class)
public class PublishExceptionHandler {
    @ExceptionHandler(io.github.xiiiiimu.content.domain.FeedFailure.class)
    ResponseEntity<Error> maintenance(io.github.xiiiiimu.content.domain.FeedFailure e) { return ResponseEntity.status(e.status).body(new Error(e.code)); }
    private static final Logger LOG = LoggerFactory.getLogger(PublishExceptionHandler.class);
    public record Error(String code) {}
    @ExceptionHandler(PublishFailure.class)
    ResponseEntity<Error> failure(PublishFailure e) {
        int status = switch (e.reason()) {
            case AUTHENTICATION_REQUIRED -> 401;
            case INVALID_REQUEST -> 400;
            case POST_NOT_FOUND -> 404;
            case POST_FORBIDDEN -> 403;
            default -> 409;
        };
        return ResponseEntity.status(status).body(new Error(e.reason().name()));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<Error> malformed(Exception e) {
        return ResponseEntity.badRequest().body(new Error("INVALID_REQUEST"));
    }
    @ExceptionHandler({DataAccessException.class, IllegalStateException.class, ArithmeticException.class})
    ResponseEntity<Error> persistenceFailure(Exception e) {
        LOG.error("Publication transaction failed", e);
        return ResponseEntity.internalServerError().body(new Error("PUBLISH_FAILED"));
    }
}
