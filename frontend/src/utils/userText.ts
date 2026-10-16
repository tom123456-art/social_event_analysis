// 所有用户提示经过此入口，第三方英文错误和堆栈不直接进入页面。
export function chineseMessage(value: unknown, fallback = '操作失败，请稍后重试。'): string {
  // 非字符串输入不直接展示。
  if (typeof value !== 'string') return fallback
  // 清理提示两侧空白。
  const text = value.trim()
  // 无中文内容的第三方消息使用备用提示。
  if (!text || !/[\u4e00-\u9fff]/.test(text)) return fallback
  // 拦截多行堆栈、乱码标记、英文错误句和 Java/SQL 技术信息。
  if (/\r|\n|\uFFFD|[A-Za-z]{2,}\s+[A-Za-z]{2,}|(?:Exception|Traceback|Error:|SQLSTATE|jdbc:|java\.|org\.|com\.)/.test(text)) return fallback
  // 仅返回通过格式和内容检查的中文提示。
  return text
}

// 显示名称只在展示时转换，原始状态码仍用于筛选和提交。
function display(value: unknown, labels: Record<string, string>, fallback: string): string {
  // 将状态等显示值统一为去除空白的文本。
  const code = String(value ?? '').trim()
  // 缺失值使用明确占位，而不是显示空区域。
  if (!code) return '未提供'
  // 优先匹配英文编码，仅允许原始纯中文名称直接展示。
  return labels[code.toUpperCase()] || (/^[\u4e00-\u9fff、\s]+$/.test(code) ? code : fallback)
}

// 将执行、审核和启用状态转为中文名称。
export function statusName(value: unknown): string {
  return display(value, { SUCCESS: '成功', FAILED: '失败', RUNNING: '运行中', PENDING: '待审核', APPROVED: '已通过', REJECTED: '已驳回', ENABLED: '启用', DISABLED: '禁用', ACTIVE: '有效', INACTIVE: '停用' }, '未知状态')
}

// 将账号角色编码转为中文名称。
export function roleName(value: unknown): string {
  return display(value, { ADMIN: '管理员', USER: '普通用户' }, '未知角色')
}

// 将来源平台编码转为中文名称。
export function platformName(value: unknown): string {
  return display(value, { TENCENT_NEWS: '腾讯新闻', NETEASE_NEWS: '网易新闻', SOHU_NEWS: '搜狐新闻', SINA_NEWS: '新浪新闻', THE_PAPER: '澎湃新闻', WEIBO: '微博', NEWS: '新闻', FRONT: '用户投稿', UNKNOWN: '未知平台' }, '其他平台')
}

// 将情感标签转为中文名称，未知值不伪装为中性。
export function sentimentName(value: unknown): string {
  return display(value, { POSITIVE: '正面', NEGATIVE: '负面', NEUTRAL: '中性', UNKNOWN: '未分类' }, '未分类')
}

// 将分析类别编码转为标准中文类别。
export function categoryName(value: unknown): string {
  return display(value, { FINANCE: '财经', POLITICS: '政治', TECHNOLOGY: '科技', SPORTS: '体育', CULTURE: '文娱', SOCIETY: '社会', GENERAL: '综合' }, '未分类')
}

// 将用户互动类型转为中文动作名。
export function actionName(value: unknown): string {
  return display(value, { COMMENT: '评论', LIKE: '点赞', FAVORITE: '收藏', SHARE: '分享', REPOST: '转发', VIEW: '浏览' }, '其他互动')
}

// 将仓库层级及任务终态转为处理阶段名称。
export function stageName(value: unknown): string {
  return display(value, { ODS: '原始数据接入', DWD: '明细清洗', DWS: '指标汇总', ADS: '应用指标生成', MYSQL_SYNC: '数据库同步', FAILED: '执行失败', COMPLETED: '已完成', DONE: '已完成' }, '处理中')
}

// 将 ETL 任务类名映射为业务任务名称。
export function taskName(value: unknown): string {
  // 定义后端任务标识与中文说明的对应关系。
  const labels: Record<string, string> = {
    RawToOdsJob: '原始数据接入', FullEtlJob: '全量数据处理',
    OdsToDwdCleanJob: '原始数据清洗', DwdToDwsAggregateJob: '明细指标汇总', DwsToAdsAndMysqlSyncJob: '应用指标与数据库同步',
    DataQualityStructural: '数据结构检查', DataQualityTextNormalization: '文本规范化', DataQualityTextEncoding: '文本编码检查',
    DataQualityCompletenessValidity: '完整性与有效性检查', DataQualityUniqueness: '重复记录检查', SentimentConfidenceFilter: '情感置信度筛选'
  }
  // 未知任务统一显示为数据处理任务，不暴露内部类名。
  return labels[String(value ?? '')] || '数据处理任务'
}

// 将公共数据集标识转换为系统事件名，其余名称执行中文安全过滤。
export function eventName(value: unknown): string {
  return value === 'public_rss_latest' ? '网络媒体热点事件传播态势与舆情分析' : chineseMessage(value, '热点事件')
}
