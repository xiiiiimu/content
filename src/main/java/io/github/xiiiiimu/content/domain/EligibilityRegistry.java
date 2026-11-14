package io.github.xiiiiimu.content.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.xiiiiimu.content.infrastructure.*;
import java.util.*;

/**
 * Extensible eligibility checkers. PURCHASE is registered but disabled until an order source exists.
 */
public final class EligibilityRegistry {
 public interface Checker {
  String type();
  boolean enabled();
  /** Accounts that currently satisfy this tool for the giveaway post/window. */
  Set<Long> qualifyingAccounts(long postId,long authorId,Long windowStartMs,Long deadlineMs,JsonNode params);
 }

 private final Map<String,Checker> checkers=new LinkedHashMap<>();
 private final ObjectMapper json;

 public EligibilityRegistry(InteractionMapper interactions,GiveawayMapper giveaways,FeedMapper posts,ObjectMapper json){
  this(interactions,giveaways,posts,json,null);
 }
 public EligibilityRegistry(InteractionMapper interactions,GiveawayMapper giveaways,FeedMapper posts,ObjectMapper json,SaleMapper sales){
  this.json=json;
  register(new Checker(){
   public String type(){return "LIKE";}
   public boolean enabled(){return true;}
   public Set<Long> qualifyingAccounts(long postId,long authorId,Long windowStartMs,Long deadlineMs,JsonNode params){
    return new LinkedHashSet<>(interactions.likedUsers(postId));
   }
  });
  register(new Checker(){
   public String type(){return "COMMENT";}
   public boolean enabled(){return true;}
   public Set<Long> qualifyingAccounts(long postId,long authorId,Long windowStartMs,Long deadlineMs,JsonNode params){
    return new LinkedHashSet<>(interactions.commentedUsers(postId,windowStartMs,deadlineMs));
   }
  });
  register(new Checker(){
   public String type(){return "FAVORITE";}
   public boolean enabled(){return true;}
   public Set<Long> qualifyingAccounts(long postId,long authorId,Long windowStartMs,Long deadlineMs,JsonNode params){
    return new LinkedHashSet<>(giveaways.favoritedUsers(postId));
   }
  });
  register(new Checker(){
   public String type(){return "FOLLOW";}
   public boolean enabled(){return true;}
   public Set<Long> qualifyingAccounts(long postId,long authorId,Long windowStartMs,Long deadlineMs,JsonNode params){
    return new LinkedHashSet<>(posts.followerIds(authorId));
   }
  });
  register(new Checker(){
   public String type(){return "PURCHASE";}
   public boolean enabled(){return sales!=null;}
   public Set<Long> qualifyingAccounts(long postId,long authorId,Long windowStartMs,Long deadlineMs,JsonNode params){
    if(sales==null)throw new FeedFailure(409,"PURCHASE_TOOL_DISABLED");
    try{
     Long scopePost=postId;
     if(params!=null && params.has("postId") && params.get("postId").canConvertToLong())scopePost=params.get("postId").longValue();
     return new LinkedHashSet<>(sales.purchaseEligible(authorId,scopePost,windowStartMs,deadlineMs));
    }catch(FeedFailure e){throw e;}
    catch(RuntimeException e){throw new FeedFailure(409,"PURCHASE_SOURCE_UNAVAILABLE");}
   }
  });
 }

 public void register(Checker checker){checkers.put(checker.type(),checker);}
 public Checker require(String type){
  var c=checkers.get(type);if(c==null)throw new FeedFailure(400,"UNKNOWN_ELIGIBILITY_TOOL");
  if(!c.enabled())throw new FeedFailure(409,"ELIGIBILITY_TOOL_DISABLED");
  return c;
 }
 public Set<String> enabledTypes(){
  Set<String> out=new LinkedHashSet<>();
  for(var e:checkers.entrySet())if(e.getValue().enabled())out.add(e.getKey());
  return out;
 }

 /** Evaluate ALL / ANY / AT_LEAST rule tree; returns deduped account ids excluding author/assistant. */
 public Set<Long> evaluate(JsonNode tree,long postId,long authorId,long assistantUserId,Long windowStartMs,Long deadlineMs){
  Set<Long> raw=evalNode(tree,postId,authorId,windowStartMs,deadlineMs);
  raw.remove(authorId);raw.remove(assistantUserId);raw.remove(0L);
  return raw;
 }

 private Set<Long> evalNode(JsonNode node,long postId,long authorId,Long windowStartMs,Long deadlineMs){
  if(node==null || node.isNull())throw new FeedFailure(400,"INVALID_RULE_TREE");
  if(node.has("type")){
   var checker=require(node.path("type").asText());
   return checker.qualifyingAccounts(postId,authorId,windowStartMs,deadlineMs,node.path("params"));
  }
  String op=node.path("op").asText("ALL");
  var children=node.path("children");
  if(!children.isArray() || children.isEmpty())throw new FeedFailure(400,"INVALID_RULE_TREE");
  List<Set<Long>> sets=new ArrayList<>();
  for(JsonNode child:children)sets.add(evalNode(child,postId,authorId,windowStartMs,deadlineMs));
  return switch(op){
   case "ALL" -> intersect(sets);
   case "ANY" -> union(sets);
   case "AT_LEAST" -> atLeast(sets,node.path("k").asInt(0));
   default -> throw new FeedFailure(400,"INVALID_RULE_OP");
  };
 }

 private static Set<Long> intersect(List<Set<Long>> sets){
  Set<Long> out=new LinkedHashSet<>(sets.get(0));
  for(int i=1;i<sets.size();i++)out.retainAll(sets.get(i));
  return out;
 }
 private static Set<Long> union(List<Set<Long>> sets){
  Set<Long> out=new LinkedHashSet<>();
  for(var s:sets)out.addAll(s);
  return out;
 }
 private static Set<Long> atLeast(List<Set<Long>> sets,int k){
  if(k<=0 || k>sets.size())throw new FeedFailure(400,"INVALID_AT_LEAST_K");
  Map<Long,Integer> counts=new HashMap<>();
  for(var s:sets)for(Long id:s)counts.merge(id,1,Integer::sum);
  Set<Long> out=new LinkedHashSet<>();
  for(var e:counts.entrySet())if(e.getValue()>=k)out.add(e.getKey());
  return out;
 }

 public void validateTreeOrThrow(JsonNode tree){
  evaluateEnabledOnly(tree);
 }
 private void evaluateEnabledOnly(JsonNode node){
  if(node.has("type")){require(node.path("type").asText());return;}
  String op=node.path("op").asText();
  if(!Set.of("ALL","ANY","AT_LEAST").contains(op))throw new FeedFailure(400,"INVALID_RULE_OP");
  var children=node.path("children");
  if(!children.isArray() || children.isEmpty() || children.size()>16)throw new FeedFailure(400,"INVALID_RULE_TREE");
  if("AT_LEAST".equals(op)){
   int k=node.path("k").asInt(0);if(k<=0 || k>children.size())throw new FeedFailure(400,"INVALID_AT_LEAST_K");
  }
  Set<String> seen=new HashSet<>();
  for(JsonNode child:children){
   if(child.has("type")){
    String t=child.path("type").asText();
    if(!seen.add(t))throw new FeedFailure(400,"DUPLICATE_RULE_CONDITION");
   }
   evaluateEnabledOnly(child);
  }
 }
}
