package com.social.hotspot.controller;

import com.social.hotspot.common.ApiResponse;
import com.social.hotspot.service.EtlService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class EtlController {
    private final EtlService etlService;

    public EtlController(EtlService etlService) {
        this.etlService = etlService;
    }

    @GetMapping("/etl/batches")
    public ApiResponse<List<Map<String, Object>>> batches(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        return etlService.batchesResponse(limit);
    }

    @GetMapping("/etl/batches/{batchId}/logs")
    public ApiResponse<List<Map<String, Object>>> logs(@PathVariable("batchId") String batchId) {
        return etlService.logsResponse(batchId);
    }

    @GetMapping("/etl/raw/file")
    public ApiResponse<Map<String, Object>> rawFile() throws Exception {
        return etlService.rawFileResponse();
    }

    @PostMapping("/etl/run")
    public ApiResponse<Map<String, Object>> runEtl(@RequestBody(required = false) Map<String, Object> body) throws Exception {
        return etlService.runIncrementalResponse(body);
    }

    @PostMapping("/etl/raw/replace")
    public ApiResponse<Map<String, Object>> replaceRawCsv(@RequestParam("file") MultipartFile file) {
        return etlService.replaceRawCsvResponse(file);
    }
}