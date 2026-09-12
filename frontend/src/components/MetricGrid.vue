<template>
  <div class="grid g4">
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
defineProps<{ items: Array<{ label: string; value: string | number; sub?: string }> }>()
const colors = ['#2364e8', '#18a7d7', '#18a058', '#d88400', '#d83a3a', '#6866d9']
</script>
