export const INTERACTION_METRICS = [
  { field: 'like_count', label: '点赞' },
  { field: 'comment_count', label: '评论' },
  { field: 'repost_count', label: '转发' },
  { field: 'favorite_count', label: '收藏' }
]

// Standalone country entities are context, not a specific news topic.
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
  return COUNTRY_ENTITIES.has(keyword.trim())
}

export function numericInteraction(value: unknown): number | null {
  if (value === null || value === undefined || String(value).trim() === '') return null
  const number = Number(value)
  return Number.isFinite(number) && number >= 0 ? number : null
}

export function topicMedian(values: number[]): number | null {
  const sorted = values.filter(Number.isFinite).sort((a, b) => a - b)
  if (!sorted.length) return null
  const middle = Math.floor(sorted.length / 2)
  return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2
}

export function observeTopics(topics: any[], records: any[], field: string) {
  return topics.map(topic => {
    const members = new Set(topic.member_keywords)
    const articles = records.filter(row => row.keywords.some((word: string) => members.has(word)))
    const values = articles.map(row => numericInteraction(row.source[field])).filter((value): value is number => value !== null)
    const total = values.reduce((sum, value) => sum + value, 0)
    return { ...topic, articles, content_count: articles.length, valid_count: values.length,
      missing_count: articles.length - values.length, interaction_total: total,
      average_interaction: values.length ? total / values.length : null }
  }).filter(topic => topic.content_count > 0)
}

export function observationPosition(topic: any, countMedian: number | null, interactionMedian: number | null) {
  if (topic.average_interaction === null || countMedian === null || interactionMedian === null) return '互动数据不足'
  const count = topic.content_count === countMedian ? '报道量处于中位数' : topic.content_count > countMedian ? '报道较多' : '报道较少'
  const interaction = topic.average_interaction === interactionMedian ? '互动处于中位数' : topic.average_interaction > interactionMedian ? '互动较高' : '互动较低'
  return `${count} · ${interaction}`
}

export function representativeTopics(topics: any[], countMedian: number | null, interactionMedian: number | null) {
  if (countMedian === null || interactionMedian === null) return []
  const buckets: any[][] = [[], [], [], []]
  for (const topic of topics) {
    if (topic.average_interaction === null) continue
    const index = (topic.content_count >= countMedian ? 1 : 0) + (topic.average_interaction >= interactionMedian ? 2 : 0)
    buckets[index].push(topic)
  }
  return buckets.flatMap(bucket => bucket.sort((a, b) => b.content_count - a.content_count || b.interaction_total - a.interaction_total).slice(0, 5))
}
