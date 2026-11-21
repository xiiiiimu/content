package io.github.xiiiiimu.content.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hardening A4 follow-up: the default BCrypt pool is max(2, logical cores / 2). Half the cores stay free for the other
 * endpoints during a login burst; at least 2 so a small machine still serves two logins at once. More login capacity
 * comes from more instances, not from a bigger pool. content.auth.bcrypt-threads overrides it.
 */
class PasswordHasherDefaultsTest {
 @Test void defaultPoolIsHalfTheLogicalCoresButAtLeastTwo(){
  assertThat(PasswordHasher.defaultThreads(1)).isEqualTo(2);
  assertThat(PasswordHasher.defaultThreads(2)).isEqualTo(2);
  assertThat(PasswordHasher.defaultThreads(3)).isEqualTo(2);
  assertThat(PasswordHasher.defaultThreads(4)).isEqualTo(2);
  assertThat(PasswordHasher.defaultThreads(8)).isEqualTo(4);
  assertThat(PasswordHasher.defaultThreads(16)).isEqualTo(8);
 }
}
