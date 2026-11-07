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
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(4);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
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
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(3);
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
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(2);latest.validate();
        assertThat(latest.migrate().migrationsExecuted).isZero();
        try(var c=DriverManager.getConnection(url,"root",MYSQL.getPassword());var s=c.createStatement();var r=s.executeQuery("SELECT payload,status,attempt,lease_token FROM outbox_events WHERE id=42")) {
            assertThat(r.next()).isTrue();assertThat(r.getString("payload")).isEqualTo(payload);
            assertThat(r.getString("status")).isEqualTo("NEW");assertThat(r.getInt("attempt")).isZero();assertThat(r.getLong("lease_token")).isZero();
        }
    }
}
