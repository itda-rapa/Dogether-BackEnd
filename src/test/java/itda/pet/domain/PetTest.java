package itda.pet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.user.domain.User;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PetTest {

    @Nested
    @DisplayName("Describe: Pet 등록")
    class DescribeRegister {

        @Test
        @DisplayName("It: 입력값과 ACTIVE 초기 상태를 보존한다")
        void itRegistersPetWithActiveInitialState() {
            // given
            User owner = owner(1L);
            LocalDate birthDate = LocalDate.of(2020, 5, 1);
            List<String> personalityTags = new ArrayList<>(
                    List.of("친화적", "활발함")
            );

            // when
            Pet pet = Pet.register(
                    owner,
                    "몽이#A7K2",
                    "몽이",
                    "말티즈",
                    PetSex.FEMALE,
                    true,
                    birthDate,
                    new BigDecimal("3.25"),
                    PetSizeCode.SMALL,
                    "사람을 좋아해요.",
                    personalityTags,
                    "닭고기 알레르기"
            );

            // then
            assertThat(pet.getOwner()).isSameAs(owner);
            assertThat(pet.getPublicTag()).isEqualTo("몽이#A7K2");
            assertThat(pet.getNickname()).isEqualTo("몽이");
            assertThat(pet.getBreedName()).isEqualTo("말티즈");
            assertThat(pet.getSex()).isEqualTo(PetSex.FEMALE);
            assertThat(pet.getNeutered()).isTrue();
            assertThat(pet.getBirthDate()).isEqualTo(birthDate);
            assertThat(pet.getWeightKg()).isEqualByComparingTo("3.25");
            assertThat(pet.getSizeCode()).isEqualTo(PetSizeCode.SMALL);
            assertThat(pet.getBio()).isEqualTo("사람을 좋아해요.");
            assertThat(pet.getPersonalityTags())
                    .containsExactly("친화적", "활발함");
            assertThat(pet.getCareNote()).isEqualTo("닭고기 알레르기");
            assertThat(pet.getStatus()).isEqualTo(PetStatus.ACTIVE);
            assertThat(pet.getDeletedAt()).isNull();
            assertThat(pet.getProfileAsset()).isNull();
            assertThat(pet.isActive()).isTrue();
            assertThat(pet.isDeleted()).isFalse();
            assertThat(pet.belongsTo(1L)).isTrue();
        }

        @Test
        @DisplayName("It: personalityTags를 방어적으로 복사한다")
        void itDefensivelyCopiesPersonalityTags() {
            // given
            List<String> personalityTags = new ArrayList<>(
                    List.of("차분함")
            );

            // when
            Pet pet = Pet.register(
                    owner(1L),
                    "초코#B8M3",
                    "초코",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    personalityTags,
                    null
            );
            personalityTags.add("활발함");

            // then
            assertThat(pet.getPersonalityTags()).containsExactly("차분함");
            assertThatThrownBy(() ->
                    pet.getPersonalityTags().add("새 태그")
            ).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("Describe: Pet 정보 변경")
    class DescribeChange {

        @Test
        @DisplayName("It: nullable 값과 목록을 명시적으로 변경한다")
        void changesMutableFields() {
            Pet pet = mutablePet();
            List<String> tags = new ArrayList<>(List.of("차분함"));

            pet.changeNickname("초코");
            pet.changeBreedName(null);
            pet.changeSex(PetSex.MALE);
            pet.changeNeutered(null);
            pet.changeBirthDate(null);
            pet.changeWeightKg(null);
            pet.changeSizeCode(PetSizeCode.MEDIUM);
            pet.changeBio("");
            pet.changePersonalityTags(tags);
            pet.changeCareNote("   ");
            tags.add("외부 변경");

            assertThat(pet.getNickname()).isEqualTo("초코");
            assertThat(pet.getBreedName()).isNull();
            assertThat(pet.getSex()).isEqualTo(PetSex.MALE);
            assertThat(pet.getNeutered()).isNull();
            assertThat(pet.getBirthDate()).isNull();
            assertThat(pet.getWeightKg()).isNull();
            assertThat(pet.getSizeCode()).isEqualTo(PetSizeCode.MEDIUM);
            assertThat(pet.getBio()).isEmpty();
            assertThat(pet.getPersonalityTags()).containsExactly("차분함");
            assertThat(pet.getCareNote()).isEqualTo("   ");
            assertThat(pet.getPublicTag()).isEqualTo("몽이#A7K2");
            assertThat(pet.getOwner().getId()).isEqualTo(1L);
            assertThat(pet.getStatus()).isEqualTo(PetStatus.ACTIVE);
            assertThat(pet.getDeletedAt()).isNull();
            assertThat(pet.getProfileAsset()).isNull();
        }

        @Test
        @DisplayName("It: BigDecimal scale만 다르면 기존 인스턴스를 유지한다")
        void treatsEqualDecimalValuesAsNoOp() {
            Pet pet = mutablePet();
            BigDecimal original = pet.getWeightKg();

            pet.changeWeightKg(new BigDecimal("3.250"));

            assertThat(pet.getWeightKg()).isSameAs(original);
        }

        @Test
        @DisplayName("It: 목록 내용이 같으면 기존 인스턴스를 유지한다")
        void treatsEqualListAsNoOp() {
            Pet pet = mutablePet();
            Object original = ReflectionTestUtils.getField(
                    pet,
                    "personalityTags"
            );

            pet.changePersonalityTags(List.of("친화적", "활발함"));

            assertThat(ReflectionTestUtils.getField(pet, "personalityTags"))
                    .isSameAs(original);
        }
    }

    private Pet mutablePet() {
        return Pet.register(
                owner(1L),
                "몽이#A7K2",
                "몽이",
                "말티즈",
                PetSex.FEMALE,
                true,
                LocalDate.of(2020, 5, 1),
                new BigDecimal("3.25"),
                PetSizeCode.SMALL,
                "소개",
                List.of("친화적", "활발함"),
                "돌봄 메모"
        );
    }

    private User owner(Long id) {
        User owner = User.register(
                "owner@example.com",
                "encoded",
                "보호자",
                "보호자#A7K2",
                "4113111500"
        );
        ReflectionTestUtils.setField(owner, "id", id);
        return owner;
    }
}
