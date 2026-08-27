package itda.user.dto;

import java.math.BigDecimal;

/**
 * Parsed PATCH /me input. A present flag preserves the distinction between an
 * omitted property and an explicit JSON null.
 */
public record MeUpdateCommand(
        boolean nicknamePresent,
        String nickname,
        boolean neighborhoodCodePresent,
        String neighborhoodCode,
        boolean weightKgPresent,
        BigDecimal weightKg
) {

    public boolean hasAnyPresentField() {
        return nicknamePresent || neighborhoodCodePresent || weightKgPresent;
    }
}
