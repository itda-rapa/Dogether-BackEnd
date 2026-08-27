package itda.medicalsupport.ingestion;
public interface MedicalSupportSourceAdapter { String key(); String sourceUrl(); MedicalSupportCandidate collect(); }
