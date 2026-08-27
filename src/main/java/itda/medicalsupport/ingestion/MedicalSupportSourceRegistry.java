package itda.medicalsupport.ingestion;
import java.util.*; import org.springframework.stereotype.Component;
@Component
public class MedicalSupportSourceRegistry {
 private final Map<String,MedicalSupportSourceAdapter> sources;
 public MedicalSupportSourceRegistry(OfficialSourceHttpClient http){ Map<String,MedicalSupportSourceAdapter> map=new LinkedHashMap<>(); map.put("seoul",new SeoulMedicalSupportSourceAdapter(http)); map.put("seongnam",new SeongnamMedicalSupportSourceAdapter(http)); sources=Map.copyOf(map); }
 public Optional<MedicalSupportSourceAdapter> find(String key){return Optional.ofNullable(sources.get(key));} public Collection<MedicalSupportSourceAdapter> all(){return sources.values();}
}
