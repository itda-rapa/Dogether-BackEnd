package itda.setlog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
                       'greetings'
                   )
                """, String.class);
        assertThat(tables).containsExactlyInAnyOrder(
                "media",
                "setlogs",
                "setlog_reactions",
                "greetings"
        );

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
