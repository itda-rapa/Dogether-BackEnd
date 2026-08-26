package itda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.auth.dto.AuthTokensResponse;
import itda.auth.service.AuthService;
import itda.auth.service.UserRegistrationService;
import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.common.security.TokenHashing;
import itda.common.security.dto.IssuedTokens;
import itda.common.security.service.TokenProvider;
import itda.oauth.domain.OAuthProvider;
import itda.oauth.service.OAuthExchangeCommand;
import itda.oauth.service.OAuthExchangeResult;
import itda.oauth.service.OAuthExchangeService;
import itda.oauth.service.OAuthFlowException;
import itda.oauth.service.OAuthFlowFailure;
import itda.oauth.service.OAuthLoginCodeIssuer;
import itda.oauth.service.OAuthSignupCommand;
import itda.oauth.service.OAuthSignupService;
import itda.oauth.service.OAuthVerifiedIdentity;
import itda.user.domain.User;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import org.testcontainers.utility.DockerImageName;

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
            new PostgreSQLContainer(
                DockerImageName.parse("pgrouting/pgrouting:16-3.5-4.0")
                        .asCompatibleSubstituteFor("postgres")
        );

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRegistrationService userRegistrationService;

    @Autowired
    private TokenProvider tokenProvider;

    @Autowired
    private OAuthLoginCodeIssuer oauthLoginCodeIssuer;

    @Autowired
    private OAuthExchangeService oauthExchangeService;

    @Autowired
    private OAuthSignupService oauthSignupService;

    @Test
    void appliesAllMigrationsToPostgreSql() {
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().current()).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                """
                select count(*)
                  from neighborhoods
                 where code = '4113165000'
                   and active = true
                """,
                Integer.class
        )).isEqualTo(1);
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
                ) values (?, ?, ?, ?, 'USER', 'ACTIVE', '4113165000')
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
                    "4113165000"
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

    @Test
    void oauthOnlyUserCanPersistWithoutPasswordButEmailRemainsCaseInsensitiveUnique() {
        String unique = UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update("""
                insert into users (email, password_hash, nickname, public_tag, role, account_status, neighborhood_code)
                values (?, null, ?, ?, 'USER', 'ACTIVE', '4113165000')
                """, unique + "@example.com", "OAuth사용자", "OAuth#" + unique.substring(0, 8));

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into users (email, password_hash, nickname, public_tag, role, account_status, neighborhood_code)
                values (?, 'encoded', ?, ?, 'USER', 'ACTIVE', '4113165000')
                """, unique.toUpperCase() + "@EXAMPLE.COM", "중복사용자", "중복#" + unique.substring(0, 8)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void oauthIdentityAndArtifactConstraintsProtectIdentityBindingAndSensitiveEmailScrubbing() {
        String unique = UUID.randomUUID().toString().replace("-", "");
        Long firstUserId = insertOAuthUser(unique + "one@example.com", "OAuth#" + unique.substring(0, 8));
        Long secondUserId = insertOAuthUser(unique + "two@example.com", "Other#" + unique.substring(0, 8));
        jdbcTemplate.update("""
                insert into oauth_identities (user_id, provider, provider_subject, created_at, updated_at)
                values (?, 'GOOGLE', ?, current_timestamp, current_timestamp)
                """, firstUserId, "subject-" + unique);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into oauth_identities (user_id, provider, provider_subject, created_at, updated_at)
                values (?, 'GOOGLE', ?, current_timestamp, current_timestamp)
                """, secondUserId, "subject-" + unique)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into oauth_identities (user_id, provider, provider_subject, created_at, updated_at)
                values (?, 'GOOGLE', ?, current_timestamp, current_timestamp)
                """, firstUserId, "other-subject-" + unique)).isInstanceOf(RuntimeException.class);

        jdbcTemplate.update("""
                insert into oauth_login_codes
                    (token_hash, provider, provider_subject, verified_email, status, expires_at, created_at, consumed_at)
                values (?, 'GOOGLE', ?, null, 'CONSUMED', current_timestamp + interval '5 minutes',
                        current_timestamp, current_timestamp)
                """, TokenHashing.sha256("consumed-" + unique), "subject-" + unique);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into oauth_login_codes
                    (token_hash, provider, provider_subject, verified_email, status, expires_at, created_at)
                values (?, 'GOOGLE', ?, null, 'AVAILABLE', current_timestamp + interval '5 minutes', current_timestamp)
                """, TokenHashing.sha256("available-" + unique), "subject-" + unique))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void oauthArtifactStatusAndConsumedAtMustRemainConsistent() {
        for (String table : List.of("oauth_login_codes", "oauth_signup_tokens")) {
            String unique = UUID.randomUUID().toString().replace("-", "");

            insertOAuthArtifact(table, unique + "-available", "AVAILABLE", false);
            insertOAuthArtifact(table, unique + "-consumed", "CONSUMED", true);

            assertThatThrownBy(() ->
                    insertOAuthArtifact(table, unique + "-available-with-consumed-at", "AVAILABLE", true))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() ->
                    insertOAuthArtifact(table, unique + "-consumed-without-consumed-at", "CONSUMED", false))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void oauthNewUserLifecycleCreatesPasswordlessUserAndSingleGoogleIdentity() {
        String unique = UUID.randomUUID().toString().replace("-", "");
        String email = unique + "@example.com";
        String loginCode = oauthLoginCodeIssuer.issue(new OAuthVerifiedIdentity(
                OAuthProvider.GOOGLE, "subject-" + unique, email)).loginCode();

        OAuthExchangeResult<User> exchange = oauthExchangeService.exchange(
                new OAuthExchangeCommand(OAuthProvider.GOOGLE, loginCode), user -> user);
        String signupToken = ((OAuthExchangeResult.SignupRequired<User>) exchange).signupToken();
        User completed = oauthSignupService.complete(new OAuthSignupCommand(
                signupToken, "OAuth사용자", "4113165000"), user -> user);

        assertThat(completed.hasPasswordCredential()).isFalse();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from oauth_identities
                where user_id = ? and provider = 'GOOGLE' and provider_subject = ?
                """, Integer.class, completed.getId(), "subject-" + unique)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select verified_email is null and status = 'CONSUMED'
                from oauth_signup_tokens where provider_subject = ?
                """, Boolean.class, "subject-" + unique)).isTrue();
    }

    @Test
    void lateOAuthEmailRaceReturnsConflictAndRollsBackSignupArtifacts() {
        String unique = UUID.randomUUID().toString().replace("-", "");
        String email = unique + "@example.com";
        String subject = "late-email-subject-" + unique;
        String loginCode = oauthLoginCodeIssuer.issue(new OAuthVerifiedIdentity(
                OAuthProvider.GOOGLE, subject, email)).loginCode();
        String signupToken = ((OAuthExchangeResult.SignupRequired<User>) oauthExchangeService.exchange(
                new OAuthExchangeCommand(OAuthProvider.GOOGLE, loginCode), user -> user)).signupToken();

        // A Dogether account wins the email race after exchange has committed its signup token.
        insertOAuthUser(email, "Race#" + unique.substring(0, 8));

        assertThatThrownBy(() -> oauthSignupService.complete(
                new OAuthSignupCommand(signupToken, "OAuth사용자", "4113165000"),
                user -> tokenProvider.issueTokens(user)))
                .isInstanceOf(OAuthFlowException.class)
                .extracting(error -> ((OAuthFlowException) error).getFailure())
                .isEqualTo(OAuthFlowFailure.CONCURRENT_UPDATE_CONFLICT);

        assertThat(jdbcTemplate.queryForObject("""
                select status = 'AVAILABLE' and verified_email = ?
                  from oauth_signup_tokens
                 where provider_subject = ?
                """, Boolean.class, email, subject)).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from users where email = ?
                """, Integer.class, email)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from oauth_identities where provider_subject = ?
                """, Integer.class, subject)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*)
                  from refresh_tokens refresh_token
                  join users user_account on user_account.id = refresh_token.user_id
                 where user_account.email = ?
                """, Integer.class, email)).isZero();
    }

    @Test
    void concurrentOAuthSignupHasExactlyOneWinnerAndOnePersistedIdentity() throws Exception {
        String unique = UUID.randomUUID().toString().replace("-", "");
        String subject = "concurrent-subject-" + unique;
        String loginCode = oauthLoginCodeIssuer.issue(new OAuthVerifiedIdentity(
                OAuthProvider.GOOGLE, subject, unique + "@example.com")).loginCode();
        String signupToken = ((OAuthExchangeResult.SignupRequired<User>) oauthExchangeService.exchange(
                new OAuthExchangeCommand(OAuthProvider.GOOGLE, loginCode), user -> user)).signupToken();
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> complete = () -> {
                try {
                    oauthSignupService.complete(new OAuthSignupCommand(
                            signupToken, "OAuth사용자", "4113165000"), user -> user.getId());
                    return true;
                } catch (RuntimeException exception) {
                    return false;
                }
            };
            long winners = executor.invokeAll(List.of(complete, complete)).stream()
                    .filter(result -> {
                        try {
                            return result.get();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    }).count();
            assertThat(winners).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from oauth_identities where provider_subject = ?", Integer.class, subject))
                .isEqualTo(1);
    }

    @Nested
    @DisplayName("Describe: Refresh Token 생명주기")
    class DescribeRefreshTokenLifecycle {

        @Nested
        @DisplayName("Context: 유효한 Refresh Token을 회전하면")
        class WithValidRefreshToken {

            @Test
            @DisplayName("It: 새 Token을 발급하고 기존 Token 재사용을 거부한다")
            void itIssuesNewTokenAndRejectsReuse() {
                // given
                User user = newUser();
                AuthTokensResponse issued = userRegistrationService.registerAndIssue(user);

                // when
                AuthTokensResponse rotated = authService.refresh(issued.refreshToken());

                // then
                assertThat(rotated.refreshToken())
                        .isNotBlank()
                        .isNotEqualTo(issued.refreshToken());
                assertRefreshRejected(issued.refreshToken());
            }
        }

        @Nested
        @DisplayName("Context: 활성 Token이 두 개인 사용자가 Logout하면")
        class WithTwoActiveTokensOnLogout {

            @Test
            @DisplayName("It: 두 Token을 모두 폐기하고 재사용을 거부한다")
            void itRevokesAllTokensAndRejectsReuse() {
                // given
                User user = newUser();
                AuthTokensResponse first = userRegistrationService.registerAndIssue(user);
                IssuedTokens second = tokenProvider.issueTokens(user);

                // when
                authService.logout(user.getId());

                // then
                assertThat(jdbcTemplate.queryForObject("""
                        select count(*)
                          from refresh_tokens
                         where user_id = ?
                           and revoked_at is null
                        """,
                        Integer.class,
                        user.getId()
                )).isZero();
                assertRefreshRejected(first.refreshToken());
                assertRefreshRejected(second.refreshToken());
            }
        }
    }

    private User newUser() {
        String unique = UUID.randomUUID().toString().replace("-", "");
        return User.register(
                unique + "@example.com",
                "encoded",
                "사용자",
                "사용자#" + unique.substring(0, 8),
                "4113165000"
        );
    }

    private Long insertOAuthUser(String email, String publicTag) {
        return jdbcTemplate.queryForObject("""
                insert into users (email, password_hash, nickname, public_tag, role, account_status, neighborhood_code)
                values (?, null, 'OAuth사용자', ?, 'USER', 'ACTIVE', '4113165000')
                returning id
                """, Long.class, email, publicTag);
    }

    private void insertOAuthArtifact(String table, String suffix, String status, boolean withConsumedAt) {
        jdbcTemplate.update("""
                insert into %s
                    (token_hash, provider, provider_subject, verified_email, status, expires_at, created_at, consumed_at)
                values (?, 'GOOGLE', ?, ?, ?, current_timestamp + interval '5 minutes', current_timestamp,
                        case when ? then current_timestamp else null end)
                """.formatted(table),
                TokenHashing.sha256(table + "-" + suffix),
                "subject-" + suffix,
                suffix + "@example.com",
                status,
                withConsumedAt);
    }

    private void assertRefreshRejected(String refreshToken) {
        assertThatThrownBy(() -> authService.refresh(refreshToken))
                .isInstanceOf(BusinessException.class)
                .extracting(error ->
                        ((BusinessException) error).getErrorCode()
                )
                .isEqualTo(ErrorCode.REFRESH_TOKEN_EXPIRED);
    }
}
