package io.github.xiiiiimu.content.worker;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Polls media + material version workers when content.media.enabled. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="content.media.enabled",havingValue="true")
@EnableScheduling
public class MediaScanConfiguration {
 private final Runnable mediaRunner;
 public MediaScanConfiguration(@Qualifier("mediaRunner") Runnable mediaRunner){this.mediaRunner=mediaRunner;}
 @Scheduled(fixedDelayString="${content.media.scan-delay-millis:1000}")
 public void scan(){
  try{mediaRunner.run();}
  catch(Exception e){org.slf4j.LoggerFactory.getLogger(MediaScanConfiguration.class).error("Media scan failed; pending tasks remain",e);}
 }
}
