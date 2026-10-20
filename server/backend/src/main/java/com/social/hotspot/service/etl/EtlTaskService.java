package com.social.hotspot.service.etl;

import com.social.hotspot.common.ApiResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

// 定义 ETL 业务接口，约定调用方式。
public interface EtlTaskService {
    // 查询批次并包装响应，最多返回 200 条。
    ApiResponse<List<Map<String, Object>>> batchesResponse(int limit);

    // 查询批次日志并包装响应。
    ApiResponse<List<Map<String, Object>>> logsResponse(String batchId);

    // 检查表头并返回 CSV 信息与预览。
    ApiResponse<Map<String, Object>> rawFileResponse() throws Exception;

    // 串行触发增量 ETL，成功后删除旧概览缓存。
    ApiResponse<Map<String, Object>> runIncrementalResponse(Map<String, Object> body) throws Exception;

    // 处理 CSV 上传并包装失败响应。
    ApiResponse<Map<String, Object>> replaceRawCsvResponse(MultipartFile file);
// 结束接口定义。
}
