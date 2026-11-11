package com.social.hotspot.service.analysis;

import java.util.List;
import java.util.Map;

/** 平台扩散业务接口，实现位于 PlatformSpreadServiceImpl。 */
public interface PlatformSpreadService {
    /** 平台扩散：返回扩散网络、热度漏斗、类别响应时间线和结论摘要所需的数据。 */
    Map<String, Object> platformSpread(String eventId);

    /** 平台扩散 / 查看依据：按平台、可选类别和小时返回分页新闻及同口径的扩散计算依据。 */
    Map<String, Object> propagationEvidence(String eventId, String platform, String category, String hour, int page);

    Map<String, Object> propagationEvidence(String eventId, String platform, String category, String hour, int page, String date);
}
