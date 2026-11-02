<template>
  <!-- 循环展示指标名称、数值和可选说明，保持各分析页的指标结构一致。 -->
  <div class="grid g4">
    <!-- 根据 (item, index) in items 循环生成条目，模板中的字段读取当前条目数据。 -->
    <div v-for="(item, index) in items" :key="item.label" class="card metric">
      <div class="mi" :style="{ background: colors[index % colors.length] }">{{ String.fromCharCode(65 + index) }}</div>
      <div>
        <label>{{ item.label }}</label>
        <strong>{{ item.value }}</strong>
        <small>{{ item.sub || '来自 Spark ADS' }}</small>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
// 指标卡组件：以统一样式展示关键统计数值。
defineProps<{ items: Array<{ label: string; value: string | number; sub?: string }> }>()
// 按指标下标循环选择图标配色。
const colors = ['#2364e8', '#18a7d7', '#18a058', '#d88400', '#d83a3a', '#6866d9']
</script>
