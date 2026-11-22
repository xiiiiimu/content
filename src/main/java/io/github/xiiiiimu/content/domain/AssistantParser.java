package io.github.xiiiiimu.content.domain;

import java.util.List;
import java.util.Map;

/** Parses @助手 natural language into structured commands. Python or deterministic stub. */
public interface AssistantParser {
 record Request(long taskId,long postId,long actorUserId,Long parentCommentId,String body,boolean actorIsAuthor,Map<String,Object> context) {}
 record Clarify(String intent,String question) {}
 record SearchCommand(String query,String tag,String aspectRatio,Boolean licenseDownload,Boolean licenseCommercial) {}
 /**
  * action: SUBSCRIBE (default) or CANCEL; target: SELF (default), OTHERS (on someone else's behalf) or ALL (bulk).
  * Only the parser can know these (negation, who the request is for); Java refuses anything but SUBSCRIBE/CANCEL for SELF.
  */
 record SubscribeCommand(long feedbackCommentId,String action,String target) {
  public SubscribeCommand(long feedbackCommentId){this(feedbackCommentId,"SUBSCRIBE","SELF");}
 }
 record GiveawayCommand(String ruleTreeJson,String timezone,Long windowStartMs,Long deadlineMs,int winnerCount,
                        String rewardVersionHint,String rewardLicense,String summary) {}
 record Result(String intent,Clarify clarify,SearchCommand search,SubscribeCommand subscribe,GiveawayCommand giveaway,List<String> unsupportedTools) {}

 Result parse(Request request);
}
