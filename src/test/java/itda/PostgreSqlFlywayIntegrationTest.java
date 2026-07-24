package itda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.common.security.TokenHashing;
import itda.common.security.service.TokenProvider;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
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
    private TokenProvider tokenProvider;

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
                tokenProvider.rotateRefreshToken(rawRefreshToken)
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
}
