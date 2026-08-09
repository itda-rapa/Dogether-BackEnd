package itda.email;

public final class EmailRedisKeys {
    private static final String PREFIX = "dogether:email:v1:";
    private static final String CURRENT_PREFIX = PREFIX + "current:";

    private EmailRedisKeys() {
    }

    public static String challenge(String challengeId) { return PREFIX + "challenge:" + challengeId; }
    public static String current(EmailVerificationPurpose purpose, String emailHmac) {
        return CURRENT_PREFIX + purpose.name() + ":" + emailHmac;
    }
    public static String cooldown(EmailVerificationPurpose purpose, String emailHmac) {
        return PREFIX + "cooldown:" + purpose.name() + ":" + emailHmac;
    }
    public static String token(String tokenHmac) { return PREFIX + "token:" + tokenHmac; }
    public static String payload(String eventId) { return PREFIX + "delivery:payload:" + eventId; }
    public static String currentPrefix() { return CURRENT_PREFIX; }
}
