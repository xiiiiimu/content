package io.github.xiiiiimu.content.infrastructure;

import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface RelayMapper {
    record Row(long id, String payload, String status, long token, int attempt) {}
    String COLUMNS="id,payload,status,lease_token AS token,attempt";
    String READY="event_type='PostPublished' AND ((status='NEW' AND next_retry_at<=CURRENT_TIMESTAMP(6)) OR (status='RUNNING' AND lease_until<=CURRENT_TIMESTAMP(6)))";
    @Select("SELECT "+COLUMNS+" FROM outbox_events WHERE "+READY+" AND attempt<#{maxAttempts} ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED")
    Row candidate(int maxAttempts);
    @Update("UPDATE outbox_events SET status='RUNNING',attempt=attempt+1,lease_token=lease_token+1,lease_until=TIMESTAMPADD(MICROSECOND,#{leaseMillis}*1000,CURRENT_TIMESTAMP(6)),last_attempt_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND lease_token=#{token} AND attempt<#{maxAttempts} AND "+READY)
    int claim(@Param("id") long id,@Param("token") long token,@Param("maxAttempts") int maxAttempts,@Param("leaseMillis") long leaseMillis);
    @Select("SELECT "+COLUMNS+" FROM outbox_events WHERE id=#{id} AND event_type='PostPublished' FOR UPDATE")
    Row lock(long id);
    @Update("UPDATE outbox_events SET status='SENT',sent_at=CURRENT_TIMESTAMP(6),lease_until=NULL,last_error=NULL WHERE id=#{id} AND status='RUNNING' AND lease_token=#{token} AND lease_until>CURRENT_TIMESTAMP(6)")
    int sent(@Param("id") long id,@Param("token") long token);
    @Update("UPDATE outbox_events SET status=#{status},lease_until=NULL,last_error=#{error},last_failure_at=CURRENT_TIMESTAMP(6),next_retry_at=TIMESTAMPADD(MICROSECOND,#{delay}*1000,CURRENT_TIMESTAMP(6)) WHERE id=#{id} AND status='RUNNING' AND lease_token=#{token} AND lease_until>CURRENT_TIMESTAMP(6)")
    int failed(@Param("id") long id,@Param("token") long token,@Param("status") String status,@Param("delay") long delay,@Param("error") String error);
    @Select("SELECT "+COLUMNS+" FROM outbox_events WHERE "+READY+" AND attempt>=#{maxAttempts} ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED")
    List<Row> exhausted(int maxAttempts);
    @Update("UPDATE outbox_events SET status='DEAD',lease_until=NULL,last_error='Attempt limit reached after lease expiry',last_failure_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND lease_token=#{token} AND attempt>=#{maxAttempts} AND "+READY)
    int dead(@Param("id") long id,@Param("token") long token,@Param("maxAttempts") int maxAttempts);
    @Update("UPDATE outbox_events SET status='NEW',attempt=0,lease_token=lease_token+1,lease_until=NULL,next_retry_at=CURRENT_TIMESTAMP(6),last_error=NULL WHERE id=#{id} AND status='DEAD' AND lease_token=#{token} AND event_type='PostPublished'")
    int replay(@Param("id") long id,@Param("token") long token);
    @Insert("INSERT INTO outbox_replay_audit(event_id,operator,reason,old_token,new_token,previous_attempt) VALUES (#{id},#{operator},#{reason},#{token},#{newToken},#{attempt})")
    int audit(@Param("id") long id,@Param("operator") String operator,@Param("reason") String reason,@Param("token") long token,@Param("newToken") long newToken,@Param("attempt") int attempt);
}
