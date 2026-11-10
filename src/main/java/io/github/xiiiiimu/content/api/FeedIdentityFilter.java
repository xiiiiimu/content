package io.github.xiiiiimu.content.api;

import io.github.xiiiiimu.content.domain.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.security.Principal;

/** Trust boundary: configured server key and OS-controlled mint command, never a reader-id request header. */
public final class FeedIdentityFilter extends OncePerRequestFilter {
 private final FeedTokens tokens;
 public FeedIdentityFilter(String key){tokens=new FeedTokens(key,"IDENTITY");}
 @Override protected boolean shouldNotFilter(HttpServletRequest request){return !request.getRequestURI().startsWith("/api/");}
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
  String header=request.getHeader("Authorization");if(header==null){chain.doFilter(request,response);return;}
  HttpServletRequest authenticated;
  try{
   if(!header.startsWith("Bearer "))throw new IllegalArgumentException();
   var identity=tokens.verify(header.substring(7),FeedTokens.Identity.class);long now=System.currentTimeMillis();
   if(identity.version()!=1 || identity.reader()<=0 || identity.issuedAt()<=0 || identity.issuedAt()>now+30_000 || identity.expiresAt()<=now
      || identity.expiresAt()<=identity.issuedAt() || identity.expiresAt()-identity.issuedAt()>3_600_000)throw new IllegalArgumentException();
   String role=identity.role()==null||identity.role().isBlank()?"READER":identity.role();
   if(!"READER".equals(role) && !"TRENDGUARD_OPS".equals(role))throw new IllegalArgumentException();
   var principal=new ContentPrincipal(identity.reader(),role);
   if(request.getUserPrincipal()!=null && !request.getUserPrincipal().getName().equals(principal.getName()))throw new IllegalArgumentException();
   authenticated=new HttpServletRequestWrapper(request){@Override public Principal getUserPrincipal(){return principal;}@Override public String getRemoteUser(){return principal.getName();}};
  }catch(FeedFailure | IllegalArgumentException e){response.setStatus(401);response.setContentType("application/json");response.getWriter().write("{\"code\":\"AUTHENTICATION_REQUIRED\"}");return;}
  chain.doFilter(authenticated,response);
 }
}
