package itda.neighborhood;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Neighborhood API")
class NeighborhoodApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Nested
    @DisplayName("Describe: GET /neighborhoods")
    class DescribeListNeighborhoods {

        @Nested
        @DisplayName("Context: 활성·비활성 동네가 저장되어 있으면")
        class WithActiveAndInactiveNeighborhoods {

            @Test
            @Transactional
            @DisplayName("It: 익명 사용자에게 활성 동네만 code ASC로 반환한다")
            void itReturnsActiveNeighborhoodsInCodeOrderToAnonymousUser() throws Exception {
                // given
                insertNeighborhood("9999900002", "높은 동네", true);
                insertNeighborhood("9999900001", "낮은 동네", true);
                insertNeighborhood("9999900003", "비활성 동네", false);

                // when
                var result = mockMvc.perform(get("/neighborhoods"));

                // then
                result.andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.length()").value(2))
                        .andExpect(jsonPath("$.data[0].code").value("9999900001"))
                        .andExpect(jsonPath("$.data[1].code").value("9999900002"));
            }
        }
    }

    private void insertNeighborhood(
            String code,
            String eupmyeondongName,
            boolean active
    ) {
        jdbcTemplate.update("""
                insert into neighborhoods (
                    code,
                    sido_name,
                    sigungu_name,
                    eupmyeondong_name,
                    active,
                    created_at,
                    updated_at
                ) values (?, '테스트도', '테스트시', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                code,
                eupmyeondongName,
                active
        );
    }
}
