package itda.node.service;

import itda.node.domain.NetworkNode;
import itda.node.dto.NodeRequest;
import itda.node.repository.NodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NodeService {

    private final NodeRepository nodeRepository;

    public NetworkNode getNearestNode(NodeRequest request) {
        return nodeRepository.findNearestNodeOrThrow(
                request.longitude(),
                request.latitude()
        );
    }

}
