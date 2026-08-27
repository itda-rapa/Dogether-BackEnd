package itda.medicalsupport.ingestion;

import itda.medicalsupport.domain.*;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

public record MedicalSupportCandidate(String sourceUrl, String sourceDocumentUrl, String sourceOrganization,
 Instant sourcePublishedAt, Instant fetchedAt, String sourceHash, String contentType, String parserVersion,
 String stableSourceProgramId, String regionCode, MedicalSupportRegionScope regionScope,
 String regionSidoName, String regionSigunguName,
 Integer programYear, String programName, String normalizedProgramName, String summary, String supportAmount,
 String applicationPeriod, String supportTarget, String supportItems, String applicationMethod,
 String animalRegistrationCondition, String incomeWelfareCondition, String contact,
 MedicalSupportHospitalPolicy hospitalPolicy, MedicalSupportProgramStatus programStatus, List<Hospital> hospitals,
 String semanticFingerprint) {
 public record Hospital(String name,String address,String phone,String sidoName,String sigunguName) {}
 public MedicalSupportCandidate { hospitals=List.copyOf(hospitals); normalizedProgramName=normalize(programName); }
 public static String normalize(String value){ return value==null?null:value.replaceAll("[\\p{Punct}]+", " ").trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }
}
