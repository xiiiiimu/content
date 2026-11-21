package io.github.xiiiiimu.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.xiiiiimu.content.domain.AssistantParser;
import io.github.xiiiiimu.content.domain.HttpAssistantParser;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * The Python parser in llm_with_fallback mode can take up to timeout x (1 + 1 retry) + backoff before answering
 * from its rules fallback (default 10 s per attempt -> about 21 s). Java's call must wait longer than that, or a
 * task that Python would have answered fails and is retried. The fake parser answers after 22 s.
 */
class HttpAssistantParserTimeoutTest {
 static final long PYTHON_WORST_CASE_MS=22_000;
 HttpServer server;

 HttpAssistantParser slowParser(long delayMs)throws Exception{return slowParser(delayMs,null);}
 HttpAssistantParser slowParser(long delayMs,java.time.Duration timeout)throws Exception{
  server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/v1/parse",ex->{
   ex.getRequestBody().readAllBytes();
   try{Thread.sleep(delayMs);}catch(InterruptedException e){Thread.currentThread().interrupt();}
   byte[] b="{\"intent\":\"UNKNOWN\",\"clarify\":{\"intent\":\"UNKNOWN\",\"question\":\"q\"},\"source\":\"rules_fallback\",\"fallbackReason\":\"TIMEOUT\"}".getBytes(StandardCharsets.UTF_8);
   ex.getResponseHeaders().add("Content-Type","application/json");
   ex.sendResponseHeaders(200,b.length);
   try(var os=ex.getResponseBody()){os.write(b);}
  });
  server.start();
  String url="http://127.0.0.1:"+server.getAddress().getPort();
  return timeout==null?new HttpAssistantParser(url,"t",new ObjectMapper()):new HttpAssistantParser(url,"t",new ObjectMapper(),timeout);
 }

 @AfterEach void stop(){if(server!=null)server.stop(0);}

 static AssistantParser.Request request(){
  return new AssistantParser.Request(1,1,1,null,"@助手 你好",false,Map.of());
 }

 @Test void waitsLongerThanThePythonLlmWorstCase()throws Exception{
  var result=slowParser(PYTHON_WORST_CASE_MS).parse(request());
  assertThat(result.intent()).isEqualTo("UNKNOWN");
  assertThat(result.clarify()).isNotNull();
 }
 @Test void configuredTimeoutIsHonoured()throws Exception{
  var parser=slowParser(3_000,java.time.Duration.ofSeconds(1));
  assertThatThrownBy(()->parser.parse(request())).hasMessageContaining("timed out");
 }
}
