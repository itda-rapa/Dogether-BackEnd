package itda.medicalsupport.ingestion;

import java.util.Comparator;
import java.util.List;
final class SemanticFingerprint {
    private SemanticFingerprint() {}
    static String of(String... fields) { return OfficialSourceText.sha256(String.join("|", java.util.Arrays.stream(fields).map(SemanticFingerprint::canonical).toList())); }
    static String canonical(String value) { return value == null ? "" : value.replaceAll("\\s+", " ").trim(); }
    static String hospitals(List<MedicalSupportCandidate.Hospital> hospitals) { return hospitals.stream().map(h -> canonical(h.name()) + "~" + canonical(h.address()) + "~" + canonical(h.phone()) + "~" + canonical(h.sidoName()) + "~" + canonical(h.sigunguName())).sorted(Comparator.naturalOrder()).reduce("", (a,b) -> a + "|" + b); }
}
