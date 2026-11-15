package io.github.xiiiiimu.content.infrastructure;

import io.github.xiiiiimu.content.domain.SaleFaultHooks;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.io.IOException;

/** Counts HTTP in-flight for commerce load measurement (separate from settle in-flight). */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnProperty(name="content.sale.fault-hooks",havingValue="true")
public final class SaleHttpInFlightFilter implements Filter {
 private final SaleFaultHooks faults;
 public SaleHttpInFlightFilter(SaleFaultHooks faults){this.faults=faults;}

 @Override public void doFilter(ServletRequest req,ServletResponse res,FilterChain chain)throws IOException,ServletException{
  HttpServletRequest http=(HttpServletRequest)req;
  String path=http.getRequestURI()==null?"":http.getRequestURI();
  boolean track=path.startsWith("/api/") && !path.startsWith("/api/sale-debug/");
  if(track)faults.enterHttpInFlight();
  try{chain.doFilter(req,res);}
  finally{if(track)faults.leaveHttpInFlight();}
 }
}
