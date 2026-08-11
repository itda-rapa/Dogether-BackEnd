package itda.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class NoEmojiValidator implements ConstraintValidator<NoEmoji, CharSequence> {

    private static final int VARIATION_SELECTOR_16 = 0xFE0F;
    private static final int COMBINING_ENCLOSING_KEYCAP = 0x20E3;
    private static final int REGIONAL_INDICATOR_START = 0x1F1E6;
    private static final int REGIONAL_INDICATOR_END = 0x1F1FF;

    @Override
    public boolean isValid(
            CharSequence value,
            ConstraintValidatorContext context
    ) {
        if (value == null) {
            return true;
        }

        return value.codePoints()
                .noneMatch(this::isDisallowedPictographicCodePoint);
    }

    private boolean isDisallowedPictographicCodePoint(int codePoint) {
        return Character.isExtendedPictographic(codePoint)
                || Character.isEmojiPresentation(codePoint)
                || Character.isEmojiModifier(codePoint)
                || Character.isEmojiModifierBase(codePoint)
                || isRegionalIndicator(codePoint)
                || codePoint == VARIATION_SELECTOR_16
                || codePoint == COMBINING_ENCLOSING_KEYCAP;
    }

    private boolean isRegionalIndicator(int codePoint) {
        return codePoint >= REGIONAL_INDICATOR_START
                && codePoint <= REGIONAL_INDICATOR_END;
    }
}
