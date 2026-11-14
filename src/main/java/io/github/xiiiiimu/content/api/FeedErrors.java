package io.github.xiiiiimu.content.api;
import io.github.xiiiiimu.content.domain.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessException;

@RestControllerAdvice(assignableTypes={FeedController.class,PostController.class,InteractionController.class,TrendGuardController.class,MediaController.class,MediaObjectController.class,AuthController.class,PostAuthoringController.class,NotificationController.class,MaterialController.class})
public class FeedErrors {
 public record Error(String code){}
 @ExceptionHandler(FeedFailure.class) ResponseEntity<Error> known(FeedFailure e){return ResponseEntity.status(e.status).body(new Error(e.code));}
 @ExceptionHandler({FeedMerger.Unavailable.class,DataAccessException.class}) ResponseEntity<Error> unavailable(Exception e){org.slf4j.LoggerFactory.getLogger(FeedErrors.class).warn("Feed unavailable",e);return ResponseEntity.status(503).body(new Error("FEED_UNAVAILABLE"));}
 @ExceptionHandler({IllegalArgumentException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,org.springframework.http.converter.HttpMessageNotReadableException.class}) ResponseEntity<Error> bad(Exception e){return ResponseEntity.badRequest().body(new Error("INVALID_REQUEST"));}
}
