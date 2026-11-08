package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class DistributionInbox {
 private final InboxMapper inbox;private final FeedMapper db;private final TransactionTemplate tx;private final ObjectMapper json;private final PipelineObserver observer;
 public DistributionInbox(InboxMapper inbox,FeedMapper db,TransactionTemplate tx,ObjectMapper json,PipelineObserver observer){this.inbox=inbox;this.db=db;this.tx=tx;this.json=json.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);this.observer=observer;}
 public void accept(String payload,String key,String tag){
  if(payload==null || payload.getBytes(StandardCharsets.UTF_8).length>65536)throw new FeedFailure(409,"INVALID_EVENT");
  String hash=digest(payload);long id=0;
  try{
   JsonNode body=json.readTree(payload);id=number(body,"eventId");if(!Long.toString(id).equals(key))throw new FeedFailure(409,"EVENT_KEY_CONFLICT");
   String type=body.has("eventType")?body.path("eventType").asText():"PostPublished";
   if(!type.equals(tag) || !Set.of("PostPublished","FollowChanged","FeedModeChanged","PostOfflined").contains(type))throw new FeedFailure(409,"EVENT_TYPE_CONFLICT");
   final long eventId=id;
   tx.executeWithoutResult(status->{
    RelationshipService.requireWritable(db.gate());
    var stored=inbox.event(eventId);
    try{if(stored==null || !stored.type().equals(type) || !json.readTree(stored.payload()).equals(body))throw new FeedFailure(409,"OUTBOX_EVENT_CONFLICT");}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
    String decision=decision(type,body);
    if(inbox.accept(eventId,type,hash,decision)==0){var old=inbox.receipt(eventId);if(old==null || !hash.equals(old.hash()) || !type.equals(old.type()) || !decision.equals(old.decision()))throw new FeedFailure(409,"INBOX_EVENT_CONFLICT");verifyTask(eventId,decision,body);return;}
    if(type.equals("PostPublished")){
     long post=number(body,"postId"),author=number(body,"authorId");var p=db.post(post);
     if(p==null || p.time()==null || p.sequence()==null || p.epoch()==null || stored.aggregate()!=post || p.authorId()!=author || p.time()!=nonnegative(body,"publishedAtMs") || !p.mode().equals(body.path("deliveryMode").asText())
         || p.epoch()!=nonnegative(body,"deliveryEpoch") || p.sequence()!=number(body,"publishSeq") || p.version()<number(body,"postVersion"))throw new FeedFailure(409,"PUBLICATION_EVENT_CONFLICT");
     if(decision.equals("PUSH_TASK"))inbox.createTask(eventId,author,p.epoch(),"PUBLISH",post,null,null,payload);
    }else if(type.equals("FollowChanged") && decision.equals("HISTORY_TASK")){
     inbox.createTask(eventId,number(body,"authorId"),0,"BACKFILL",null,number(body,"followerId"),number(body,"followEpoch"),payload);
    }
    observer.beforeInboxCommit(eventId);
   });
   // The listener only ACKs after this method returns, after the transaction commit.
   observer.afterInboxCommit(eventId);
  }catch(Exception failure){
   if(failure instanceof org.springframework.dao.DataIntegrityViolationException)failure=new FeedFailure(409,"TASK_PERSISTENCE_CONFLICT");
   if(failure instanceof FeedFailure f && f.status==409){
    long conflictId=id;String reason=f.code;
    tx.executeWithoutResult(status->inbox.conflict(conflictId,hash,reason,payload));
    org.slf4j.LoggerFactory.getLogger(DistributionInbox.class).error("Feed event requires reconciliation eventId={} reason={} hash={}",id,reason,hash);
   }
   if(failure instanceof RuntimeException r)throw r;throw new IllegalStateException("Invalid MQ event",failure);
  }
 }
 private void verifyTask(long id,String decision,JsonNode body){
  var task=inbox.task(id);
  if(!decision.equals("PUSH_TASK") && !decision.equals("HISTORY_TASK")){if(task!=null)throw new FeedFailure(409,"UNEXPECTED_TASK_CONFLICT");return;}
  if(task==null || task.payload()==null || task.author()!=number(body,"authorId"))throw new FeedFailure(409,"MISSING_TASK_CONFLICT");
  try{if(!json.readTree(task.payload()).equals(body))throw new FeedFailure(409,"TASK_PAYLOAD_CONFLICT");}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new FeedFailure(409,"TASK_PAYLOAD_CONFLICT");}
  if(decision.equals("PUSH_TASK")){
   if(!task.kind().equals("PUBLISH") || !Objects.equals(task.post(),number(body,"postId")) || task.epoch()!=nonnegative(body,"deliveryEpoch") || task.follower()!=null || task.followEpoch()!=null)throw new FeedFailure(409,"TASK_METADATA_CONFLICT");
  }else if(!task.kind().equals("BACKFILL") || task.post()!=null || !Objects.equals(task.follower(),number(body,"followerId")) || !Objects.equals(task.followEpoch(),number(body,"followEpoch")))throw new FeedFailure(409,"TASK_METADATA_CONFLICT");
 }
 private String decision(String type,JsonNode body){return switch(type){
  case "PostPublished" -> switch(body.path("deliveryMode").asText()){case "PUSH"->"PUSH_TASK";case "PULL"->"PULL_NO_FANOUT";default->throw new FeedFailure(409,"INVALID_DELIVERY_MODE");};
  case "FollowChanged" -> {if(!body.path("following").isBoolean())throw new FeedFailure(409,"INVALID_EVENT");yield body.path("following").booleanValue()?"HISTORY_TASK":"FOLLOW_CANCELLED";}
  case "FeedModeChanged" -> "MODE_ACCEPTED";
  case "PostOfflined" -> "OFFLINE_ACCEPTED";
  default->throw new FeedFailure(409,"INVALID_EVENT");
 };}
 private static long number(JsonNode body,String name){long value=nonnegative(body,name);if(value==0)throw new FeedFailure(409,"INVALID_EVENT");return value;}
 private static long nonnegative(JsonNode body,String name){var value=body.get(name);if(value==null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue()<0)throw new FeedFailure(409,"INVALID_EVENT");return value.longValue();}
 public static String digest(String payload){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
