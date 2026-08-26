package itda.media.service;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * ISO Base Media File Format의 movie header(mvhd)에서 영상 재생 시간을 추출한다.
 *
 * <p>파일 전체를 대상으로 box 경계를 검증하므로, 클라이언트 메타데이터나 파일명에 의존하지
 * 않는다. Chat VIDEO는 검증된 최대 50 MiB 객체만 이 추출기에 전달해야 한다.</p>
 */
final class Mp4DurationExtractor {

    private static final String MOOV = "moov";
    private static final String MVHD = "mvhd";
    private static final BigInteger THOUSAND = BigInteger.valueOf(1_000L);

    private Mp4DurationExtractor() {
    }

    static BigInteger durationMillis(byte[] source) {
        if (source == null || source.length < 8) {
            throw invalid();
        }
        Box moov = findBox(source, 0, source.length, MOOV);
        if (moov == null) {
            throw invalid();
        }
        Box mvhd = findBox(source, moov.payloadStart(), moov.end(), MVHD);
        if (mvhd == null) {
            throw invalid();
        }
        return parseMovieHeader(source, mvhd);
    }

    private static BigInteger parseMovieHeader(byte[] source, Box mvhd) {
        int offset = mvhd.payloadStart();
        requireRemaining(offset, mvhd.end(), 4);
        int version = unsignedByte(source[offset]);
        if (version == 0) {
            requireRemaining(offset, mvhd.end(), 20);
            long timescale = unsignedInt(source, offset + 12);
            long duration = unsignedInt(source, offset + 16);
            return toMillis(BigInteger.valueOf(duration), timescale);
        }
        if (version == 1) {
            requireRemaining(offset, mvhd.end(), 32);
            long timescale = unsignedInt(source, offset + 20);
            BigInteger duration = unsignedLong(source, offset + 24);
            return toMillis(duration, timescale);
        }
        throw invalid();
    }

    private static BigInteger toMillis(BigInteger duration, long timescale) {
        if (timescale <= 0 || duration.signum() < 0) {
            throw invalid();
        }
        return duration.multiply(THOUSAND).divide(BigInteger.valueOf(timescale));
    }

    private static Box findBox(byte[] source, int from, int to, String type) {
        int cursor = from;
        while (cursor < to) {
            requireRemaining(cursor, to, 8);
            long size32 = unsignedInt(source, cursor);
            String actualType = new String(source, cursor + 4, 4, StandardCharsets.ISO_8859_1);
            int headerSize = 8;
            long boxSize = size32;
            if (size32 == 1) {
                requireRemaining(cursor, to, 16);
                BigInteger extendedSize = unsignedLong(source, cursor + 8);
                if (extendedSize.compareTo(BigInteger.valueOf(to - cursor)) > 0) {
                    throw invalid();
                }
                boxSize = extendedSize.longValue();
                headerSize = 16;
            } else if (size32 == 0) {
                boxSize = to - cursor;
            }
            if (boxSize < headerSize || boxSize > to - cursor) {
                throw invalid();
            }
            int end = Math.toIntExact(cursor + boxSize);
            if (type.equals(actualType)) {
                return new Box(cursor + headerSize, end);
            }
            cursor = end;
        }
        return null;
    }

    private static long unsignedInt(byte[] source, int offset) {
        return ((long) unsignedByte(source[offset]) << 24)
                | ((long) unsignedByte(source[offset + 1]) << 16)
                | ((long) unsignedByte(source[offset + 2]) << 8)
                | unsignedByte(source[offset + 3]);
    }

    private static BigInteger unsignedLong(byte[] source, int offset) {
        byte[] unsigned = new byte[9];
        System.arraycopy(source, offset, unsigned, 1, 8);
        return new BigInteger(unsigned);
    }

    private static int unsignedByte(byte value) {
        return Byte.toUnsignedInt(value);
    }

    private static void requireRemaining(int offset, int end, int required) {
        if (offset < 0 || required < 0 || offset > end || end - offset < required) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid MP4 movie header");
    }

    private record Box(int payloadStart, int end) {
    }
}
