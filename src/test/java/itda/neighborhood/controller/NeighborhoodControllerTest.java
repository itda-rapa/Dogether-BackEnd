package itda.neighborhood.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import itda.common.filter.JwtFilter;
import itda.neighborhood.dto.NeighborhoodResponse;
import itda.neighborhood.service.NeighborhoodService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(NeighborhoodController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("NeighborhoodController")
class NeighborhoodControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NeighborhoodService neighborhoodService;

    @MockitoBean
    private JwtFilter jwtFilter;

    @Nested
    @DisplayName("Describe: GET /neighborhoods")
    class DescribeListNeighborhoods {

        @Nested
        @DisplayName("Context: Service가 가입 가능한 동네 목록을 반환하면")
        class WithAvailableNeighborhoods {

            @Test
            @DisplayName("It: 200과 성공 Envelope로 목록을 반환한다")
            void itReturnsOkWithNeighborhoods() throws Exception {
                // given
                List<NeighborhoodResponse> responses = List.of(
                        new NeighborhoodResponse(
                                "1111000001",
                                "테스트도",
                                "테스트시",
                                "첫 동네"
                        ),
                        new NeighborhoodResponse(
                                "1111000002",
                                "테스트도",
                                "테스트시",
                                "둘째 동네"
                        )
                );
                given(neighborhoodService.listActiveNeighborhoods())
                        .willReturn(responses);

                // when
                var result = mockMvc.perform(get("/neighborhoods"));

                // then
                result.andExpect(status().isOk())
                        .andExpect(jsonPath("$.success").value(true))
                        .andExpect(jsonPath("$.data.length()").value(2))
                        .andExpect(jsonPath("$.data[0].code").value("1111000001"))
                        .andExpect(jsonPath("$.data[0].sidoName").value("테스트도"))
                        .andExpect(jsonPath("$.data[0].sigunguName").value("테스트시"))
                        .andExpect(jsonPath("$.data[0].eupmyeondongName").value("첫 동네"))
                        .andExpect(jsonPath("$.data[1].code").value("1111000002"));
                then(neighborhoodService).should().listActiveNeighborhoods();
            }
        }
    }
}
