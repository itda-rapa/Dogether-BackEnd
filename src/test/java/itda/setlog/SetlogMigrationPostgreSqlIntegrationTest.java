package itda.setlog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("postgres")
@Testcontainers
class SetlogMigrationPostgreSqlIntegrationTest {

    @Container
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void createsCurrentMediaSetlogReactionAndGreetingTables() {
        Flyway flyway = Flyway.configure()
                .dataSource(
                        postgres.getJdbcUrl(),
                        postgres.getUsername(),
                        postgres.getPassword()
                )
                .locations("classpath:db/migration")
                .load();

        flyway.migrate();

        JdbcTemplate jdbcTemplate = new JdbcTemplate(
                new DriverManagerDataSource(
                        postgres.getJdbcUrl(),
                        postgres.getUsername(),
                        postgres.getPassword()
                )
        );
        List<String> tables = jdbcTemplate.queryForList("""
                select table_name
                  from information_schema.tables
                 where table_schema = 'public'
                   and table_name in (
                       'media',
                       'setlogs',
                       'setlog_reactions',
                       'greetings',
                       'setlog_uploads'
                   )
                """, String.class);
        assertThat(tables).containsExactlyInAnyOrder(
                "media",
                "setlogs",
                "setlog_reactions",
                "greetings",
                "setlog_uploads"
        );

        Map<String, Object> uploadColumns = jdbcTemplate.queryForMap("""
                select count(*) filter (where data_type = 'uuid') as uuid_columns,
                       count(*) filter (where column_name = 'expires_at'
                           and data_type = 'timestamp with time zone') as expiry_columns
                  from information_schema.columns
                 where table_schema = 'public'
                   and table_name = 'setlog_uploads'
                   and column_name in ('id', 'expires_at')
                """);
        assertThat(((Number) uploadColumns.get("uuid_columns")).intValue()).isEqualTo(1);
        assertThat(((Number) uploadColumns.get("expiry_columns")).intValue()).isEqualTo(1);

        List<String> uploadConstraints = jdbcTemplate.queryForList("""
                select constraint_name
                  from information_schema.table_constraints
                 where table_schema = 'public'
                   and table_name = 'setlog_uploads'
                """, String.class);
        assertThat(uploadConstraints).contains(
                "fk_setlog_uploads_owner",
                "fk_setlog_uploads_pet",
                "uk_setlog_uploads_object_key",
                "ck_setlog_uploads_content_type",
                "ck_setlog_uploads_expected_size",
                "ck_setlog_uploads_status"
        );

        String partialIndex = jdbcTemplate.queryForObject("""
                select indexdef
                  from pg_indexes
                 where schemaname = 'public'
                   and tablename = 'setlog_uploads'
                   and indexname = 'ix_setlog_uploads_presigned_expires'
                """, String.class);
        assertThat(partialIndex)
                .contains("expires_at", "id")
                .containsIgnoringCase("WHERE")
                .contains("PRESIGNED");

        String referencedTable = jdbcTemplate.queryForObject("""
                select referenced_table.table_name
                  from information_schema.table_constraints constraint_info
                  join information_schema.referential_constraints reference_info
                    on reference_info.constraint_schema =
                       constraint_info.constraint_schema
                   and reference_info.constraint_name =
                       constraint_info.constraint_name
                  join information_schema.table_constraints referenced_table
                    on referenced_table.constraint_schema =
                       reference_info.unique_constraint_schema
                   and referenced_table.constraint_name =
                       reference_info.unique_constraint_name
                 where constraint_info.table_schema = 'public'
                   and constraint_info.table_name = 'setlogs'
                   and constraint_info.constraint_name = 'fk_setlogs_media'
                """, String.class);
        assertThat(referencedTable).isEqualTo("media");
    }
}
