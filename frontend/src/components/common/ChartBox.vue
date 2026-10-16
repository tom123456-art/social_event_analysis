<template>
  <!-- 容器引用用于初始化图表，尺寸类用于统一图表高度。 -->
  <div ref="chartEl" class="chart" :class="size" role="img"></div>
</template>

<script setup lang="ts">
// 通用图表组件：负责创建、更新和销毁 ECharts 图表实例。
import * as echarts from 'echarts'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

// 接收图表配置及可选尺寸规格。
const props = defineProps<{
  // 完整的 ECharts 图表配置。
  option: any
  // 允许小号或默认中号图表布局。
  size?: 'sm' | 'md'
}>()
// 将图表点击参数透传给父页面。
const emit = defineEmits<{ chartClick: [params: any] }>()

// 保存挂载 ECharts 的 DOM 元素引用。
const chartEl = ref<HTMLDivElement>()
// 保存当前图表实例，避免每次配置更新重复创建。
let chart: echarts.ECharts | undefined
// 保存待执行绘制帧，用于合并连续更新。
let renderFrame: number | undefined
// 监听容器尺寸变化，而不只依赖浏览器窗口变化。
let resizeObserver: ResizeObserver | undefined

// 在容器尺寸变化时同步图表画布尺寸。
function resize() {
  // 尚未初始化图表时忽略尺寸事件。
  if (!chart) return
  try {
    // 尺寸更新不使用动画，避免布局拖动时产生延迟。
    chart.resize({ animation: { duration: 0 } })
  } catch (error) {
    // 将第三方渲染错误保留在开发日志，不中断页面生命周期。
    console.error('ECharts resize failed', error)
  }
}

// 创建或更新图表，并重新绑定父页面需要的点击事件。
function render() {
  // DOM 尚未挂载时不能创建画布。
  if (!chartEl.value) return
  try {
    // 仅首次渲染创建实例，后续复用原实例。
    chart ||= echarts.init(chartEl.value)
  } catch (error) {
    console.error('ECharts init failed', error)
    return
  }
  // 新配置覆盖旧的待绘制帧，避免同一帧重复渲染。
  if (renderFrame) window.cancelAnimationFrame(renderFrame)
  // 将图表更新安排到下一个浏览器绘制帧。
  renderFrame = window.requestAnimationFrame(() => {
    // 当前帧已经执行，不再视为待处理帧。
    renderFrame = undefined
    // 组件销毁或实例释放后不继续写入画布。
    if (!chart || !chartEl.value) return
    try {
      // 替换旧配置，避免旧系列残留，并启用延迟更新。
      chart.setOption(props.option, true, true)
      // 先移除旧点击监听，防止反复渲染累积处理函数。
      chart.off('click')
      // 将 ECharts 点击事件转换为组件事件。
      chart.on('click', params => emit('chartClick', params))
    } catch (error) {
      console.error('ECharts render failed', error)
    }
  })
}

// 挂载后创建图表并监听窗口与容器变化。
onMounted(() => {
  // 使用当前配置完成首次绘制。
  render()
  // DOM 存在时观察其尺寸变化。
  if (chartEl.value) {
    // 创建容器尺寸观察器。
    resizeObserver = new ResizeObserver(resize)
    // 只观察当前图表容器。
    resizeObserver.observe(chartEl.value)
  }
  // 兼容浏览器窗口缩放引起的布局调整。
  window.addEventListener('resize', resize)
})
// 深度监听系列数据和样式配置变化。
watch(() => props.option, render, { deep: true })
// 销毁前释放浏览器监听、绘制帧和 ECharts 实例。
onBeforeUnmount(() => {
  // 删除全局窗口监听，避免持有已销毁组件。
  window.removeEventListener('resize', resize)
  // 断开容器尺寸观察。
  resizeObserver?.disconnect()
  // 取消尚未执行的绘制回调。
  if (renderFrame) window.cancelAnimationFrame(renderFrame)
  // 释放图表画布及内部事件资源。
  chart?.dispose()
})
</script>
