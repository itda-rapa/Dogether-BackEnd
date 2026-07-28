package itda.chat.dto.response;

/**
 * M1 counterpart-pet display item for the chat room response.
 *
 * <p>In M1, {@code profileUrl} is always {@code null} and {@code verified} is always {@code false}.
 * {@code relationship} is nullable because no friend contract exists yet — see M1-015.
 */
public record PetSearchItem(
        Long petId,
        String publicTag,
        String nickname,
        String profileUrl,
        boolean verified,
        String relationship
) {
}