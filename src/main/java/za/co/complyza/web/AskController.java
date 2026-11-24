package za.co.complyza.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.complyza.qa.ComplianceQaService;
import za.co.complyza.qa.ComplianceResponse;

import java.security.Principal;

@RestController
@RequestMapping("/api")
public class AskController {

    private final ComplianceQaService qa;

    public AskController(ComplianceQaService qa) {
        this.qa = qa;
    }

    public record AskRequest(@NotBlank @Size(max = 1000) String question) {
    }

    @PostMapping("/ask")
    public ComplianceResponse ask(@Valid @RequestBody AskRequest request, Principal principal) {
        return qa.ask(request.question(), principal == null ? "anonymous" : principal.getName());
    }
}
