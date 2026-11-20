package io.github.xiiiiimu.content;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class MySqlMigrationIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Test
    void migratesEmptySchemaAndCanRunAgain() {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(27);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
            var s=c.createStatement();
            var r=s.executeQuery("SHOW INDEX FROM feed_delivery_tasks WHERE Key_name IN ('ix_delivery_retry_attempt','ix_delivery_lease_attempt')")){
            int n=0;while(r.next())n++;
            assertThat(n).as("V9 attempt-aware claim indexes").isGreaterThanOrEqualTo(2);
        }catch(Exception e){throw new AssertionError(e);}
        try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
            var s=c.createStatement();
            var r=s.executeQuery("SELECT index_name,GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='material_sale_dispatch' AND index_name IN ('ix_sale_dispatch_pending_due','ix_sale_dispatch_sending_expired','ix_sale_dispatch_due') GROUP BY index_name ORDER BY index_name")){
            var found=new java.util.ArrayList<String>();while(r.next())found.add(r.getString(1)+"="+r.getString(2));
            assertThat(found).as("V25 split dispatch claim indexes keep V20's serial index").containsExactly(
             "ix_sale_dispatch_due=state,available_at_ms,lease_until_ms",
             "ix_sale_dispatch_pending_due=state,available_at_ms,reservation_id",
             "ix_sale_dispatch_sending_expired=state,lease_until_ms,reservation_id");
        }catch(Exception e){throw new AssertionError(e);}
        try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());var s=c.createStatement()){
            var fks=new java.util.ArrayList<String>();
            try(var r=s.executeQuery("SELECT constraint_name FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE() AND referenced_table_name='material_sale_campaigns' ORDER BY constraint_name")){while(r.next())fks.add(r.getString(1));}
            assertThat(fks).as("V26: purchase requests and orders no longer take an FK share lock on the hot campaign row")
             .doesNotContain("fk_purchase_campaign","fk_order_campaign").contains("fk_sale_elig_campaign");
            var idx=new java.util.ArrayList<String>();
            try(var r=s.executeQuery("SELECT CONCAT(table_name,'.',index_name,'=',GROUP_CONCAT(column_name ORDER BY seq_in_index)) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name IN ('material_purchase_requests','material_orders') AND index_name IN ('ix_purchase_campaign_state','fk_order_campaign') GROUP BY table_name,index_name ORDER BY 1")){while(r.next())idx.add(r.getString(1));}
            assertThat(idx).as("V26 keeps the campaign_id indexes").containsExactly("material_orders.fk_order_campaign=campaign_id","material_purchase_requests.ix_purchase_campaign_state=campaign_id,state");
        }catch(Exception e){throw new AssertionError(e);}
        try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());var s=c.createStatement();
            var r=s.executeQuery("SELECT CONCAT(table_name,'.',index_name,'=',GROUP_CONCAT(column_name ORDER BY seq_in_index)) FROM information_schema.statistics WHERE table_schema=DATABASE() AND index_name IN ('ix_bucket_start','ix_ledger_time') GROUP BY table_name,index_name ORDER BY 1")){
            var idx=new java.util.ArrayList<String>();while(r.next())idx.add(r.getString(1));
            assertThat(idx).as("V27: time-leading indexes for the trending window read and the retention purge")
             .containsExactly("topic_event_ledger.ix_ledger_time=occurred_at_ms","topic_minute_buckets.ix_bucket_start=bucket_start_ms");
        }catch(Exception e){throw new AssertionError(e);}
    }

    @Test
    void upgradesExistingV1WithoutLosingDrafts() throws Exception {
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE content_upgrade");
        }
        String url = MYSQL.getJdbcUrl().replace("/test", "/content_upgrade");
        Flyway v1 = Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).target("1").load();
        assertThat(v1.migrate().migrationsExecuted).isEqualTo(1);
        try (var connection = DriverManager.getConnection(url, "root", MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO author_feed_mode(author_id,mode) VALUES (1,'PUSH')");
            statement.executeUpdate("INSERT INTO posts(id,author_id,status,body,version) VALUES (1,1,'DRAFT','preserved',4)");
        }
        Flyway latest = Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(26);
        assertThat(latest.migrate().migrationsExecuted).isZero();
        latest.validate();
        try (var connection = DriverManager.getConnection(url, "root", MYSQL.getPassword());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT body,version,status FROM posts WHERE id=1")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("body")).isEqualTo("preserved");
            assertThat(rows.getLong("version")).isEqualTo(4);
            assertThat(rows.getString("status")).isEqualTo("DRAFT");
        }
    }

    @Test
    void upgradesV2WithAnUnsentPublicationWithoutChangingItsIdentity() throws Exception {
        try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword());var s=c.createStatement()) {
            s.execute("CREATE DATABASE relay_upgrade");
        }
        String url=MYSQL.getJdbcUrl().replace("/test","/relay_upgrade");
        var old=Flyway.configure().dataSource(url,"root",MYSQL.getPassword()).target("2").load();
        assertThat(old.migrate().migrationsExecuted).isEqualTo(2);
        String payload;
        try(var c=DriverManager.getConnection(url,"root",MYSQL.getPassword());var s=c.createStatement()) {
            s.executeUpdate("INSERT INTO outbox_events(id,event_type,aggregate_id,payload) VALUES (42,'PostPublished',7,'{\"eventId\":42}')");
            try(var r=s.executeQuery("SELECT payload FROM outbox_events WHERE id=42")){r.next();payload=r.getString(1);}
        }
        var latest=Flyway.configure().dataSource(url,"root",MYSQL.getPassword()).load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(25);latest.validate();
        assertThat(latest.migrate().migrationsExecuted).isZero();
        try(var c=DriverManager.getConnection(url,"root",MYSQL.getPassword());var s=c.createStatement();var r=s.executeQuery("SELECT payload,status,attempt,lease_token FROM outbox_events WHERE id=42")) {
            assertThat(r.next()).isTrue();assertThat(r.getString("payload")).isEqualTo(payload);
            assertThat(r.getString("status")).isEqualTo("NEW");assertThat(r.getInt("attempt")).isZero();assertThat(r.getLong("lease_token")).isZero();
        }
    }
}
