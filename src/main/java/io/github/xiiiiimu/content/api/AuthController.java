package io.github.xiiiiimu.content.api;

import io.github.xiiiiimu.content.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.Map;

@RestController @ConditionalOnProperty(name="content.feed.enabled",havingValue="true")
public class AuthController {
 private final AuthService auth;
 public AuthController(AuthService auth){this.auth=auth;}
 @PostMapping("/api/auth/register") public AuthService.Session register(@RequestBody Map<String,String> body){
  return auth.register(body.get("username"),body.get("password"));
 }
 @PostMapping("/api/auth/login") public AuthService.Session login(@RequestBody Map<String,String> body){
  return auth.login(body.get("username"),body.get("password"));
 }
 @GetMapping("/api/auth/me") public AuthService.PublicUser me(Principal p){
  return auth.me(user(p));
 }
 private static long user(Principal p){
  if(p==null||p.getName()==null)throw new FeedFailure(401,"UNAUTHENTICATED");
  try{long id=Long.parseLong(p.getName());if(id<=0)throw new FeedFailure(401,"UNAUTHENTICATED");return id;}
  catch(NumberFormatException e){throw new FeedFailure(401,"UNAUTHENTICATED");}
 }
}
