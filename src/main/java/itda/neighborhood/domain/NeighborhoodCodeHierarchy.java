package itda.neighborhood.domain;

import java.util.regex.Pattern;

/**
 * Canonical hierarchy of the 10-digit legal-dong code stored by Neighborhood.
 * The first two digits identify SIDO and the first five identify SIGUNGU.
 */
public record NeighborhoodCodeHierarchy(String sidoCode, String sigunguCode) {

    private static final Pattern LEGAL_DONG_CODE = Pattern.compile("\\d{10}");

    public static NeighborhoodCodeHierarchy from(Neighborhood neighborhood) {
        return fromCode(neighborhood.getCode());
    }

    public static NeighborhoodCodeHierarchy fromCode(String code) {
        if (code == null || !LEGAL_DONG_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("invalid canonical neighborhood code: " + code);
        }
        return new NeighborhoodCodeHierarchy(code.substring(0, 2), code.substring(0, 5));
    }
}
