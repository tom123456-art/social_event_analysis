package com.social.hotspot.controller;

import com.social.hotspot.common.ApiResponse;
import com.social.hotspot.service.etl.EtlTaskService;
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

// 注册控制器，返回对象由 Spring 转为 JSON。
@RestController
// 设置控制器统一接口路径前缀。
@RequestMapping("/api")
// 定义 ETL 任务控制器类。
public class EtlTaskController {
    // 保存 ETL 业务服务。
    private final EtlTaskService etlService;

    // 构造 ETL 任务控制器，接收所需组件并保存依赖。
    public EtlTaskController(EtlTaskService etlService) {
        this.etlService = etlService;
    }

    // 将 GET 请求 /etl/batches 交给下面的方法。
    @GetMapping("/etl/batches")
    // 查询 ETL 批次列表，请求注解中的默认值和可选性决定输入规则。
    public ApiResponse<List<Map<String, Object>>> batches(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        // 返回查询批次并包装响应，最多返回 200 条。
        return etlService.batchesResponse(limit);
    }

    // 将 GET 请求 /etl/batches/{batchId}/logs 交给下面的方法。
    @GetMapping("/etl/batches/{batchId}/logs")
    // 查询批次日志。
    public ApiResponse<List<Map<String, Object>>> logs(@PathVariable("batchId") String batchId) {
        // 返回查询批次日志并包装响应。
        return etlService.logsResponse(batchId);
    }

    // 将 GET 请求 /etl/raw/file 交给下面的方法。
    @GetMapping("/etl/raw/file")
    // 查询原始 CSV 文件信息与预览。
    public ApiResponse<Map<String, Object>> rawFile() throws Exception {
        // 返回检查表头并返回 CSV 信息与预览。
        return etlService.rawFileResponse();
    }

    // 将 POST 请求 /etl/run 交给下面的方法。
    @PostMapping("/etl/run")
    // 触发增量 ETL，请求注解中的默认值和可选性决定输入规则。
    public ApiResponse<Map<String, Object>> runEtl(@RequestBody(required = false) Map<String, Object> body) throws Exception {
        // 返回串行触发增量 ETL，成功后删除旧概览缓存。
        return etlService.runIncrementalResponse(body);
    }

    // 将 POST 请求 /etl/raw/replace 交给下面的方法。
    @PostMapping("/etl/raw/replace")
    // 接收上传的 CSV 文件，交给 ETL 业务层校验、全量处理并替换原始数据。
    public ApiResponse<Map<String, Object>> replaceRawCsv(@RequestParam("file") MultipartFile file) {
        // 返回处理 CSV 上传并包装失败响应。
        return etlService.replaceRawCsvResponse(file);
    }
}
