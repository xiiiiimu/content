package io.github.xiiiiimu.content.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Calls the Python FastAPI assistant over authenticated internal HTTP.
 * The request timeout must exceed the Python side's worst case: in llm_with_fallback mode Python waits up to
 * LLM timeout x (1 + 1 retry) + backoff (defaults: 10 s -> about 21 s) before answering from its rules fallback.
 * The task runs asynchronously under a 60 s lease, so a 30 s default neither blocks comments nor outlives the lease.
 */
public final class HttpAssistantParser implements AssistantParser {
 public static final Duration DEFAULT_TIMEOUT=Duration.ofSeconds(30);
 private final String baseUrl;private final String token;private final ObjectMapper json;private final HttpClient http;private final Duration timeout;
 public HttpAssistantParser(String baseUrl,String token,ObjectMapper json){this(baseUrl,token,json,DEFAULT_TIMEOUT);}
 public HttpAssistantParser(String baseUrl,String token,ObjectMapper json,Duration timeout){
  this.baseUrl=baseUrl.endsWith("/")?baseUrl.substring(0,baseUrl.length()-1):baseUrl;this.token=token;this.json=json;
  this.timeout=timeout==null||timeout.isZero()||timeout.isNegative()?DEFAULT_TIMEOUT:timeout;
  this.http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
 }
 @Override public Result parse(Request request){
  try{
   String body=json.writeValueAsString(request);
   HttpRequest req=HttpRequest.newBuilder(URI.create(baseUrl+"/v1/parse"))
    .timeout(timeout)
    .header("Content-Type","application/json")
    .header("X-Content-Internal-Token",token)
    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
   HttpResponse<String> resp=http.send(req,HttpResponse.BodyHandlers.ofString());
   if(resp.statusCode()>=300)throw new IllegalStateException("assistant http "+resp.statusCode()+" "+resp.body());
   return fromJson(json.readTree(resp.body()));
  }catch(Exception e){throw new IllegalStateException("assistant parse failed: "+e.getMessage(),e);}
 }
 private Result fromJson(JsonNode n){
  String intent=n.path("intent").asText("UNKNOWN");
  Clarify clarify=null;
  if(n.hasNonNull("clarify"))clarify=new Clarify(n.path("clarify").path("intent").asText(intent),n.path("clarify").path("question").asText());
  SearchCommand search=null;
  if(n.hasNonNull("search")){
   var s=n.get("search");
   search=new SearchCommand(textOrNull(s,"query"),textOrNull(s,"tag"),textOrNull(s,"aspectRatio"),
    boolOrNull(s,"licenseDownload"),boolOrNull(s,"licenseCommercial"));
  }
  SubscribeCommand subscribe=null;
  if(n.hasNonNull("subscribe")){
   var sc=n.get("subscribe");
   subscribe=new SubscribeCommand(sc.path("feedbackCommentId").asLong(),sc.path("action").asText("SUBSCRIBE"),sc.path("target").asText("SELF"));
  }
  GiveawayCommand giveaway=null;
  if(n.hasNonNull("giveaway")){
   var g=n.get("giveaway");
   giveaway=new GiveawayCommand(g.path("ruleTreeJson").asText(),g.path("timezone").asText("Asia/Shanghai"),
    longOrNull(g,"windowStartMs"),longOrNull(g,"deadlineMs"),g.path("winnerCount").asInt(1),
    textOrNull(g,"rewardVersionHint"),g.path("rewardLicense").asText("DOWNLOAD"),textOrNull(g,"summary"));
  }
  List<String> unsupported=new ArrayList<>();
  if(n.has("unsupportedTools") && n.get("unsupportedTools").isArray())n.get("unsupportedTools").forEach(x->unsupported.add(x.asText()));
  return new Result(intent,clarify,search,subscribe,giveaway,unsupported);
 }
 private static String textOrNull(JsonNode n,String f){return n.hasNonNull(f)?n.get(f).asText():null;}
 private static Boolean boolOrNull(JsonNode n,String f){return n.has(f)&&!n.get(f).isNull()?n.get(f).asBoolean():null;}
 private static Long longOrNull(JsonNode n,String f){return n.has(f)&&n.get(f).canConvertToLong()?n.get(f).asLong():null;}
}
