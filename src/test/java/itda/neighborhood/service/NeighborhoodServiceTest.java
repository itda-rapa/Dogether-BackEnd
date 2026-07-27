package itda.neighborhood.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import itda.neighborhood.domain.Neighborhood;
import itda.neighborhood.dto.NeighborhoodResponse;
import itda.neighborhood.repository.NeighborhoodRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("NeighborhoodService")
class NeighborhoodServiceTest {

    @Mock
    private NeighborhoodRepository neighborhoodRepository;

    @Nested
    @DisplayName("Describe: listActiveNeighborhoods")
    class DescribeListActiveNeighborhoods {

        @Nested
        @DisplayName("Context: Repository가 활성 동네 목록을 반환하면")
        class WithActiveNeighborhoods {

            @Test
            @DisplayName("It: 응답 DTO 목록으로 변환해 같은 순서로 반환한다")
            void itMapsResponsesInRepositoryOrder() {
                // given
                Neighborhood first = neighborhood(
                        "1111000001",
                        "첫 동네"
                );
                Neighborhood second = neighborhood(
                        "1111000002",
                        "둘째 동네"
                );
                given(neighborhoodRepository.findAllByActiveTrueOrderByCodeAsc())
                        .willReturn(List.of(first, second));
                NeighborhoodService neighborhoodService =
                        new NeighborhoodService(neighborhoodRepository);

                // when
                List<NeighborhoodResponse> responses =
                        neighborhoodService.listActiveNeighborhoods();

                // then
                assertThat(responses)
                        .extracting(
                                NeighborhoodResponse::code,
                                NeighborhoodResponse::sidoName,
                                NeighborhoodResponse::sigunguName,
                                NeighborhoodResponse::eupmyeondongName
                        )
                        .containsExactly(
                                tuple("1111000001", "테스트도", "테스트시", "첫 동네"),
                                tuple("1111000002", "테스트도", "테스트시", "둘째 동네")
                        );
                then(neighborhoodRepository)
                        .should()
                        .findAllByActiveTrueOrderByCodeAsc();
            }
        }
    }

    private Neighborhood neighborhood(String code, String eupmyeondongName) {
        Neighborhood neighborhood = mock(Neighborhood.class);
        given(neighborhood.getCode()).willReturn(code);
        given(neighborhood.getSidoName()).willReturn("테스트도");
        given(neighborhood.getSigunguName()).willReturn("테스트시");
        given(neighborhood.getEupmyeondongName()).willReturn(eupmyeondongName);
        return neighborhood;
    }
}
