package itda.neighborhood.service;

import itda.neighborhood.dto.NeighborhoodResponse;
import itda.neighborhood.repository.NeighborhoodRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NeighborhoodService {

    private final NeighborhoodRepository neighborhoodRepository;

    public NeighborhoodService(NeighborhoodRepository neighborhoodRepository) {
        this.neighborhoodRepository = neighborhoodRepository;
    }

    @Transactional(readOnly = true)
    public List<NeighborhoodResponse> listActiveNeighborhoods() {
        return neighborhoodRepository.findAllByActiveTrueOrderByCodeAsc()
                .stream()
                .map(NeighborhoodResponse::from)
                .toList();
    }
}
