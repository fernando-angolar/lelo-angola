package ao.com.angotech.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/health")
public class HealthController {

    @GetMapping("")
    public Map<String, String> health() {
        return Map.of(
                "status", "UP",
                "service", "Lelo Angola API",
                "version", "1.0.0"
        );
    }
}
