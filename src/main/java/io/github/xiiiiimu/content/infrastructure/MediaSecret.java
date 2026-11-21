package io.github.xiiiiimu.content.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Hardening A7: content.media.secret-key signs download links (HMAC) and S3 requests, so it must never fall back to a
 * value anyone can read in this repository.
 * <ul>
 * <li>Configured (and not the old public default) → used as is.</li>
 * <li>Missing, blank or the old public default, without the {@code dev} profile → startup fails.</li>
 * <li>Same with the {@code dev} profile → a random per-process key and a WARN. Links do not survive a restart, and an
 *     S3 store that checks credentials (MinIO) will refuse requests until the real key is configured.</li>
 * </ul>
 */
public final class MediaSecret {
 /** The value application.yml used to default to; it is public, so it is treated as unset. */
 public static final String OLD_PUBLIC_DEFAULT="content-secret-key-32-bytes-minimum";
 private static final Logger log=LoggerFactory.getLogger(MediaSecret.class);
 private MediaSecret(){}

 public static String resolve(String configured,boolean devProfile){
  boolean missing=configured==null || configured.isBlank() || OLD_PUBLIC_DEFAULT.equals(configured.strip());
  if(!missing)return configured;
  if(!devProfile)throw new IllegalStateException("content.media.secret-key (CONTENT_S3_SECRET_KEY) must be set to a private value "
   +"when media is enabled; it is missing or still the old public default. Only the 'dev' profile may run without it.");
  byte[] b=new byte[32];new SecureRandom().nextBytes(b);
  log.warn("content.media.secret-key is not set: dev profile uses a random per-process key. Download links will not survive "
   +"a restart, and a credential-checking S3 store will refuse requests until CONTENT_S3_SECRET_KEY is configured.");
  return HexFormat.of().formatHex(b);
 }
}
