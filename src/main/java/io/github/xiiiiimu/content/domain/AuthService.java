package io.github.xiiiiimu.content.domain;

import io.github.xiiiiimu.content.infrastructure.UserMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

/** V1.1 register/login; issues FeedTokens.Identity compatible Bearer (role always READER). */
public final class AuthService {
 public record Session(long userId,String username,String token,long expiresAtMs) {}
 public record PublicUser(long userId,String username,String status) {}
 private final UserMapper users;private final TransactionTemplate tx;private final FeedTokens identity;
 private final PasswordHasher passwords;
 /** BCrypt cost 10 (same as real hashes) of a random value that was discarded; never matches a real password. */
 static final String DUMMY_HASH="$2a$10$MrJ2M4dkc4DKr1ZbR9dmyepYW2AO2jR6Jzh.F2Tw72Pzq9GA3k3lm";
 private final long ttlMs;
 public AuthService(UserMapper users,TransactionTemplate tx,FeedTokens identity,long ttlMs,PasswordHasher passwords){
  this.users=users;this.tx=tx;this.identity=identity;this.ttlMs=Math.min(Math.max(ttlMs,60_000),3_600_000);this.passwords=passwords;
 }
 /**
  * A taken name is refused before hashing. The hash is computed outside the transaction (on the bounded BCrypt pool, A4),
  * so no connection is held while it runs. A concurrent registration of the same name (the key is case-insensitive) hits
  * uq_users_username and is answered like any taken name: 409 USERNAME_TAKEN (A6), never a server error.
  */
 public Session register(String username,String password){
  validateCreds(username,password);
  if(users.byUsername(username)!=null)throw new FeedFailure(409,"USERNAME_TAKEN");
  String hash=passwords.encode(password);
  try{
   return tx.execute(status->{
    if(users.byUsername(username)!=null)throw new FeedFailure(409,"USERNAME_TAKEN");
    long now=System.currentTimeMillis();
    if(users.insert(username,hash,now)!=1)throw new IllegalStateException("user insert");
    long id=users.lastId();
    users.ensureAuthor(id);
    return session(id,username);
   });
  }catch(DuplicateKeyException e){throw new FeedFailure(409,"USERNAME_TAKEN");}
 }
 public Session login(String username,String password){
  validateCreds(username,password);
  var u=users.byUsername(username);
  // Hardening A13: an unknown username still pays one BCrypt check (fixed dummy hash, same cost, same pool), so the
  // response time does not tell whether the account exists.
  boolean ok=passwords.matches(password,u==null?DUMMY_HASH:u.passwordHash());
  if(u==null || !ok)throw new FeedFailure(401,"INVALID_CREDENTIALS");
  if(!"ACTIVE".equals(u.status()))throw new FeedFailure(403,"USER_DISABLED");
  users.ensureAuthor(u.id());
  return session(u.id(),u.username());
 }
 public PublicUser me(long userId){
  var u=users.byId(userId);if(u==null)throw new FeedFailure(404,"USER_NOT_FOUND");
  return new PublicUser(u.id(),u.username(),u.status());
 }
 private Session session(long id,String username){
  long now=System.currentTimeMillis();long exp=now+ttlMs;
  // Ordinary users cannot self-elevate to TRENDGUARD_OPS.
  String token=identity.sign(new FeedTokens.Identity(1,id,now,exp,"READER"));
  return new Session(id,username,token,exp);
 }
 private static void validateCreds(String username,String password){
  if(username==null || !username.matches("[a-zA-Z0-9_]{3,32}"))throw new FeedFailure(400,"INVALID_REQUEST");
  if(password==null || password.length()<8 || password.length()>72)throw new FeedFailure(400,"INVALID_REQUEST");
 }
}
