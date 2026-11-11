package com.social.hotspot.service.analysis;

import java.util.List;
import java.util.Map;

/** 内容明细业务接口，实现位于 ContentServiceImpl。 */
public interface ContentService {
    /** 内容明细：按关键词、平台、情感和类别筛选，返回当前页内容、总条数和分页信息。 */
    Map<String, Object> contentPage(String eventId, String query, String platform,
                                    String sentiment, String category, int page, int pageSize);
}
