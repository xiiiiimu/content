package io.github.xiiiiimu.content.api;
import java.security.Principal;

/** Authenticated API caller: reader id + role from signed identity token. */
public record ContentPrincipal(long reader,String role) implements Principal {
 @Override public String getName(){return Long.toString(reader);}
 public boolean trendGuardOps(){return "TRENDGUARD_OPS".equals(role);}
}
