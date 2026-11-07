package io.github.xiiiiimu.content.api;
import io.github.xiiiiimu.content.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;

@RestController @ConditionalOnProperty(name="content.feed.enabled",havingValue="true")
public class FeedController {
 private final FollowingFeed feed;private final RelationshipService relationships;
 public FeedController(FollowingFeed feed,RelationshipService relationships){this.feed=feed;this.relationships=relationships;}
 public static long reader(Principal p){try{long id=Long.parseLong(p.getName());if(id<=0)throw new NumberFormatException();return id;}catch(Exception e){throw new FeedFailure(401,"AUTHENTICATION_REQUIRED");}}
 @GetMapping("/api/feed/following") public FollowingFeed.Response feed(@RequestParam(defaultValue="20")int limit,@RequestParam(required=false)String cursor,Principal p){return feed.read(reader(p),limit,cursor);}
 @PutMapping("/api/follows/{author}") public RelationshipService.Result follow(@PathVariable long author,Principal p){return relationships.change(reader(p),author,true);}
 @DeleteMapping("/api/follows/{author}") public RelationshipService.Result unfollow(@PathVariable long author,Principal p){return relationships.change(reader(p),author,false);}
 @PostMapping("/api/posts/{post}/offline") public void offline(@PathVariable long post,@RequestBody Version body,Principal p){relationships.offline(reader(p),post,body.expectedVersion());}
 public record Version(long expectedVersion) {}
}
