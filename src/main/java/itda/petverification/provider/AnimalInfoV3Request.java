package itda.petverification.provider;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public record AnimalInfoV3Request(
        IdentifierType identifierType,
        String identifier,
        String ownerName,
        LocalDate ownerBirthDate
) {
    private static final DateTimeFormatter OWNER_BIRTH_FORMAT = DateTimeFormatter.ofPattern("yyMMdd");

    public enum IdentifierType { REGISTRATION_NUMBER, RFID }

    public boolean usesOwnerName() {
        return ownerName != null && !ownerName.isBlank();
    }

    public String formattedOwnerBirthDate() {
        return ownerBirthDate.format(OWNER_BIRTH_FORMAT);
    }
}
