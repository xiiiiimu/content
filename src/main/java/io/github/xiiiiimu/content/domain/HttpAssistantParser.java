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

/** Calls the Python FastAPI assistant over authenticated internal HTTP. */
public final class HttpAssistantParser implements AssistantParser {
 private final String baseUrl;private final String token;private final ObjectMapper json;private final HttpClient http;
 public HttpAssistantParser(String baseUrl,String token,ObjectMapper json){
  this.baseUrl=baseUrl.endsWith("/")?baseUrl.substring(0,baseUrl.length()-1):baseUrl;this.token=token;this.json=json;
  this.http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
 }
 @Override public Result parse(Request request){
  try{
   String body=json.writeValueAsString(request);
   HttpRequest req=HttpRequest.newBuilder(URI.create(baseUrl+"/v1/parse"))
    .timeout(Duration.ofSeconds(20))
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
  if(n.hasNonNull("subscribe"))subscribe=new SubscribeCommand(n.path("subscribe").path("feedbackCommentId").asLong());
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
