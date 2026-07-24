package itda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.auth.service.AuthService;
import itda.auth.service.UserRegistrationService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.common.security.TokenHashing;
import itda.user.domain.User;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("postgres")
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.locations=classpath:db/migration,classpath:db/seed"
})
class PostgreSqlFlywayIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRegistrationService userRegistrationService;

    @Test
    void appliesAllMigrationsToPostgreSql() {
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().current()).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from neighborhoods",
                Integer.class
        )).isEqualTo(3);
    }

    @Test
    void expiredRefreshTokenIsPersistentlyRevoked() {
        String unique = UUID.randomUUID().toString().replace("-", "");
        Long userId = jdbcTemplate.queryForObject("""
                insert into users (
                    email,
                    password_hash,
                    nickname,
                    public_tag,
                    role,
                    account_status,
                    neighborhood_code
                ) values (?, ?, ?, ?, 'USER', 'ACTIVE', '4113111500')
                returning id
                """,
                Long.class,
                unique + "@example.com",
                "encoded",
                "사용자",
                "사용자#" + unique.substring(0, 8)
        );
        String rawRefreshToken = "expired-" + unique;
        jdbcTemplate.update("""
                insert into refresh_tokens (
                    user_id,
                    token_hash,
                    expires_at,
                    created_at
                ) values (?, ?, CURRENT_TIMESTAMP - INTERVAL '1 day',
                          CURRENT_TIMESTAMP - INTERVAL '2 days')
                """,
                userId,
                TokenHashing.sha256(rawRefreshToken)
        );

        assertThatThrownBy(() ->
                authService.refresh(rawRefreshToken)
        )
                .isInstanceOf(BusinessException.class)
                .extracting(error ->
                        ((BusinessException) error).getErrorCode()
                )
                .isEqualTo(ErrorCode.REFRESH_TOKEN_EXPIRED);

        assertThat(jdbcTemplate.queryForObject("""
                select revoked_at is not null
                  from refresh_tokens
                 where token_hash = ?
                """,
                Boolean.class,
                TokenHashing.sha256(rawRefreshToken)
        )).isTrue();
    }

    @Test
    void signupRollsBackUserWhenRefreshTokenCannotBeStored() {
        String unique = UUID.randomUUID().toString().replace("-", "");
        String email = unique + "@example.com";
        jdbcTemplate.execute("""
                create or replace function reject_refresh_token_insert()
                returns trigger
                language plpgsql
                as $$
                begin
                    raise exception 'forced refresh token insert failure';
                end;
                $$
                """);
        jdbcTemplate.execute("""
                create trigger reject_refresh_token_insert_trigger
                before insert on refresh_tokens
                for each row execute function reject_refresh_token_insert()
                """);

        try {
            User user = User.register(
                    email,
                    "encoded",
                    "사용자",
                    "사용자#" + unique.substring(0, 8),
                    "4113111500"
            );

            assertThatThrownBy(() ->
                    userRegistrationService.registerAndIssue(user)
            ).isInstanceOf(RuntimeException.class);

            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from users where email = ?",
                    Integer.class,
                    email
            )).isZero();
        } finally {
            jdbcTemplate.execute("""
                    drop trigger if exists reject_refresh_token_insert_trigger
                    on refresh_tokens
                    """);
            jdbcTemplate.execute(
                    "drop function if exists reject_refresh_token_insert()"
            );
        }
    }

}
