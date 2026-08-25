package itda.pet.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;

import itda.common.constants.ErrorCode;
import itda.common.exception.BusinessException;
import itda.media.domain.Media;
import itda.media.repository.MediaRepository;
import itda.media.service.MediaService;
import itda.pet.domain.Pet;
import itda.pet.dto.PetResponse;
import itda.pet.repository.PetRepository;
import itda.pet.service.PetProfileImageService;
import itda.pet.service.query.PetHelpfulReceivedCountQueryService;
import itda.petverification.PetVerificationBadgeService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
class PetProfileImageConcurrencyPostgreSqlIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PetProfileImageService profileImageService;

    @Autowired
    private PetRepository petRepository;

    @Autowired
    private MediaRepository mediaRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private MediaService mediaService;

    @MockitoBean
    private PetVerificationBadgeService badgeService;

    @MockitoBean
    private PetHelpfulReceivedCountQueryService helpfulReceivedCounts;

    @BeforeEach
    void setUp() {
        reset(mediaService, badgeService, helpfulReceivedCounts);
        given(mediaService.getPresignedDownloadUrls(any()))
                .willAnswer(invocation -> downloadUrls(invocation.getArgument(0)));
    }

    @Test
    void replaceActualMutationFlushesAndResponseVersionMatchesPostgreSql()
            throws Exception {
        long ownerId = createUser();
        long originalMediaId = createMedia(ownerId, "UPLOADED");
        long replacementMediaId = createMedia(ownerId, "COMPLETED");
        long petId = createPet(ownerId, originalMediaId);
        Map<Long, Map<String, Object>> mediaBefore = mediaStates(
                List.of(originalMediaId, replacementMediaId)
        );
        long jobsBefore = storageDeleteJobCount();

        PetResponse response = profileImageService.replaceProfileImage(
                ownerId, petId, replacementMediaId, 0
        );

        assertThat(response.version()).isEqualTo(1L);
        assertThat(response.profileUrl())
                .isEqualTo("https://presigned.example/media/" + replacementMediaId);
        assertThat(petState(petId)).isEqualTo(new PetState(replacementMediaId, 1L));
        assertThat(mediaStates(List.of(originalMediaId, replacementMediaId)))
                .isEqualTo(mediaBefore);
        assertThat(storageDeleteJobCount()).isEqualTo(jobsBefore);
    }

    @Test
    void putFirstSetFlushesAndReturnsThePersistedVersion() {
        long ownerId = createUser();
        long mediaId = createMedia(ownerId, "UPLOADED");
        long petId = createPet(ownerId, null);
        Map<Long, Map<String, Object>> mediaBefore = mediaStates(List.of(mediaId));

        PetResponse response = profileImageService.replaceProfileImage(
                ownerId, petId, mediaId, 0
        );

        assertThat(response.version()).isEqualTo(1L);
        assertThat(petState(petId)).isEqualTo(new PetState(mediaId, 1L));
        assertThat(mediaStates(List.of(mediaId))).isEqualTo(mediaBefore);
    }

    @Test
    void postFirstSetFlushesAndReturnsThePersistedVersion() {
        long ownerId = createUser();
        long mediaId = createMedia(ownerId, "COMPLETED");
        long petId = createPet(ownerId, null);
        Map<Long, Map<String, Object>> mediaBefore = mediaStates(List.of(mediaId));

        PetResponse response = profileImageService.setInitialProfileImage(
                ownerId, petId, mediaId
        );

        assertThat(response.version()).isEqualTo(1L);
        assertThat(petState(petId)).isEqualTo(new PetState(mediaId, 1L));
        assertThat(mediaStates(List.of(mediaId))).isEqualTo(mediaBefore);
    }

    @Test
    void sameMediaPutAndAlreadyNullDeleteAreNoOpsWithoutVersionIncrement() {
        long ownerId = createUser();
        long mediaId = createMedia(ownerId, "UPLOADED");
        long profiledPetId = createPet(ownerId, mediaId);
        long emptyPetId = createPet(ownerId, null);

        PetResponse response = profileImageService.replaceProfileImage(
                ownerId, profiledPetId, mediaId, 0
        );
        profileImageService.deleteProfileImage(ownerId, emptyPetId, 0);

        assertThat(response.version()).isZero();
        assertThat(petState(profiledPetId)).isEqualTo(new PetState(mediaId, 0L));
        assertThat(petState(emptyPetId)).isEqualTo(new PetState(null, 0L));
    }

    @Test
    void deleteActualMutationIncrementsVersionAndLeavesMediaLifecycleUntouched() {
        long ownerId = createUser();
        long mediaId = createMedia(ownerId, "UPLOADED");
        long petId = createPet(ownerId, mediaId);
        Map<Long, Map<String, Object>> mediaBefore = mediaStates(List.of(mediaId));
        long jobsBefore = storageDeleteJobCount();

        profileImageService.deleteProfileImage(ownerId, petId, 0);

        assertThat(petState(petId)).isEqualTo(new PetState(null, 1L));
        assertThat(mediaStates(List.of(mediaId))).isEqualTo(mediaBefore);
        assertThat(storageDeleteJobCount()).isEqualTo(jobsBefore);
    }

    @Test
    void staleSameMediaPutAndStaleAlreadyNullDeleteConflictBeforeNoOp() {
        long ownerId = createUser();
        long mediaId = createMedia(ownerId, "UPLOADED");
        long profiledPetId = createPet(ownerId, mediaId);
        long emptyPetId = createPet(ownerId, null);
        jdbcTemplate.update("update pets set version = 1 where id = ?", profiledPetId);
        jdbcTemplate.update("update pets set version = 1 where id = ?", emptyPetId);

        assertConcurrentConflict(() -> profileImageService.replaceProfileImage(
                ownerId, profiledPetId, mediaId, 0
        ));
        assertConcurrentConflict(() -> profileImageService.deleteProfileImage(
                ownerId, emptyPetId, 0
        ));

        assertThat(petState(profiledPetId)).isEqualTo(new PetState(mediaId, 1L));
        assertThat(petState(emptyPetId)).isEqualTo(new PetState(null, 1L));
    }

    @Test
    void concurrentPutPutHasOneActualWinnerAndOneOptimisticConflict()
            throws Exception {
        long ownerId = createUser();
        long originalMediaId = createMedia(ownerId, "UPLOADED");
        long firstReplacementId = createMedia(ownerId, "UPLOADED");
        long secondReplacementId = createMedia(ownerId, "COMPLETED");
        long petId = createPet(ownerId, originalMediaId);
        Map<Long, Map<String, Object>> mediaBefore = mediaStates(List.of(
                originalMediaId, firstReplacementId, secondReplacementId
        ));

        List<MutationOutcome> outcomes = runConcurrentRace((index, bothRead, allowFlush) ->
                replaceRaceOutcome(
                        ownerId,
                        petId,
                        index == 0 ? firstReplacementId : secondReplacementId,
                        bothRead,
                        allowFlush
                )
        );

        assertOneSuccessAndOneOptimisticConflict(outcomes);
        assertThat(petState(petId).version()).isEqualTo(1L);
        assertThat(petState(petId).profileAssetId())
                .isIn(firstReplacementId, secondReplacementId);
        assertThat(mediaStates(List.of(
                originalMediaId, firstReplacementId, secondReplacementId
        ))).isEqualTo(mediaBefore);
    }

    @Test
    void concurrentPutDeleteHasOneActualWinnerAndOneOptimisticConflict()
            throws Exception {
        long ownerId = createUser();
        long originalMediaId = createMedia(ownerId, "UPLOADED");
        long replacementMediaId = createMedia(ownerId, "COMPLETED");
        long petId = createPet(ownerId, originalMediaId);
        Map<Long, Map<String, Object>> mediaBefore = mediaStates(
                List.of(originalMediaId, replacementMediaId)
        );

        List<MutationOutcome> outcomes = runConcurrentRace((index, bothRead, allowFlush) ->
                index == 0
                        ? replaceRaceOutcome(
                        ownerId, petId, replacementMediaId, bothRead, allowFlush
                )
                        : deleteRaceOutcome(ownerId, petId, bothRead, allowFlush)
        );

        assertOneSuccessAndOneOptimisticConflict(outcomes);
        PetState finalState = petState(petId);
        assertThat(finalState.version()).isEqualTo(1L);
        assertThat(finalState.profileAssetId()).isIn(null, replacementMediaId);
        assertThat(mediaStates(List.of(originalMediaId, replacementMediaId)))
                .isEqualTo(mediaBefore);
    }

    @Test
    void concurrentPostPutHasOneActualWinnerAndOneOptimisticConflict()
            throws Exception {
        long ownerId = createUser();
        long postMediaId = createMedia(ownerId, "UPLOADED");
        long putMediaId = createMedia(ownerId, "COMPLETED");
        long petId = createPet(ownerId, null);
        Map<Long, Map<String, Object>> mediaBefore = mediaStates(
                List.of(postMediaId, putMediaId)
        );

        List<MutationOutcome> outcomes = runConcurrentRace((index, bothRead, allowFlush) ->
                index == 0
                        ? initialPostRaceOutcome(
                        ownerId, petId, postMediaId, bothRead, allowFlush
                )
                        : replaceRaceOutcome(
                        ownerId, petId, putMediaId, bothRead, allowFlush
                )
        );

        assertOneSuccessAndOneOptimisticConflict(outcomes);
        PetState finalState = petState(petId);
        assertThat(finalState.version()).isEqualTo(1L);
        assertThat(finalState.profileAssetId()).isIn(postMediaId, putMediaId);
        assertThat(mediaStates(List.of(postMediaId, putMediaId)))
                .isEqualTo(mediaBefore);
    }

    @Test
    void concurrentPostDeleteAllowsNoOpThenActualMutationLinearization()
            throws Exception {
        long ownerId = createUser();
        long postMediaId = createMedia(ownerId, "UPLOADED");
        long petId = createPet(ownerId, null);
        Map<Long, Map<String, Object>> mediaBefore = mediaStates(List.of(postMediaId));

        List<MutationOutcome> outcomes = runConcurrentRace((index, bothRead, allowFlush) ->
                index == 0
                        ? initialPostRaceOutcome(
                        ownerId, petId, postMediaId, bothRead, allowFlush
                )
                        : deleteRaceOutcome(ownerId, petId, bothRead, allowFlush)
        );

        assertThat(outcomes).allSatisfy(outcome -> {
            assertThat(outcome.failure()).isNull();
        });
        assertThat(petState(petId)).isEqualTo(new PetState(postMediaId, 1L));
        assertThat(mediaStates(List.of(postMediaId))).isEqualTo(mediaBefore);
    }

    private List<MutationOutcome> runConcurrentRace(IndexedRace race)
            throws Exception {
        CountDownLatch bothReadsCompleted = new CountDownLatch(2);
        CountDownLatch allowMutation = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MutationOutcome>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 2; index++) {
                int worker = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    return race.apply(worker, bothReadsCompleted, allowMutation);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(bothReadsCompleted.await(10, TimeUnit.SECONDS)).isTrue();
            allowMutation.countDown();

            List<MutationOutcome> outcomes = new ArrayList<>();
            for (Future<MutationOutcome> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            allowMutation.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private MutationOutcome replaceRaceOutcome(
            long ownerId,
            long petId,
            long mediaId,
            CountDownLatch bothReadsCompleted,
            CountDownLatch allowMutation
    ) {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                Pet pet = petRepository.findByIdWithOwnerAndProfileAsset(petId)
                        .orElseThrow();
                Media media = mediaRepository.findById(mediaId).orElseThrow();
                bothReadsCompleted.countDown();
                await(bothReadsCompleted);
                await(allowMutation);
                assertThat(pet.replaceProfileAsset(media)).isTrue();
                petRepository.flush();
            });
            return MutationOutcome.success(null);
        } catch (Throwable exception) {
            return MutationOutcome.failure(exception);
        }
    }

    private MutationOutcome deleteRaceOutcome(
            long ownerId,
            long petId,
            CountDownLatch bothReadsCompleted,
            CountDownLatch allowMutation
    ) {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                Pet pet = petRepository.findByIdWithOwnerAndProfileAsset(petId)
                        .orElseThrow();
                bothReadsCompleted.countDown();
                await(bothReadsCompleted);
                await(allowMutation);
                if (pet.removeProfileAsset()) {
                    petRepository.flush();
                }
            });
            return MutationOutcome.success(null);
        } catch (Throwable exception) {
            return MutationOutcome.failure(exception);
        }
    }

    private MutationOutcome initialPostRaceOutcome(
            long ownerId,
            long petId,
            long mediaId,
            CountDownLatch bothReadsCompleted,
            CountDownLatch allowMutation
    ) {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                Pet pet = petRepository.findByIdWithOwnerAndProfileAsset(petId)
                        .orElseThrow();
                Media media = mediaRepository.findById(mediaId).orElseThrow();
                bothReadsCompleted.countDown();
                await(bothReadsCompleted);
                await(allowMutation);
                pet.setInitialProfileAsset(media);
                petRepository.flush();
            });
            return MutationOutcome.success(null);
        } catch (Throwable exception) {
            return MutationOutcome.failure(exception);
        }
    }

    private void assertOneSuccessAndOneOptimisticConflict(
            List<MutationOutcome> outcomes
    ) {
        assertThat(outcomes).filteredOn(MutationOutcome::succeeded)
                .singleElement();
        assertThat(outcomes).filteredOn(outcome ->
                outcome.failure() instanceof ObjectOptimisticLockingFailureException
        ).singleElement();
    }

    private void assertConcurrentConflict(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation
    ) {
        assertThatThrownBy(operation)
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.CONCURRENT_UPDATE_CONFLICT);
    }

    private Map<Long, MediaService.PresignedDownloadUrl> downloadUrls(
            List<Media> mediaItems
    ) {
        Map<Long, MediaService.PresignedDownloadUrl> urls = new LinkedHashMap<>();
        for (Media media : mediaItems) {
            urls.put(media.getId(), new MediaService.PresignedDownloadUrl(
                    "https://presigned.example/media/" + media.getId(),
                    Instant.parse("2030-01-01T00:00:00Z")
            ));
        }
        return Map.copyOf(urls);
    }

    private PetState petState(long petId) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select profile_asset_id, version
                  from pets
                 where id = ?
                """, petId);
        return new PetState(
                (Long) row.get("profile_asset_id"),
                (Long) row.get("version")
        );
    }

    private Map<Long, Map<String, Object>> mediaStates(List<Long> mediaIds) {
        Map<Long, Map<String, Object>> states = new LinkedHashMap<>();
        for (Long mediaId : mediaIds) {
            states.put(mediaId, jdbcTemplate.queryForMap("""
                    select id, media_type, path, status, user_id, file_size,
                           content_type, etag, object_version_id,
                           storage_last_modified, verified_at, deleted_at, attributes
                      from media
                     where id = ?
                    """, mediaId));
        }
        return states;
    }

    private long storageDeleteJobCount() {
        return jdbcTemplate.queryForObject(
                "select count(*) from storage_delete_jobs", Long.class
        );
    }

    private long createUser() {
        String suffix = UUID.randomUUID().toString()
                .replace("-", "")
                .toUpperCase();
        return jdbcTemplate.queryForObject("""
                insert into users (
                    email,
                    password_hash,
                    nickname,
                    public_tag,
                    role,
                    account_status,
                    neighborhood_code
                ) values (?, 'encoded', '보호자', ?, 'USER', 'ACTIVE', '4113111500')
                returning id
                """,
                Long.class,
                suffix + "@example.com",
                "보호자#" + suffix.substring(0, 8)
        );
    }

    private long createPet(long ownerId, Long profileAssetId) {
        String tagSuffix = UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 4)
                .toUpperCase();
        return jdbcTemplate.queryForObject("""
                insert into pets (
                    owner_user_id,
                    public_tag,
                    nickname,
                    status,
                    profile_asset_id
                ) values (?, ?, '반려견', 'ACTIVE', ?)
                returning id
                """,
                Long.class,
                ownerId,
                "반려견#" + tagSuffix,
                profileAssetId
        );
    }

    private long createMedia(long ownerId, String status) {
        return jdbcTemplate.queryForObject("""
                insert into media (
                    media_type,
                    path,
                    status,
                    user_id,
                    file_size
                ) values ('IMAGE', ?, ?, ?, 1024)
                returning id
                """,
                Long.class,
                "pet-profile/" + UUID.randomUUID(),
                status,
                ownerId
        );
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out at concurrent test barrier");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    @FunctionalInterface
    private interface IndexedRace {

        MutationOutcome apply(
                int index,
                CountDownLatch bothReadsCompleted,
                CountDownLatch allowMutation
        );
    }

    private record PetState(Long profileAssetId, long version) {
    }

    private record MutationOutcome(PetResponse response, Throwable failure) {

        private static MutationOutcome success(PetResponse response) {
            return new MutationOutcome(response, null);
        }

        private static MutationOutcome failure(Throwable failure) {
            return new MutationOutcome(null, failure);
        }

        private boolean succeeded() {
            return failure == null;
        }
    }
}
