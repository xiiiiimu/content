package io.github.xiiiiimu.content.domain;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class PublishService {
    private final PublishTransaction transaction;

    public PublishService(PublishTransaction transaction) { this.transaction = transaction; }

    public Publication publish(long authorId, long postId, String key, long expectedVersion) {
        try {
            return transaction.publish(authorId, postId, key, expectedVersion);
        } catch (DuplicateKeyException conflict) {
            // The transaction proxy has rolled back every attempted write before this read.
            // Never inspect an idempotency winner from inside the failed transaction.
            return transaction.replayAfterUniqueConflict(authorId, postId, key, expectedVersion);
        }
    }
}
