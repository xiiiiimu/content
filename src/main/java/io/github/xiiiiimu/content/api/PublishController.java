package io.github.xiiiiimu.content.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.xiiiiimu.content.domain.Publication;
import io.github.xiiiiimu.content.domain.PublishFailure;
import io.github.xiiiiimu.content.domain.PublishService;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

import static io.github.xiiiiimu.content.domain.PublishFailure.Reason.*;

@RestController
public class PublishController {
    private final PublishService service;
    public PublishController(PublishService service) { this.service = service; }

    @PostMapping("/api/posts/{postId}/publish")
    public Publication publish(@PathVariable long postId,
                               @RequestHeader("Idempotency-Key") String key,
                               @RequestBody JsonNode body, Principal principal) {
        if (principal == null) throw new PublishFailure(AUTHENTICATION_REQUIRED);
        long authorId;
        try { authorId = Long.parseLong(principal.getName()); }
        catch (NumberFormatException e) { throw new PublishFailure(AUTHENTICATION_REQUIRED); }
        if (authorId <= 0) throw new PublishFailure(AUTHENTICATION_REQUIRED);
        JsonNode version = body.get("expectedVersion");
        if (!body.isObject() || body.size() != 1 || version == null || !version.isIntegralNumber()
                || !version.canConvertToLong() || version.longValue() < 0)
            throw new PublishFailure(INVALID_REQUEST);
        return service.publish(authorId, postId, key, version.longValue());
    }
}
