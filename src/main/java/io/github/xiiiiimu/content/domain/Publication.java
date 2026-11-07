package io.github.xiiiiimu.content.domain;

/** Immutable publication response and PostPublished event snapshot. */
public record Publication(long eventId, long postId, long authorId, long publishedAtMs,
                          String deliveryMode, long deliveryEpoch, long postVersion, long publishSeq) {}
