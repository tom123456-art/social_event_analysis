package com.social.hotspot.service.analysis;

import java.util.List;
import java.util.Map;

/** 数据大屏业务接口，实现位于 ScreenServiceImpl。 */
public interface ScreenService {
    /** 数据大屏：汇总平台、热度、内容、关键词、类别和情感数据；智能助手也复用其中的摘要。 */
    Map<String, Object> screen(String eventId);
}
