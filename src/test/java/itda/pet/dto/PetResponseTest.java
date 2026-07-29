package itda.pet.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import itda.pet.domain.PetSex;
import itda.pet.domain.PetSizeCode;
import itda.pet.domain.PetStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PetResponse")
class PetResponseTest {

    @Nested
    @DisplayName("Describe: personalityTags를 응답 계약에 맞게 보관한다")
    class DescribePersonalityTags {

        @Test
        @DisplayName("It: null을 빈 불변 List로 정규화한다")
        void itNormalizesNullToEmptyImmutableList() {
            PetResponse response = response(null);

            assertThat(response.personalityTags()).isEmpty();
            assertThatThrownBy(() -> response.personalityTags().add("차분함"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("It: 가변 List를 불변 복사본으로 보관한다")
        void itDefensivelyCopiesMutableList() {
            List<String> source = new ArrayList<>();
            source.add("친화적");

            PetResponse response = response(source);
            source.add("활발함");

            assertThat(response.personalityTags()).containsExactly("친화적");
            assertThatThrownBy(() -> response.personalityTags().add("차분함"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    private PetResponse response(List<String> personalityTags) {
        return new PetResponse(
                2L,
                1L,
                "몽이#B8M3",
                "보호자#A7K2",
                "몽이",
                "말티즈",
                PetSex.FEMALE,
                true,
                LocalDate.of(2020, 1, 2),
                new BigDecimal("3.40"),
                PetSizeCode.SMALL,
                "사람을 좋아해요.",
                personalityTags,
                "닭고기 알레르기",
                null,
                PetStatus.ACTIVE,
                null,
                false,
                null,
                false
        );
    }
}
