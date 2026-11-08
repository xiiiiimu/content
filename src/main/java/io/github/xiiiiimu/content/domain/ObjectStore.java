package io.github.xiiiiimu.content.domain;
/** S3-compatible object API. Production target is MinIO; IT may use an available S3 mock while MinIO archives are blocked. */
public interface ObjectStore {
 void put(String bucket,String key,byte[] body,String contentType);
 byte[] get(String bucket,String key);
 boolean exists(String bucket,String key);
 String signGet(String bucket,String key,long ttlMillis);
}
