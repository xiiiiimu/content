package io.github.xiiiiimu.content.infrastructure;
import io.micrometer.core.instrument.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.ResultHandler;
import org.apache.coyote.AbstractProtocol;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.context.annotation.*;
import org.springframework.context.event.EventListener;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.jdbc.datasource.*;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="content.sale.latency-enabled",havingValue="true")
public class SaleLatencyConfiguration {
 @Bean static BeanPostProcessor latencyDataSource(){
  return new BeanPostProcessor(){
   @Override public Object postProcessAfterInitialization(Object bean,String name){
    if(!(bean instanceof DataSource ds))return bean;
    return new DelegatingDataSource(ds){
     @Override public Connection getConnection()throws SQLException {
      try(var ignored=SaleLatency.phase("jdbc.acquire")){return instrument(super.getConnection());}
     }
     @Override public Connection getConnection(String username,String password)throws SQLException {
      try(var ignored=SaleLatency.phase("jdbc.acquire")){return instrument(super.getConnection(username,password));}
     }
    };
   }
  };
 }
 private static Connection instrument(Connection connection){
  if(!SaleLatency.active())return connection;
  return (Connection)Proxy.newProxyInstance(SaleLatencyConfiguration.class.getClassLoader(),new Class<?>[]{ConnectionProxy.class},(proxy,method,args)->{
   if(method.getDeclaringClass()==Object.class)return switch(method.getName()){
    case "equals"->proxy==args[0];case "hashCode"->System.identityHashCode(proxy);default->"SaleLatencyConnection";
   };
   if(method.getName().equals("getTargetConnection"))return connection;
   String phase=switch(method.getName()){case "commit"->"jdbc.commit";case "rollback"->"jdbc.rollback";default->"jdbc.other";};
   if(!phase.equals("jdbc.other")){
    try(var ignored=SaleLatency.phase(phase)){return invoke(connection,method,args);}
   }
   return invoke(connection,method,args);
  });
 }
 private static Object invoke(Object target,Method method,Object[] args)throws Throwable{
  try{return method.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}
 }
 @Bean Interceptor latencySql(){return new SqlTiming();}
 @Intercepts({
  @Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}),
  @Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class,org.apache.ibatis.cache.CacheKey.class,org.apache.ibatis.mapping.BoundSql.class}),
  @Signature(type=Executor.class,method="update",args={MappedStatement.class,Object.class})
 })
 public static class SqlTiming implements Interceptor {
  @Override public Object intercept(Invocation invocation)throws Throwable{
   String id=((MappedStatement)invocation.getArgs()[0]).getId();
   try(var ignored=SaleLatency.phase("sql."+id)){return invocation.proceed();}
  }
 }
 @Bean FilterRegistrationBean<Filter> saleLatencyFilter(){
  SaleLatency.enableAcceptCapture();
  Filter filter=(req,res,chain)->{
   var request=(HttpServletRequest)req;var response=(HttpServletResponse)res;
   String path=request.getRequestURI();boolean purchase=path.matches("/api/sale-campaigns/[0-9]+/purchase");
   if(!purchase){chain.doFilter(req,res);return;}
   long campaign=0;try{campaign=Long.parseLong(path.split("/")[3]);}catch(NumberFormatException ignored){}
   try(var ignored=SaleLatency.begin(request.getHeader("X-Diag-Id"),"http",campaign,request.getRemotePort())){
    try{chain.doFilter(req,res);}finally{SaleLatency.status(response.getStatus());}
   }
  };
  var registration=new FilterRegistrationBean<>(filter);registration.setOrder(Ordered.HIGHEST_PRECEDENCE+1);return registration;
 }
 @EventListener void queues(ServletWebServerInitializedEvent event){
  if(!(event.getWebServer() instanceof TomcatWebServer server))return;
  var registry=event.getApplicationContext().getBean(MeterRegistry.class);
  for(var connector:server.getTomcat().getService().findConnectors()){
   if(connector.getProtocolHandler() instanceof AbstractProtocol<?> protocol){
    Gauge.builder("sale.http.queue.depth",protocol,p->{
     var executor=p.getExecutor();
     if(executor instanceof org.apache.tomcat.util.threads.ThreadPoolExecutor pool)return pool.getQueue().size();
     if(executor instanceof ThreadPoolExecutor pool)return pool.getQueue().size();
     return Double.NaN;
    }).register(registry);
   }
  }
 }
}

