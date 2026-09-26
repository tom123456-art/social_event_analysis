<template>
  <div ref="chartEl" class="chart" :class="size"></div>
</template>

<script setup lang="ts">
// 通用图表组件：负责创建、更新和销毁 ECharts 图表实例。
import * as echarts from 'echarts'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps<{
  option: any
  size?: 'sm' | 'md'
}>()
const emit = defineEmits<{ chartClick: [params: any] }>()

const chartEl = ref<HTMLDivElement>()
let chart: echarts.ECharts | undefined
let renderFrame: number | undefined
let resizeObserver: ResizeObserver | undefined

function resize() {
  if (!chart) return
  chart.resize({ animation: { duration: 0 } })
}

function render() {
  if (!chartEl.value) return
  chart ||= echarts.init(chartEl.value)
  if (renderFrame) window.cancelAnimationFrame(renderFrame)
  renderFrame = window.requestAnimationFrame(() => {
    renderFrame = undefined
    if (!chart || !chartEl.value) return
    chart.setOption(props.option, true, true)
    chart.off('click')
    chart.on('click', params => emit('chartClick', params))
  })
}

onMounted(() => {
  render()
  if (chartEl.value) {
    resizeObserver = new ResizeObserver(resize)
    resizeObserver.observe(chartEl.value)
  }
  window.addEventListener('resize', resize)
})
watch(() => props.option, render, { deep: true })
onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  resizeObserver?.disconnect()
  if (renderFrame) window.cancelAnimationFrame(renderFrame)
  chart?.dispose()
})
</script>
