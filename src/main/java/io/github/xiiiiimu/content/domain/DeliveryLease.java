package io.github.xiiiiimu.content.domain;
public record DeliveryLease(long taskId,long eventId,String kind,long authorId,Long postId,Long followerId,Long followEpoch,String payload,long token,int attempt,Long generation,Long upper,long lastFollower,long lastPost,long deliveryEpoch) {
 public DeliveryLease position(long follower,long post){return new DeliveryLease(taskId,eventId,kind,authorId,postId,followerId,followEpoch,payload,token,attempt,generation,upper,follower,post,deliveryEpoch);}
}
