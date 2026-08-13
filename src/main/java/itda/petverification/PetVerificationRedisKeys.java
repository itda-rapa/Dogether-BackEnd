package itda.petverification;

final class PetVerificationRedisKeys {
    private static final String PREFIX = "dogether:pet-verification:v1:token:";
    private PetVerificationRedisKeys() { }
    static String token(String tokenHmac) { return PREFIX + tokenHmac; }
}
