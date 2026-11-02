// 所有分析页共用当前 ETL 数据集，历史导入批次不作为独立事件切换。
// 接口查询使用固定标识，页面展示使用中文名称，两者分别维护。
export const ANALYSIS_EVENT_ID = 'public_rss_latest'
// 定义页面展示的统一中文系统名称。
export const ANALYSIS_EVENT_NAME = '网络媒体热点事件传播态势与舆情分析系统'
