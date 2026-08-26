package itda.medicalsupport.ingestion;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 서울 '우리동네 동물병원' 지정병원 목록 페이지의 deterministic parser다.
 *
 * <p>공식 목록(https://news.seoul.go.kr/env/archives/567583)은
 * {@code <table>} 안에 {@code 관할 자치구 | 동물병원명 | 소재지 | 전화번호} 4열을 제공하고,
 * 자치구 셀은 그룹의 첫 행에만 {@code rowspan}으로 나타난다. 연속 행은 3개 {@code <td>}만 가진다.
 * 이 parser는 4열 행에서 자치구를 갱신하고 3열 행은 직전 자치구를 이어받는다.
 * 원문이 제공하지 않는 시·도 필드는 추정하지 않고 null로 둔다.
 */
final class SeoulHospitalListParser {
    private SeoulHospitalListParser() { }
    private static final Pattern ROW = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern CELL = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    static List<MedicalSupportCandidate.Hospital> parse(String html) {
        List<MedicalSupportCandidate.Hospital> hospitals = new ArrayList<>();
        String district = null;
        Matcher rows = ROW.matcher(html);
        while (rows.find()) {
            List<String> cells = cells(rows.group(1));
            if (cells.isEmpty()) continue;
            String name, address, phone;
            if (cells.size() >= 4) {
                district = cells.get(0); name = cells.get(1); address = cells.get(2); phone = cells.get(3);
            } else {
                name = cells.get(0); address = cells.get(1); phone = cells.get(2);
            }
            if (name == null || name.isBlank()) continue;
            hospitals.add(new MedicalSupportCandidate.Hospital(name, address, phone, null, district));
        }
        return List.copyOf(hospitals);
    }

    private static List<String> cells(String row) {
        List<String> result = new ArrayList<>();
        Matcher cells = CELL.matcher(row);
        while (cells.find()) result.add(OfficialSourceText.plain(cells.group(1)));
        return result;
    }
}
