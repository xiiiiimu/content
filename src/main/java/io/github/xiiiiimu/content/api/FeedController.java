package io.github.xiiiiimu.content.api;
import io.github.xiiiiimu.content.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;

@RestController @ConditionalOnProperty(name="content.feed.enabled",havingValue="true")
public class FeedController {
 private final FollowingFeed feed;private final RelationshipService relationships;private final FeedModes modes;
 public FeedController(FollowingFeed feed,RelationshipService relationships,FeedModes modes){this.feed=feed;this.relationships=relationships;this.modes=modes;}
 public static long reader(Principal p){try{long id=Long.parseLong(p.getName());if(id<=0)throw new NumberFormatException();return id;}catch(Exception e){throw new FeedFailure(401,"AUTHENTICATION_REQUIRED");}}
 @GetMapping("/api/feed/following") public FollowingFeed.Response feed(@RequestParam(defaultValue="20")int limit,@RequestParam(required=false)String cursor,Principal p){return feed.read(reader(p),limit,cursor);}
 @PutMapping("/api/follows/{author}") public RelationshipService.Result follow(@PathVariable long author,Principal p){return relationships.change(reader(p),author,true);}
 @DeleteMapping("/api/follows/{author}") public RelationshipService.Result unfollow(@PathVariable long author,Principal p){return relationships.change(reader(p),author,false);}
 @PostMapping("/api/posts/{post}/offline") public void offline(@PathVariable long post,@RequestBody com.fasterxml.jackson.databind.JsonNode body,Principal p){long actor=reader(p);var expected=body.get("expectedVersion");if(!body.isObject() || body.size()!=1 || expected==null || !expected.isIntegralNumber() || !expected.canConvertToLong())throw new FeedFailure(400,"INVALID_REQUEST");relationships.offline(actor,post,expected.longValue());}
 @PutMapping("/api/authors/me/feed-mode") public FeedModes.Result mode(@RequestBody com.fasterxml.jackson.databind.JsonNode body,Principal principal){
  long id=reader(principal);var value=body.get("mode");var expected=body.get("expectedEpoch");
  if(!body.isObject() || body.size()!=2 || value==null || !value.isTextual() || expected==null || !expected.isIntegralNumber() || !expected.canConvertToLong())throw new FeedFailure(400,"INVALID_REQUEST");
  return modes.change(id,value.textValue(),expected.longValue());
 }
}
