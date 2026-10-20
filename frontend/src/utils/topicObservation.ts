// 定义前端可展示的互动指标字段和中文名称。
export const INTERACTION_METRICS = [
  { field: 'like_count', label: '点赞' },
  { field: 'comment_count', label: '评论' },
  { field: 'repost_count', label: '转发' },
  { field: 'favorite_count', label: '收藏' }
]

// 单独出现的国家实体只表示语境，不作为独立新闻主题。
const COUNTRY_ENTITIES = new Set([
  '中国', '美国', '日本', '俄罗斯', '英国', '法国', '德国', '韩国', '朝鲜',
  '印度', '加拿大', '澳大利亚', '新西兰', '巴西', '南非', '意大利', '西班牙',
  '乌克兰', '以色列', '伊朗', '伊拉克', '土耳其', '巴基斯坦', '阿富汗',
  '越南', '泰国', '新加坡', '马来西亚', '印度尼西亚', '菲律宾', '埃及',
  '沙特阿拉伯', '阿联酋', '墨西哥', '阿根廷', '瑞士', '瑞典', '挪威',
  '芬兰', '丹麦', '荷兰', '比利时', '波兰', '奥地利', '葡萄牙', '希腊',
  '国家', '世界', '国际', '国内', '全国', '国外'
])

export function isContextOnlyKeyword(keyword: string) {
  // 判断关键词去空格后是否属于国家语境词。
  return COUNTRY_ENTITIES.has(keyword.trim())
}

export function numericInteraction(value: unknown): number | null {
  // 空值和空白文本没有可用互动量。
  if (value === null || value === undefined || String(value).trim() === '') return null
  // 将输入值转换为数字。
  const number = Number(value)
  // 仅保留有限且非负的互动量。
  return Number.isFinite(number) && number >= 0 ? number : null
}

export function topicMedian(values: number[]): number | null {
  // 复制并升序排列有效数值，避免修改调用方数组。
  const sorted = values.filter(Number.isFinite).sort((a, b) => a - b)
  // 没有有效值时无法计算中位数。
  if (!sorted.length) return null
  // 计算中间位置。
  const middle = Math.floor(sorted.length / 2)
  // 奇数取中值，偶数取中间两个值的平均数。
  return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2
}

export function observeTopics(topics: any[], records: any[], field: string) {
  // 为每个主题关联命中文章并汇总指定互动字段。
  return topics.map(topic => {
    // 将主题成员关键词转换为集合以便快速匹配；保留关键词与主题成员有交集的文章；提取有效互动量。
    const members = new Set(topic.member_keywords)
    const articles = records.filter(row => row.keywords.some((word: string) => members.has(word)))
    const values = articles.map(row => numericInteraction(row.source[field])).filter((value): value is number => value !== null)
    // 计算互动总量。
    const total = values.reduce((sum, value) => sum + value, 0)
    // 返回主题统计结果，并保留命中文章明细。
    return { ...topic, articles, content_count: articles.length, valid_count: values.length,
      missing_count: articles.length - values.length, interaction_total: total,
      average_interaction: values.length ? total / values.length : null }
  }).filter(topic => topic.content_count > 0)
}

export function observationPosition(topic: any, countMedian: number | null, interactionMedian: number | null) {
  // 任一中位数或主题均值缺失时无法定位主题象限。
  if (topic.average_interaction === null || countMedian === null || interactionMedian === null) return '互动数据不足'
  // 根据报道量相对中位数判断内容规模；根据平均互动量相对中位数判断互动强度。
  const count = topic.content_count === countMedian ? '报道量处于中位数' : topic.content_count > countMedian ? '报道较多' : '报道较少'
  const interaction = topic.average_interaction === interactionMedian ? '互动处于中位数' : topic.average_interaction > interactionMedian ? '互动较高' : '互动较低'
  return `${count} · ${interaction}`
}

export function representativeTopics(topics: any[], countMedian: number | null, interactionMedian: number | null) {
  // 缺少基准中位数时不生成代表性主题。
  if (countMedian === null || interactionMedian === null) return []
  // 四个桶依次表示低/高报道量与低/高互动组合。
  const buckets: any[][] = [[], [], [], []]
  // 将主题放入对应象限。
  for (const topic of topics) {
    if (topic.average_interaction === null) continue
    const index = (topic.content_count >= countMedian ? 1 : 0) + (topic.average_interaction >= interactionMedian ? 2 : 0)
    buckets[index].push(topic)
  }
  return buckets.flatMap(bucket => bucket.sort((a, b) => b.content_count - a.content_count || b.interaction_total - a.interaction_total).slice(0, 5))
}
