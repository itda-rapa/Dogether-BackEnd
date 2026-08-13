package itda.pet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.pet.domain.PetSex;
import itda.pet.domain.PetSizeCode;
import itda.pet.dto.PetCreateRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PetCreateCommand")
class PetCreateCommandTest {

    @Test
    @DisplayName("It: Request의 정규화된 필드를 내부 Command로 복사한다")
    void itCopiesRequestFields() {
        PetCreateRequest request = new PetCreateRequest(
                " 몽이 ",
                "견종",
                PetSex.MALE,
                true,
                LocalDate.of(2020, 1, 1),
                new BigDecimal("3.25"),
                PetSizeCode.SMALL,
                "소개",
                List.of("친화적"),
                "산책",
                null
        );

        PetCreateCommand command = request.toCommand();

        assertThat(command.nickname()).isEqualTo("몽이");
        assertThat(command.breedName()).isEqualTo("견종");
        assertThat(command.sex()).isEqualTo(PetSex.MALE);
        assertThat(command.neutered()).isTrue();
        assertThat(command.birthDate()).isEqualTo(LocalDate.of(2020, 1, 1));
        assertThat(command.weightKg()).isEqualByComparingTo("3.25");
        assertThat(command.sizeCode()).isEqualTo(PetSizeCode.SMALL);
        assertThat(command.bio()).isEqualTo("소개");
        assertThat(command.personalityTags()).containsExactly("친화적");
        assertThat(command.careNote()).isEqualTo("산책");
    }

    @Test
    @DisplayName("It: personalityTags를 방어적으로 복사한다")
    void itDefensivelyCopiesPersonalityTags() {
        List<String> originalTags = new ArrayList<>(List.of("친화적"));

        PetCreateCommand command = new PetCreateCommand(
                "몽이",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                originalTags,
                null
        );
        originalTags.add("활발함");

        assertThat(command.personalityTags()).containsExactly("친화적");
        assertThatThrownBy(() -> command.personalityTags().add("차분함"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
