package itda.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
class V20MediaLinkMigrationPostgreSqlIntegrationTest {

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
    private PlatformTransactionManager transactionManager;

    @Test
    void appliesV20AndCreatesRequiredBoardPostMediaColumnsAndConstraints() {
        assertThat(Arrays.stream(flyway.info().all()))
                .anyMatch(migration ->
                        migration.getVersion() != null
                                && "20".equals(migration.getVersion().getVersion())
                                && migration.getState() == MigrationState.SUCCESS
                );

        List<String> notNullColumns = jdbcTemplate.queryForList("""
                select column_name
                  from information_schema.columns
                 where table_schema = 'public'
                   and table_name = 'board_post_media'
                   and is_nullable = 'NO'
                """, String.class);
        assertThat(notNullColumns).contains(
                "id",
                "post_id",
                "media_id",
                "display_order",
                "created_at"
        );

        List<String> constraints = jdbcTemplate.queryForList("""
                select constraint_name
                  from information_schema.table_constraints
                 where table_schema = 'public'
                   and table_name = 'board_post_media'
                """, String.class);
        assertThat(constraints).contains(
                "fk_board_post_media_post",
                "fk_board_post_media_media",
                "uk_board_post_media_post_media",
                "uk_board_post_media_post_display_order",
                "ck_board_post_media_display_order"
        );
    }

    @Test
    void enforcesForeignKeysUniquePairsUniqueOrdersAndDisplayOrderRange() {
        Author author = createAuthor();
        long postId = createPost(author, unique("constraints"));
        long firstMediaId = createMedia(author.userId());
        long secondMediaId = createMedia(author.userId());

        insertLink(postId, firstMediaId, 0);

        assertIntegrityViolation(() -> insertLink(Long.MAX_VALUE, secondMediaId, 1));
        assertIntegrityViolation(() -> insertLink(postId, Long.MAX_VALUE, 1));
        assertIntegrityViolation(() -> insertLink(postId, firstMediaId, 1));
        assertIntegrityViolation(() -> insertLink(postId, secondMediaId, 0));
        assertIntegrityViolation(() -> insertLink(postId, secondMediaId, -1));
        assertIntegrityViolation(() -> insertLink(postId, secondMediaId, 5));
    }

    @Test
    void databaseAllowsAtMostFiveOrderedLinksAndAllowsMediaReuseAcrossPosts() {
        Author author = createAuthor();
        long firstPostId = createPost(author, unique("first"));
        long secondPostId = createPost(author, unique("second"));

        long reusableMediaId = createMedia(author.userId());
        insertLink(firstPostId, reusableMediaId, 0);
        insertLink(secondPostId, reusableMediaId, 0);

        for (int order = 1; order < 5; order++) {
            insertLink(firstPostId, createMedia(author.userId()), order);
        }

        assertIntegrityViolation(() ->
                insertLink(firstPostId, createMedia(author.userId()), 5)
        );
        assertThat(jdbcTemplate.queryForObject("""
                select count(*)
                  from board_post_media
                 where post_id = ?
                """, Integer.class, firstPostId)).isEqualTo(5);
    }

    @Test
    void postAndLinksRollBackTogetherWhenAnyLinkViolatesConstraint() {
        Author author = createAuthor();
        long firstMediaId = createMedia(author.userId());
        long secondMediaId = createMedia(author.userId());
        String title = unique("rollback");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            long postId = createPost(author, title);
            insertLink(postId, firstMediaId, 0);
            insertLink(postId, secondMediaId, 5);
        })).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from board_posts where title = ?",
                Integer.class,
                title
        )).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*)
                  from board_post_media bpm
                  join board_posts bp on bp.id = bpm.post_id
                 where bp.title = ?
                """, Integer.class, title)).isZero();
    }

    private Author createAuthor() {
        String suffix = UUID.randomUUID().toString()
                .replace("-", "")
                .toUpperCase();
        long userId = jdbcTemplate.queryForObject("""
                insert into users (
                    email,
                    password_hash,
                    nickname,
                    public_tag,
                    role,
                    account_status,
                    neighborhood_code
                ) values (?, 'encoded', '작성자', ?, 'USER', 'ACTIVE', '4113165000')
                returning id
                """,
                Long.class,
                suffix + "@example.com",
                "작성자#" + suffix.substring(0, 8)
        );
        long petId = jdbcTemplate.queryForObject("""
                insert into pets (
                    owner_user_id,
                    public_tag,
                    nickname,
                    status
                ) values (?, ?, '반려견', 'ACTIVE')
                returning id
                """,
                Long.class,
                userId,
                "반려견#" + suffix.substring(8, 12)
        );
        return new Author(userId, petId);
    }

    private long createPost(Author author, String title) {
        long boardId = jdbcTemplate.queryForObject(
                "insert into boards (name) values (?) returning id",
                Long.class,
                "board-" + UUID.randomUUID().toString().substring(0, 12)
        );
        return jdbcTemplate.queryForObject("""
                insert into board_posts (
                    board_id,
                    author_user_id,
                    author_pet_id,
                    neighborhood_code,
                    title,
                    content,
                    status
                ) values (?, ?, ?, '4113165000', ?, 'content', 'PUBLISHED')
                returning id
                """,
                Long.class,
                boardId,
                author.userId(),
                author.petId(),
                title
        );
    }

    private long createMedia(long userId) {
        return jdbcTemplate.queryForObject("""
                insert into media (
                    media_type,
                    path,
                    status,
                    user_id,
                    file_size
                ) values ('IMAGE', ?, 'UPLOADED', ?, 1024)
                returning id
                """,
                Long.class,
                "post/" + UUID.randomUUID(),
                userId
        );
    }

    private void insertLink(long postId, long mediaId, int displayOrder) {
        jdbcTemplate.update("""
                insert into board_post_media (
                    post_id,
                    media_id,
                    display_order
                ) values (?, ?, ?)
                """,
                postId,
                mediaId,
                displayOrder
        );
    }

    private void assertIntegrityViolation(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private record Author(long userId, long petId) {
    }
}
