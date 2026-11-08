package io.github.xiiiiimu.content.domain;

public interface PublishRepository {
    String gate();
    record AuthorMode(String mode, long epoch, long sequence) {}
    record Draft(long authorId, String status, long version) {}
    record Receipt(long postId, long expectedVersion, String snapshot) {}

    AuthorMode lockAuthor(long authorId);
    Draft lockPost(long postId);
    Receipt receipt(long authorId, String key);
    int advanceSequence(long authorId);
    long databaseTimeMs();
    int publish(long postId, long expectedVersion, String mode, long epoch, long sequence, long timeMs, String key);
    int insertOutbox(long postId);
    long insertedEventId();
    int completeOutbox(long eventId, String payload);
    int saveReceipt(long authorId, String key, long postId, long expectedVersion, String snapshot);
}
