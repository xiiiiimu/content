package io.github.xiiiiimu.content.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import static io.github.xiiiiimu.content.domain.PublishFailure.Reason.*;

@Service
public class PublishTransaction {
    private final PublishRepository repository;
    private final ObjectMapper json;

    public PublishTransaction(PublishRepository repository, ObjectMapper json) {
        this.repository = repository;
        this.json = json;
    }

    // Record locks provide serialization. READ_COMMITTED avoids missing-receipt gap
    // locks coupling independent authors, and reads the preceding committed receipt.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Publication publish(long authorId, long postId, String key, long expectedVersion) {
        if (authorId <= 0 || postId <= 0 || expectedVersion < 0 || key == null || key.isBlank() || key.length() > 128)
            throw new PublishFailure(INVALID_REQUEST);

        String gate = repository.gate();
        // Every publisher locks the same author row before post or receipt locks.
        // A competing request resumes only after the first transaction commits or rolls back.
        var mode = repository.lockAuthor(authorId);
        var post = repository.lockPost(postId);
        if (post == null) throw new PublishFailure(POST_NOT_FOUND);
        if (post.authorId() != authorId) throw new PublishFailure(POST_FORBIDDEN);
        if (mode == null) throw new IllegalStateException("Missing author mode");

        var receipt = repository.receipt(authorId, key);
        if (receipt != null) {
            if (receipt.postId() != postId || receipt.expectedVersion() != expectedVersion)
                throw new PublishFailure(IDEMPOTENCY_CONFLICT);
            return decode(receipt.snapshot());
        }
        if (!post.status().equals("DRAFT")) throw new PublishFailure(POST_STATE_CONFLICT);
        if (post.version() != expectedVersion) throw new PublishFailure(POST_VERSION_CONFLICT);
        if (!java.util.Set.of("PRIMARY","READY").contains(gate == null ? "BLOCKED" : gate))
            throw new FeedFailure(503,"MAINTENANCE");

        long sequence = Math.addExact(mode.sequence(), 1);
        long version = Math.addExact(post.version(), 1);
        long time = repository.databaseTimeMs();
        requireOne(repository.advanceSequence(authorId));
        requireOne(repository.publish(postId, expectedVersion, mode.mode(), mode.epoch(), sequence, time, key));
        requireOne(repository.insertOutbox(postId));
        long eventId = repository.insertedEventId();
        var publication = new Publication(eventId, postId, authorId, time, mode.mode(), mode.epoch(), version, sequence);
        String snapshot = encode(publication);
        // Event id is allocated by MySQL; the temporary payload is never visible outside this transaction.
        requireOne(repository.completeOutbox(eventId, snapshot));
        requireOne(repository.saveReceipt(authorId, key, postId, expectedVersion, snapshot));
        return publication;
    }

    /** Called through the Spring proxy only after the failed transaction rolled back. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Publication replayAfterUniqueConflict(long authorId, long postId, String key, long expectedVersion) {
        repository.lockAuthor(authorId);
        var post = repository.lockPost(postId);
        if (post == null) throw new PublishFailure(POST_NOT_FOUND);
        if (post.authorId() != authorId) throw new PublishFailure(POST_FORBIDDEN);
        var receipt = repository.receipt(authorId, key);
        if (receipt == null) throw new IllegalStateException("Unique conflict without a committed publication receipt");
        if (receipt.postId() != postId || receipt.expectedVersion() != expectedVersion)
            throw new PublishFailure(IDEMPOTENCY_CONFLICT);
        return decode(receipt.snapshot());
    }

    private void requireOne(int affected) {
        if (affected != 1) throw new IllegalStateException("Publication write did not affect exactly one row");
    }
    private String encode(Publication value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot encode publication", e); }
    }
    private Publication decode(String value) {
        try { return json.readValue(value, Publication.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Invalid stored publication", e); }
    }
}
