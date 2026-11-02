<template>
  <!-- 画布承载概念传播网络；平台链接和分析入口保留独立的键盘交互能力。 -->
  <div ref="surface" class="propagation-scene" :class="{ 'scene-obscured': obscured }">
    <!-- 提供脚本绘制传播网络的画布，不把装饰画布加入辅助阅读内容。 -->
    <canvas ref="canvas" aria-hidden="true" />
    <!-- 此区域集中展示“媒体平台官网”相关内容。 -->
    <nav class="network-platforms" aria-label="媒体平台官网">
      <!-- 根据 (platform, index) in platforms 循环生成条目，模板中的字段读取当前条目数据。 -->
      <a v-for="(platform, index) in platforms" :key="platform.name" class="platform-node" :href="platform.url" target="_blank" rel="noopener noreferrer" :aria-label="`${platform.name}官网（新窗口）`" :style="{ left: `${platform.x * 100}%`, top: `${platform.y * 100}%`, '--node-color': platform.color }" @pointerenter="hovered = index" @pointerleave="hovered = -1" @focus="hovered = index" @blur="hovered = -1">
        <span class="platform-symbol"><span /></span><span class="platform-name">{{ platform.name }}<el-icon><TopRight /></el-icon></span>
      </a>
    </nav>
    <!-- 点击当前按钮时执行 emit('analyze')。 -->
    <button ref="analysis" class="analysis-node" type="button" aria-haspopup="dialog" :aria-expanded="obscured" @click="emit('analyze')"><span class="analysis-symbol"><el-icon><DataAnalysis /></el-icon></span><span>分析</span></button>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { DataAnalysis, TopRight } from '@element-plus/icons-vue'
defineProps<{ obscured?: boolean }>()
// 声明向父组件发送的事件及参数类型。
const emit = defineEmits<{ analyze: [] }>()
// 保存画布节点，以便获取绘图上下文和设置画布分辨率。
const canvas = ref<HTMLCanvasElement>()
// 保存容器节点，通过实际宽高确定画布显示尺寸。
const surface = ref<HTMLElement>()
// 从证据响应中提取分析结果，缺失时使用空对象。
const analysis = ref<HTMLButtonElement>()
// 登录弹层关闭后，父组件可将焦点归还分析入口。
defineExpose({ focusAnalysis: () => analysis.value?.focus() })
// 保存动画暂停状态和当前悬停节点下标，控制粒子推进与节点强调。
const paused = ref(false), hovered = ref(-1)
// 定义可展示或选择的平台集合，供循环和筛选使用。
const platforms = [
  { name: '腾讯', x: .2, y: .3, color: '#b7e9c7', url: 'https://news.qq.com/' },
  { name: '搜狐', x: .5, y: .2, color: '#e8b597', url: 'https://www.sohu.com/' },
  { name: '新浪', x: .8, y: .3, color: '#d2dba9', url: 'https://www.sina.com.cn/' },
  { name: '澎湃', x: .2, y: .74, color: '#99c6ca', url: 'https://www.thepaper.cn/' },
  { name: '网易', x: .5, y: .84, color: '#ed947f', url: 'https://www.163.com/' },
  { name: '微博', x: .8, y: .74, color: '#e4ca86', url: 'https://weibo.com/' }
]
// 保存容器宽高、动画帧编号、累计时钟及上一帧时间，供逐帧绘制使用。
let width = 0, height = 0, frame = 0, clock = 0, previous = 0, visible = true
// 保存尺寸观察器与动态偏好查询，卸载时解除对应监听。
let observer: ResizeObserver | undefined, motion: MediaQueryList | undefined
// 系统偏好减少动画时暂停时间推进，未取得偏好时默认允许动画。
function preference() { paused.value = motion?.matches ?? false }
// 页面可见性变化后重置上一帧时间，防止切回时累计隐藏期间的时间差。
function visibility() { visible = !document.hidden; previous = 0 }

// 页面不可见或用户偏好减少动态效果时，不再推进动画时钟。
function draw(timestamp: number) {
  // 没有画布上下文或容器尚无尺寸时，等待下一帧再尝试绘制。
  const ctx = canvas.value?.getContext('2d')
  if (!ctx || !width || !height) { frame = requestAnimationFrame(draw); return }
  // 只累计可见且未暂停时的经过时间，单帧最多推进五十毫秒避免跳动。
  if (!paused.value && visible && previous) clock += Math.min(timestamp - previous, 50)
  previous = timestamp
  // 清除上一帧，再按容器宽度决定紧凑模式及场景中心点。
  ctx.clearRect(0, 0, width, height)
  const compact = width < 600, cx = width * .5, cy = height * .52
  const scale = Math.min(width / 1100, height / 750)
  // 此处为概念拓扑示意，不代表实际观测到的传播数量或传播路径。
  for (let i = 0; i < platforms.length; i++) {
    // 平台坐标以容器比例保存，绘制时换算为实际像素。
    const p = platforms[i]!, x = width * p.x, y = height * p.y
    const selected = hovered.value < 0 || hovered.value === i
    // 使用交错控制点生成弯曲连线，使不同平台的路径不完全重叠。
    const bx = (cx + x) / 2 + (i % 2 ? 1 : -1) * 18 * scale
    const by = (cy + y) / 2
    ctx.strokeStyle = selected ? '#638d73' : '#2a3e30'; ctx.lineWidth = selected ? 1.2 : .7
    ctx.beginPath(); ctx.moveTo(cx, cy); ctx.quadraticCurveTo(bx, by, x, y); ctx.stroke()
    for (let j = 0; j < 16; j++) {
      // 按固定角度和距离在平台周围布置分支节点，尺寸随紧凑模式缩放。
      const angle = i * 1.7 + j * 2.4
      const distance = (compact ? 27 : 50) + (j % 5) * (compact ? 8 : 18) * Math.min(scale, 1.2)
      const sx = x + Math.cos(angle) * distance, sy = y + Math.sin(angle) * distance
      const branchX = x + Math.cos(angle) * distance * .45, branchY = y + Math.sin(angle) * distance * .45
      ctx.strokeStyle = selected ? '#344f3e' : '#25372c'
      ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(branchX, branchY); ctx.lineTo(sx, sy); ctx.stroke()
      ctx.fillStyle = selected ? '#7ba68b' : '#385340'
      ctx.beginPath(); ctx.arc(sx, sy, j % 4 === 0 ? 2 : 1.2, 0, Math.PI * 2); ctx.fill()
      if (j % 3 === 0) {
        ctx.strokeStyle = '#2b4234'; ctx.beginPath(); ctx.moveTo(sx, sy); ctx.lineTo(sx + Math.cos(angle + 1) * distance * .3, sy + Math.sin(angle + 1) * distance * .3); ctx.stroke()
      }
    }
    for (let j = 0; j < 2; j++) {
      // 让两个粒子沿二次贝塞尔路径从平台移动到中心，循环播放。
      const t = (clock / 5800 + i / 6 + j * .5) % 1
      ctx.fillStyle = selected ? p.color : '#536a5b'
      ctx.beginPath(); ctx.arc((1 - t) ** 2 * x + 2 * (1 - t) * t * bx + t ** 2 * cx, (1 - t) ** 2 * y + 2 * (1 - t) * t * by + t ** 2 * cy, 2.6, 0, Math.PI * 2); ctx.fill()
    }
  }
  ctx.strokeStyle = '#334d3b'; ctx.lineWidth = .6
  ctx.beginPath(); ctx.arc(cx, cy, compact ? 55 : 85, 0, Math.PI * 2); ctx.stroke()
  frame = requestAnimationFrame(draw)
}
// 监听容器尺寸与动态偏好，按像素密度设置画布以保持清晰。
onMounted(() => {
  motion = window.matchMedia('(prefers-reduced-motion: reduce)'); preference(); motion.addEventListener('change', preference)
  document.addEventListener('visibilitychange', visibility)
  observer = new ResizeObserver(() => {
    // 容器变化时同步逻辑尺寸，再按最多两倍像素密度设置画布分辨率。
    const rect = surface.value!.getBoundingClientRect(); width = rect.width; height = rect.height
    const ratio = Math.min(window.devicePixelRatio || 1, 2)
    canvas.value!.width = Math.round(width * ratio); canvas.value!.height = Math.round(height * ratio)
    // 缩放绘图坐标，让后续绘制继续使用 CSS 像素而不是物理像素。
    canvas.value!.getContext('2d')?.setTransform(ratio, 0, 0, ratio, 0, 0)
  })
  observer.observe(surface.value!); frame = requestAnimationFrame(draw)
})
// 离开页面时释放动画帧、尺寸观察器及全局监听。
onBeforeUnmount(() => {
  cancelAnimationFrame(frame); observer?.disconnect(); motion?.removeEventListener('change', preference)
  document.removeEventListener('visibilitychange', visibility)
})
</script>

<style scoped>
.propagation-scene { position: absolute; inset: 0; }
canvas { position: absolute; inset: 0; width: 100%; height: 100%; }
.network-platforms { position: absolute; inset: 0; }
.platform-node, .analysis-node { position: absolute; transform: translate(-50%, -50%); display: flex; flex-direction: column; align-items: center; justify-content: center; width: 90px; height: 88px; gap: 10px; color: #e0eae2; border: none; background: none; cursor: pointer; font: inherit; }
.platform-symbol { display: grid; place-items: center; width: 30px; height: 30px; border: 1px solid var(--node-color); border-radius: 50%; background: #17271ecc; transition: transform .25s, background .25s; }
.platform-symbol span { width: 8px; height: 8px; border-radius: 50%; background: var(--node-color); }
.platform-name { display: flex; gap: 6px; align-items: center; font-size: 15px; }
.platform-name .el-icon { color: var(--node-color); opacity: 0; transition: opacity .2s; font-size: 12px; }
.platform-node:hover .platform-symbol, .platform-node:focus-visible .platform-symbol { transform: scale(1.25); background: #2f4537; }
.platform-node:hover .el-icon, .platform-node:focus-visible .el-icon { opacity: 1; }
.analysis-node { top: 52%; left: 50%; width: 100px; height: 110px; gap: 13px; font-size: 18px; color: #def3e3; }
.analysis-symbol { width: 58px; height: 58px; display: grid; place-items: center; border: 1px solid #a2ccaf; border-radius: 8px; font-size: 27px; background: #203328; transition: background .2s, transform .25s; }
.analysis-node:hover .analysis-symbol { transform: scale(1.07); background: #31553d; }
.platform-node:focus-visible, .analysis-node:focus-visible { outline: 2px solid #b7e9c7; outline-offset: 6px; border-radius: 8px; }
.scene-obscured .analysis-node { visibility: hidden; }
.platform-symbol { position: absolute; top: calc(50% - 15px); left: calc(50% - 15px); }
.platform-name { position: absolute; top: calc(50% + 23px); }
.analysis-symbol { position: absolute; top: calc(50% - 29px); left: calc(50% - 29px); }
.analysis-node > span:last-child { position: absolute; top: calc(50% + 39px); }
@media (max-width: 600px) { .platform-name { font-size: 13px; } .platform-node { width: 74px; height: 74px; gap: 8px; } .analysis-node { font-size: 16px; } .analysis-symbol { width: 48px; height: 48px; font-size: 23px; } }
@media (prefers-reduced-motion: reduce) { * { transition: none !important; } }
@media (max-width: 600px) { .analysis-symbol { top: calc(50% - 24px); left: calc(50% - 24px); } .analysis-node > span:last-child { top: calc(50% + 34px); } }
</style>
