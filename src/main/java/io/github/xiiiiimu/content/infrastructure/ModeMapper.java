package io.github.xiiiiimu.content.infrastructure;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface ModeMapper {
 record Transition(long author,long epoch) {}
 @Update("UPDATE author_feed_mode SET mode=#{mode},mode_epoch=#{next} WHERE author_id=#{author} AND mode_epoch=#{old}") int change(@Param("author")long author,@Param("old")long old,@Param("next")long next,@Param("mode")String mode);
 @Insert("INSERT INTO feed_mode_transitions(author_id,from_epoch,to_epoch,old_mode,new_mode,state,source_window_since_ms) VALUES(#{author},#{old},#{next},#{oldMode},#{newMode},'VERIFYING',#{since})")
 void transition(@Param("author")long author,@Param("old")long old,@Param("next")long next,@Param("oldMode")String oldMode,@Param("newMode")String newMode,@Param("since")long since);
 @Select("SELECT author_id AS author,to_epoch AS epoch FROM feed_mode_transitions WHERE state='VERIFYING' ORDER BY author_id,to_epoch LIMIT 100") List<Transition> pending();
 @Select("SELECT source_window_since_ms FROM feed_mode_transitions WHERE author_id=#{author} AND to_epoch=#{epoch} AND state='VERIFYING' FOR UPDATE") Long lockTransition(@Param("author")long author,@Param("epoch")long epoch);
 @Select("SELECT COUNT(*) FROM posts WHERE author_id=#{author} AND published_at_ms>=#{since} AND (delivery_epoch IS NULL OR delivery_epoch<#{epoch}) AND (delivery_mode IS NULL OR delivery_mode NOT IN('PUSH','PULL') OR delivery_epoch IS NULL OR delivery_epoch<0 OR publish_seq IS NULL OR publish_seq<=0)")
 long invalid(@Param("author")long author,@Param("epoch")long epoch,@Param("since")long since);
 @Update("UPDATE feed_mode_transitions SET state='DONE',coverage_strategy='PRIMARY_ALL_MODES',coverage_until=CURRENT_TIMESTAMP(6),verified_at=CURRENT_TIMESTAMP(6) WHERE author_id=#{author} AND to_epoch=#{epoch} AND state='VERIFYING'")
 int verified(@Param("author")long author,@Param("epoch")long epoch);
}
