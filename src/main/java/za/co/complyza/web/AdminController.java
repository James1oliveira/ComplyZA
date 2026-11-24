package za.co.complyza.web;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import za.co.complyza.audit.AuditService;
import za.co.complyza.ingest.IngestionService;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final IngestionService ingestion;
    private final AuditService audit;

    public AdminController(IngestionService ingestion, AuditService audit) {
        this.ingestion = ingestion;
        this.audit = audit;
    }

    /**
     * curl -u admin:PASS -F file=@data/popia.pdf -F act=POPIA -F "versionLabel=Act 4 of 2013" \
     *      -F sourceUrl=https://... https://HOST/admin/ingest
     */
    @PostMapping(value = "/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestionService.IngestResult ingest(@RequestParam("file") MultipartFile file,
                                                @RequestParam("act") String act,
                                                @RequestParam("versionLabel") String versionLabel,
                                                @RequestParam(value = "sourceUrl", required = false) String sourceUrl)
            throws IOException {
        return ingestion.ingest(file.getBytes(), file.getOriginalFilename(), act, versionLabel, sourceUrl);
    }

    @GetMapping("/documents")
    public List<Map<String, Object>> documents() {
        return ingestion.documents();
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit(@RequestParam(defaultValue = "50") int limit) {
        return audit.recent(Math.min(limit, 500));
    }
}
