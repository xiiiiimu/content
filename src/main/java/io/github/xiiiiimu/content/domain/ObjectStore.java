package io.github.xiiiiimu.content.domain;
/** S3-compatible object API. Production target is MinIO; IT may use an available S3 mock while MinIO archives are blocked. */
public interface ObjectStore {
 void put(String bucket,String key,byte[] body,String contentType);
 byte[] get(String bucket,String key);
 boolean exists(String bucket,String key);
 /** Returns an application gateway URL; raw object-store URLs are never client-facing. */
 String signGet(String bucket,String key,long ttlMillis);
 /** Validates gateway query parameters before any object fetch. */
 boolean verifyGet(String bucket,String key,long exp,String sig);
}
