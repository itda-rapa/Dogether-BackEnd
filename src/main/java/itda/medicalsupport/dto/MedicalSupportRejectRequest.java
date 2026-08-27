package itda.medicalsupport.dto;
import jakarta.validation.constraints.*; public record MedicalSupportRejectRequest(@NotBlank @Size(max=1000) String reason) {}
