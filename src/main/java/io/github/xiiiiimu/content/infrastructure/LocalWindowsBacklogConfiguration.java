package io.github.xiiiiimu.content.infrastructure;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="content.local.windows-backlog-hint",havingValue="true")
public class LocalWindowsBacklogConfiguration {
 @Bean WebServerFactoryCustomizer<TomcatServletWebServerFactory> localWindowsBacklog(){
  return factory->factory.setProtocol(LocalWindowsNioProtocol.class.getName());
 }
}
