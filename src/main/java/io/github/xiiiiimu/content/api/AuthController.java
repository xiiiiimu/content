package io.github.xiiiiimu.content.api;

import io.github.xiiiiimu.content.domain.*;
import io.github.xiiiiimu.content.infrastructure.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.Map;

@RestController @ConditionalOnProperty(name="content.feed.enabled",havingValue="true")
public class AuthController {
 private final AuthService auth;private final RateLimiter limits;
 public AuthController(AuthService auth,RateLimiter limits){this.auth=auth;this.limits=limits;}
 /**
  * Hardening A2: register 10/h per client IP; login 20/min per client IP and 5/min per username, every attempt counts.
  * The client IP is the socket peer address: the server binds 127.0.0.1 with no reverse proxy, so no forwarding header is trusted.
  */
 @PostMapping("/api/auth/register") public AuthService.Session register(@RequestBody Map<String,String> body,HttpServletRequest request){
  limits.acquire("register-ip",request.getRemoteAddr());
  return auth.register(body.get("username"),body.get("password"));
 }
 @PostMapping("/api/auth/login") public AuthService.Session login(@RequestBody Map<String,String> body,HttpServletRequest request){
  limits.acquire("login-ip",request.getRemoteAddr());
  String username=body.get("username");
  if(username!=null)limits.acquire("login-user",RateLimiter.hashed(username.strip().toLowerCase(java.util.Locale.ROOT)));
  return auth.login(username,body.get("password"));
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
