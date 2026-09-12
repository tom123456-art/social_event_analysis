<template>
  <div ref="chartEl" class="chart" :class="size"></div>
</template>

<script setup lang="ts">
import * as echarts from 'echarts'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps<{
  option: any
  size?: 'sm' | 'md'
}>()

const chartEl = ref<HTMLDivElement>()
let chart: echarts.ECharts | undefined
let renderFrame: number | undefined

function render() {
  if (!chartEl.value) return
  chart ||= echarts.init(chartEl.value)
  if (renderFrame) window.cancelAnimationFrame(renderFrame)
  renderFrame = window.requestAnimationFrame(() => {
    renderFrame = undefined
    if (!chart || !chartEl.value) return
    chart.setOption(props.option, true, true)
  })
}

onMounted(() => {
  render()
  window.addEventListener('resize', render)
})
watch(() => props.option, render, { deep: true })
onBeforeUnmount(() => {
  window.removeEventListener('resize', render)
  if (renderFrame) window.cancelAnimationFrame(renderFrame)
  chart?.dispose()
})
</script>
