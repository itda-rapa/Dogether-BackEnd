package itda.pet.repository;

import static org.assertj.core.api.Assertions.assertThat;

import itda.pet.domain.Pet;
import itda.pet.domain.PetSex;
import itda.pet.domain.PetSizeCode;
import itda.pet.domain.PetStatus;
import itda.user.domain.AccountStatus;
import itda.user.domain.User;
import itda.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class PetRepositoryTest {

    @Autowired
    private PetRepository petRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User owner;

    @BeforeEach
    void setUp() {
        owner = userRepository.saveAndFlush(newUser());
    }

    @Nested
    @DisplayName("Describe: Pet 저장과 조회")
    class DescribePersistence {

        @Test
        @DisplayName("It: Pet과 personalityTags를 저장하고 다시 조회한다")
        void itStoresAndLoadsPetWithPersonalityTags() {
            // given
            Pet saved = petRepository.saveAndFlush(
                    pet("몽이#A7K2", "몽이", List.of("친화적", "활발함"))
            );
            entityManager.clear();

            // when
            Pet found = petRepository.findById(saved.getId()).orElseThrow();

            // then
            assertThat(found.getOwner().getId()).isEqualTo(owner.getId());
            assertThat(found.getPublicTag()).isEqualTo("몽이#A7K2");
            assertThat(found.getNickname()).isEqualTo("몽이");
            assertThat(found.getPersonalityTags())
                    .containsExactly("친화적", "활발함");
        }

        @Test
        @DisplayName("It: PublicTag 존재 여부를 조회한다")
        void itChecksPublicTagExistence() {
            // given
            petRepository.saveAndFlush(
                    pet("초코#B8M3", "초코", List.of("차분함"))
            );

            // when and then
            assertThat(petRepository.existsByPublicTag("초코#B8M3")).isTrue();
            assertThat(petRepository.existsByPublicTag("없는펫#C9N4")).isFalse();
        }

        @Test
        @DisplayName("It: Pet ID로 비관적 잠금 조회한다")
        void itFindsPetByIdForUpdate() {
            // given
            Pet saved = petRepository.saveAndFlush(
                    pet("보리#C9N4", "보리", null)
            );
            entityManager.clear();

            // when
            Pet locked = petRepository.findByIdForUpdate(saved.getId())
                    .orElseThrow();

            // then
            assertThat(locked.getId()).isEqualTo(saved.getId());
            assertThat(entityManager.getLockMode(locked))
                    .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        }
    }

    @Nested
    @DisplayName("Describe: 검색 가능한 Pet PublicTag 정확 조회")
    class DescribeSearchablePublicTagQuery {

        @Test
        @DisplayName("It: ACTIVE·미삭제 Pet과 ACTIVE owner를 정확 조회한다")
        void itFindsExactSearchablePetWithOwner() {
            Pet saved = petRepository.saveAndFlush(
                    pet("몽이#A7K2", "몽이", null)
            );
            entityManager.clear();

            Pet found = petRepository.findSearchableByPublicTag(
                    "몽이#A7K2",
                    PetStatus.ACTIVE,
                    AccountStatus.ACTIVE
            ).orElseThrow();

            assertThat(found.getId()).isEqualTo(saved.getId());
            assertThat(found.getOwner().getId()).isEqualTo(owner.getId());
        }

        @Test
        @DisplayName("It: Repository에서 공백·부분·대소문자를 변환하지 않는다")
        void itDoesNotNormalizeOrPartiallyMatch() {
            petRepository.saveAndFlush(
                    pet("몽이#A7K2", "몽이", null)
            );
            entityManager.clear();

            assertThat(search(" 몽이#A7K2 ")).isEmpty();
            assertThat(search("몽이#A7K")).isEmpty();
            assertThat(search("몽이#a7k2")).isEmpty();
        }

        @Test
        @DisplayName("It: SUSPENDED Pet을 제외한다")
        void itExcludesSuspendedPet() {
            Pet saved = petRepository.saveAndFlush(
                    pet("몽이#A7K2", "몽이", null)
            );
            jdbcTemplate.update(
                    "update pets set status = 'SUSPENDED' where id = ?",
                    saved.getId()
            );
            entityManager.clear();

            assertThat(search("몽이#A7K2")).isEmpty();
        }

        @Test
        @DisplayName("It: DELETED Pet을 제외한다")
        void itExcludesDeletedPet() {
            Pet saved = petRepository.saveAndFlush(
                    pet("몽이#A7K2", "몽이", null)
            );
            jdbcTemplate.update("""
                    update pets
                       set status = 'DELETED', deleted_at = CURRENT_TIMESTAMP
                     where id = ?
                    """, saved.getId());
            entityManager.clear();

            assertThat(search("몽이#A7K2")).isEmpty();
        }

        @Test
        @DisplayName("It: ACTIVE가 아닌 owner의 Pet을 제외한다")
        void itExcludesInactiveOwner() {
            petRepository.saveAndFlush(
                    pet("몽이#A7K2", "몽이", null)
            );
            jdbcTemplate.update(
                    "update users set account_status = 'SUSPENDED' where id = ?",
                    owner.getId()
            );
            entityManager.clear();

            assertThat(search("몽이#A7K2")).isEmpty();
        }
    }

    @Nested
    @DisplayName("Describe: owner별 미삭제 Pet 조회")
    class DescribeOwnerQueries {

        @Test
        @DisplayName("It: ACTIVE와 SUSPENDED는 세고 DELETED는 제외한다")
        void itCountsOnlyNotDeletedPets() {
            // given
            Pet active = petRepository.saveAndFlush(
                    pet("구름#D2P5", "구름", null)
            );
            Pet suspended = petRepository.saveAndFlush(
                    pet("두부#E3Q6", "두부", null)
            );
            Pet deleted = petRepository.saveAndFlush(
                    pet("라떼#F4R7", "라떼", null)
            );
            jdbcTemplate.update(
                    "update pets set status = 'SUSPENDED' where id = ?",
                    suspended.getId()
            );
            jdbcTemplate.update("""
                    update pets
                       set status = 'DELETED',
                           deleted_at = CURRENT_TIMESTAMP
                     where id = ?
                    """,
                    deleted.getId()
            );
            entityManager.clear();

            // when
            long count = petRepository
                    .countByOwner_IdAndDeletedAtIsNull(owner.getId());

            // then
            assertThat(count).isEqualTo(2);
            assertThat(active.getId()).isNotNull();
        }

        @Test
        @DisplayName("It: 미삭제 Pet을 createdAt과 ID 오름차순으로 조회한다")
        void itListsNotDeletedPetsInCreationOrder() {
            // given
            Pet first = petRepository.saveAndFlush(
                    pet("마루#G5S8", "마루", null)
            );
            Pet second = petRepository.saveAndFlush(
                    pet("나무#H6T9", "나무", null)
            );
            Pet deleted = petRepository.saveAndFlush(
                    pet("호두#J7U2", "호두", null)
            );
            jdbcTemplate.update("""
                    update pets
                       set status = 'DELETED',
                           deleted_at = CURRENT_TIMESTAMP
                     where id = ?
                    """,
                    deleted.getId()
            );
            entityManager.clear();

            // when
            List<Pet> pets = petRepository
                    .findAllByOwner_IdAndDeletedAtIsNullOrderByCreatedAtAscIdAsc(
                            owner.getId()
                    );

            // then
            assertThat(pets)
                    .extracting(Pet::getId)
                    .containsExactly(first.getId(), second.getId());
        }
    }

    private User newUser() {
        String unique = UUID.randomUUID().toString().replace("-", "");
        return User.register(
                unique + "@example.com",
                "encoded",
                "보호자",
                "보호자#" + unique.substring(0, 8),
                "4113111500"
        );
    }

    private java.util.Optional<Pet> search(String publicTag) {
        return petRepository.findSearchableByPublicTag(
                publicTag,
                PetStatus.ACTIVE,
                AccountStatus.ACTIVE
        );
    }

    private Pet pet(
            String publicTag,
            String nickname,
            List<String> personalityTags
    ) {
        return Pet.register(
                owner,
                publicTag,
                nickname,
                "말티즈",
                PetSex.UNKNOWN,
                false,
                LocalDate.of(2021, 3, 15),
                new BigDecimal("4.50"),
                PetSizeCode.SMALL,
                "소개",
                personalityTags,
                "돌봄 메모"
        );
    }
}
