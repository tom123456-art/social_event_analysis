// 分析页面共用展示格式，不在格式化过程中修改原始数据或统计口径。
// 热度统一使用万单位；小于一万时沿用整数或一位小数口径。
export function numberText(value: unknown): string {
  // 先转成数字，空值按零处理，随后按是否达到一万选择展示单位。
  const num = Number(value || 0)
  return num >= 10000 ? `${(num / 10000).toFixed(1)}万` : num.toFixed(num % 1 ? 1 : 0)
}

// 只格式化展示，不转换时区，避免改变后端业务时间。
export function formatTime(value: unknown): string {
  // 把 ISO 时间分隔符换为空格并截取到分钟，无值时显示短横线。
  return value ? String(value).replace('T', ' ').slice(0, 16) : '-'
}

// 零是有效指标；只有空值或非数值才显示占位符。
export function fixed(value: unknown): string {
  // 逐项排除空值和非有限数字，合法的零仍格式化为一位小数。
  return value === null || value === undefined || value === '' || !Number.isFinite(Number(value))
    ? '-' : Number(value).toFixed(1)
}
