// 热度统一使用万单位；小于一万时沿用整数或一位小数口径。
export function numberText(value: unknown): string {
  const num = Number(value || 0)
  return num >= 10000 ? `${(num / 10000).toFixed(1)}万` : num.toFixed(num % 1 ? 1 : 0)
}

// 只格式化展示，不转换时区，避免改变后端业务时间。
export function formatTime(value: unknown): string {
  return value ? String(value).replace('T', ' ').slice(0, 16) : '-'
}

// 零是有效指标；只有空值或非数值才显示占位符。
export function fixed(value: unknown): string {
  return value === null || value === undefined || value === '' || !Number.isFinite(Number(value))
    ? '-' : Number(value).toFixed(1)
}
