package cn.edu.ha.secagent.organization;

import cn.edu.ha.secagent.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/organizations")
@RequiredArgsConstructor
public class OrganizationController {
    private final OrganizationRepository repository;

    @GetMapping("/public")
    List<?> publicOrganizations() {
        return repository.findAll().stream()
                .map(org -> Map.of("id", org.getId(), "code", org.getCode(), "name", org.getName()))
                .toList();
    }
}

