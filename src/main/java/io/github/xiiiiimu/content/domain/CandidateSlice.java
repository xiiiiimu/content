package io.github.xiiiiimu.content.domain;

import java.util.List;

/** One bounded source read, not a public Feed page or signed cursor. */
public record CandidateSlice(List<FeedCandidate> items, String lastReadBoundary) {}
