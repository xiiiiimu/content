package io.github.xiiiiimu.content.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic stand-in for Python intent parsing (IT + offline demo). */
public final class StubAssistantParser implements AssistantParser {
 private static final Pattern MENTION=Pattern.compile("@助手|@assistant",Pattern.CASE_INSENSITIVE);

 @Override public Result parse(Request request){
  String raw=request.body()==null?"":request.body();
  String text=MENTION.matcher(raw).replaceAll(" ").trim();
  if(text.isBlank())return new Result("UNKNOWN",new Clarify("UNKNOWN","请说明要找素材、蹲改进还是抽奖。"),null,null,null,List.of());

  if(containsAny(text,"蹲","订阅","改进","修改结果")){
   Long feedback=request.parentCommentId();
   if(feedback==null)return new Result("SUBSCRIBE",new Clarify("SUBSCRIBE","请在目标反馈评论下回复「@助手 蹲这条的修改结果」。"),null,null,null,List.of());
   return new Result("SUBSCRIBE",null,null,new SubscribeCommand(feedback),null,List.of());
  }

  if(containsAny(text,"抽","开奖","抽取","抽奖")){
   if(!request.actorIsAuthor())return new Result("GIVEAWAY",new Clarify("GIVEAWAY","只有作者可以发起抽奖。"),null,null,null,List.of());
   List<String> tools=new ArrayList<>();
   boolean like=containsAny(text,"点赞");
   boolean comment=containsAny(text,"评论");
   boolean favorite=containsAny(text,"收藏");
   boolean follow=containsAny(text,"关注");
   boolean purchase=containsAny(text,"购买","买过","下单");
   if(purchase)return new Result("GIVEAWAY",new Clarify("GIVEAWAY","历史购买工具尚未接通，不能静默忽略该条件。请去掉购买条件或等待订单源启用。"),null,null,null,List.of("PURCHASE"));
   if(!(like||comment||favorite||follow))
    return new Result("GIVEAWAY",new Clarify("GIVEAWAY","请说明参与条件（点赞/评论/收藏/关注）以及截止时间和中奖人数。"),null,null,null,List.of());
   String mode="ALL";
   int atLeast=0;
   if(containsAny(text,"任一","或者","或"))mode="ANY";
   Matcher k=Pattern.compile("至少\\s*(\\d+)|任选\\s*(\\d+)").matcher(text);
   if(k.find()){mode="AT_LEAST";atLeast=Integer.parseInt(k.group(1)!=null?k.group(1):k.group(2));}
   // "选两项" without 至少/任选 → clarify
   if(text.contains("选两") && !"AT_LEAST".equals(mode) && !text.contains("指定"))
    return new Result("GIVEAWAY",new Clarify("GIVEAWAY","「选两项」是指定两项全部满足，还是参与者任意满足两项？"),null,null,null,List.of());
   Long deadline=parseDeadlineMs(text);
   if(deadline==null)return new Result("GIVEAWAY",new Clarify("GIVEAWAY","请给出明确截止时间，例如「今晚20点」或 ISO 时间。"),null,null,null,List.of());
   int winners=1;
   Matcher wm=Pattern.compile("抽\\s*(\\d+)\\s*人|(\\d+)\\s*人").matcher(text);
   if(wm.find())winners=Integer.parseInt(wm.group(1)!=null?wm.group(1):wm.group(2));
   String license=containsAny(text,"商用")?"COMMERCIAL":"DOWNLOAD";
   StringBuilder tree=new StringBuilder("{\"op\":\"").append(mode).append("\"");
   if("AT_LEAST".equals(mode))tree.append(",\"k\":").append(atLeast);
   tree.append(",\"children\":[");
   boolean first=true;
   if(like){tree.append("{\"type\":\"LIKE\"}");first=false;}
   if(comment){if(!first)tree.append(',');tree.append("{\"type\":\"COMMENT\"}");first=false;}
   if(favorite){if(!first)tree.append(',');tree.append("{\"type\":\"FAVORITE\"}");first=false;}
   if(follow){if(!first)tree.append(',');tree.append("{\"type\":\"FOLLOW\"}");}
   tree.append("]}");
   String summary="截止 "+deadline+"；资格在封池时核验；奖励最新 READY 默认版；人数 "+winners+"；许可 "+license;
   return new Result("GIVEAWAY",null,null,null,new GiveawayCommand(tree.toString(),"Asia/Shanghai",null,deadline,winners,"DEFAULT",license,summary),tools);
  }

  // default: search
  String aspect=null;
  if(containsAny(text,"竖屏","竖版"))aspect="PORTRAIT";
  else if(containsAny(text,"横屏","横版"))aspect="LANDSCAPE";
  else if(containsAny(text,"方形","正方形"))aspect="SQUARE";
  Boolean download=containsAny(text,"可下载","允许下载")?true:null;
  Boolean commercial=containsAny(text,"商用","允许商用")?true:null;
  String tag=null;
  Matcher tm=Pattern.compile("标签[:：]?\\s*(\\S+)|#(\\S+)").matcher(text);
  if(tm.find())tag=tm.group(1)!=null?tm.group(1):tm.group(2);
  String q=text.replaceAll("找|搜索|素材|竖屏|横屏|方形|允许商用|可下载|商用|下载", " ")
   .replaceAll("[、，。！？]+"," ")
   .replaceAll("的"," ")
   .replaceAll("\\s+"," ").trim();
  if((q==null || q.isBlank()) && tag==null && aspect==null && download==null && commercial==null)
   return new Result("SEARCH",new Clarify("SEARCH","请补充关键词、标签或画幅/许可条件。"),null,null,null,List.of());
  return new Result("SEARCH",null,new SearchCommand(blankToNull(q),tag,aspect,download,commercial),null,null,List.of());
 }

 private static Long parseDeadlineMs(String text){
  // Demo/IT: 「N秒后」 or 「截止epoch:123」
  Matcher m=Pattern.compile("截止epoch:(\\d+)").matcher(text);
  if(m.find())return Long.parseLong(m.group(1));
  Matcher s=Pattern.compile("(\\d+)\\s*秒后").matcher(text);
  if(s.find())return System.currentTimeMillis()+Long.parseLong(s.group(1))*1000L;
  if(containsAny(text,"今晚8点","今晚20点","今晚 8 点")){
   // Fixed offset for stub: 2 minutes from now so demos can seal quickly when combined with epoch override.
   return System.currentTimeMillis()+120_000L;
  }
  return null;
 }
 private static boolean containsAny(String text,String... keys){for(String k:keys)if(text.contains(k))return true;return false;}
 private static String blankToNull(String s){return s==null||s.isBlank()?null:s;}
}
