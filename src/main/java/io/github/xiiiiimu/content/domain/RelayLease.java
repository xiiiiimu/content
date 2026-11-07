package io.github.xiiiiimu.content.domain;

public record RelayLease(long eventId, String payload, long token, int attempt) {}
