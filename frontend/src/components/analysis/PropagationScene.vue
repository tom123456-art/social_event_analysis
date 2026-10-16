<template>
  <div ref="surface" class="propagation-scene" :class="{ 'scene-obscured': obscured }">
    <canvas ref="canvas" aria-hidden="true" />
    <nav class="network-platforms" aria-label="媒体平台官网">
      <a v-for="(platform, index) in platforms" :key="platform.name" class="platform-node" :href="platform.url" target="_blank" rel="noopener noreferrer" :aria-label="`${platform.name}官网（新窗口）`" :style="{ left: `${platform.x * 100}%`, top: `${platform.y * 100}%`, '--node-color': platform.color }" @pointerenter="hovered = index" @pointerleave="hovered = -1" @focus="hovered = index" @blur="hovered = -1">
        <span class="platform-symbol"><span /></span><span class="platform-name">{{ platform.name }}<el-icon><TopRight /></el-icon></span>
      </a>
    </nav>
    <button ref="analysis" class="analysis-node" type="button" aria-haspopup="dialog" :aria-expanded="obscured" @click="emit('analyze')"><span class="analysis-symbol"><el-icon><DataAnalysis /></el-icon></span><span>分析</span></button>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { DataAnalysis, TopRight } from '@element-plus/icons-vue'
defineProps<{ obscured?: boolean }>()
const emit = defineEmits<{ analyze: [] }>()
const canvas = ref<HTMLCanvasElement>()
const surface = ref<HTMLElement>()
const analysis = ref<HTMLButtonElement>()
defineExpose({ focusAnalysis: () => analysis.value?.focus() })
const paused = ref(false), hovered = ref(-1)
const platforms = [
  { name: '腾讯', x: .2, y: .3, color: '#b7e9c7', url: 'https://news.qq.com/' },
  { name: '搜狐', x: .5, y: .2, color: '#e8b597', url: 'https://www.sohu.com/' },
  { name: '新浪', x: .8, y: .3, color: '#d2dba9', url: 'https://www.sina.com.cn/' },
  { name: '澎湃', x: .2, y: .74, color: '#99c6ca', url: 'https://www.thepaper.cn/' },
  { name: '网易', x: .5, y: .84, color: '#ed947f', url: 'https://www.163.com/' },
  { name: '微博', x: .8, y: .74, color: '#e4ca86', url: 'https://weibo.com/' }
]
let width = 0, height = 0, frame = 0, clock = 0, previous = 0, visible = true
let observer: ResizeObserver | undefined, motion: MediaQueryList | undefined
function preference() { paused.value = motion?.matches ?? false }
function visibility() { visible = !document.hidden; previous = 0 }

function draw(timestamp: number) {
  const ctx = canvas.value?.getContext('2d')
  if (!ctx || !width || !height) { frame = requestAnimationFrame(draw); return }
  if (!paused.value && visible && previous) clock += Math.min(timestamp - previous, 50)
  previous = timestamp
  ctx.clearRect(0, 0, width, height)
  const compact = width < 600, cx = width * .5, cy = height * .52
  const scale = Math.min(width / 1100, height / 750)
  // This is a conceptual topology, not a chart of measured propagation counts.
  for (let i = 0; i < platforms.length; i++) {
    const p = platforms[i]!, x = width * p.x, y = height * p.y
    const selected = hovered.value < 0 || hovered.value === i
    const bx = (cx + x) / 2 + (i % 2 ? 1 : -1) * 18 * scale
    const by = (cy + y) / 2
    ctx.strokeStyle = selected ? '#638d73' : '#2a3e30'; ctx.lineWidth = selected ? 1.2 : .7
    ctx.beginPath(); ctx.moveTo(cx, cy); ctx.quadraticCurveTo(bx, by, x, y); ctx.stroke()
    for (let j = 0; j < 16; j++) {
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
      const t = (clock / 5800 + i / 6 + j * .5) % 1
      ctx.fillStyle = selected ? p.color : '#536a5b'
      ctx.beginPath(); ctx.arc((1 - t) ** 2 * x + 2 * (1 - t) * t * bx + t ** 2 * cx, (1 - t) ** 2 * y + 2 * (1 - t) * t * by + t ** 2 * cy, 2.6, 0, Math.PI * 2); ctx.fill()
    }
  }
  ctx.strokeStyle = '#334d3b'; ctx.lineWidth = .6
  ctx.beginPath(); ctx.arc(cx, cy, compact ? 55 : 85, 0, Math.PI * 2); ctx.stroke()
  frame = requestAnimationFrame(draw)
}
onMounted(() => {
  motion = window.matchMedia('(prefers-reduced-motion: reduce)'); preference(); motion.addEventListener('change', preference)
  document.addEventListener('visibilitychange', visibility)
  observer = new ResizeObserver(() => {
    const rect = surface.value!.getBoundingClientRect(); width = rect.width; height = rect.height
    const ratio = Math.min(window.devicePixelRatio || 1, 2)
    canvas.value!.width = Math.round(width * ratio); canvas.value!.height = Math.round(height * ratio)
    canvas.value!.getContext('2d')?.setTransform(ratio, 0, 0, ratio, 0, 0)
  })
  observer.observe(surface.value!); frame = requestAnimationFrame(draw)
})
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
