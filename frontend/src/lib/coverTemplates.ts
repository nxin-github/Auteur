// coverTemplates —— 4 个封面模板的 canvas 渲染函数。
// 每个模板都接收 (ctx, w, h, brand, design, heroImg),内部用 w/h 比例自适应 3:4 / 4:3 / 16:9。
// 预览即 canvas,导出 = canvas.toBlob('image/png') —— 像素一致,无第三方依赖。
//
// 设计原则:
// 1. 文字必须自动换行(中文不能撑出画面);
// 2. hero 图按 cover 模式裁切(填满目标区域,不变形);
// 3. 颜色全部从 brand 取,改主色三比例同步变;
// 4. logo 在底部对齐,统一 60px 高(自适应小尺寸缩放)。
import type { BrandIdentity, CoverDesign } from '../types'

export type CoverRenderFn = (
  ctx: CanvasRenderingContext2D,
  w: number,
  h: number,
  brand: BrandIdentity,
  design: CoverDesign,
  heroImg: HTMLImageElement | null,
  logoImg: HTMLImageElement | null,
) => void

export interface CoverTemplateMeta {
  id: string
  name: string
  description: string
  render: CoverRenderFn
}

// 中文文字断行 —— canvas measureText 在中文上每个字宽差不多,直接按字宽贪心断行。
function wrapText(
  ctx: CanvasRenderingContext2D,
  text: string,
  maxWidth: number,
): string[] {
  if (!text) return []
  const lines: string[] = []
  let cur = ''
  for (const ch of Array.from(text)) {
    if (ch === '\n') {
      lines.push(cur)
      cur = ''
      continue
    }
    const next = cur + ch
    if (ctx.measureText(next).width > maxWidth && cur) {
      lines.push(cur)
      cur = ch
    } else {
      cur = next
    }
  }
  if (cur) lines.push(cur)
  return lines
}

// hero 图 cover 模式裁切 —— 像 CSS background-size: cover,填满目标区域,中心裁切,不变形。
function drawCover(
  ctx: CanvasRenderingContext2D,
  img: HTMLImageElement,
  x: number, y: number, w: number, h: number,
) {
  const imgRatio = img.naturalWidth / img.naturalHeight
  const boxRatio = w / h
  let sx = 0, sy = 0, sw = img.naturalWidth, sh = img.naturalHeight
  if (imgRatio > boxRatio) {
    // 图比框宽,左右裁
    sw = img.naturalHeight * boxRatio
    sx = (img.naturalWidth - sw) / 2
  } else {
    sh = img.naturalWidth / boxRatio
    sy = (img.naturalHeight - sh) / 2
  }
  ctx.drawImage(img, sx, sy, sw, sh, x, y, w, h)
}

// 占位 placeholder 矩形(没传 hero 图时)
function drawHeroPlaceholder(
  ctx: CanvasRenderingContext2D,
  x: number, y: number, w: number, h: number,
  brand: BrandIdentity,
) {
  // 渐变填充,左上 primary → 右下 secondary
  const grad = ctx.createLinearGradient(x, y, x + w, y + h)
  grad.addColorStop(0, brand.primaryColor)
  grad.addColorStop(1, brand.secondaryColor)
  ctx.fillStyle = grad
  ctx.fillRect(x, y, w, h)
  ctx.fillStyle = 'rgba(255,255,255,0.35)'
  ctx.font = `${Math.round(Math.min(w, h) * 0.06)}px ${brand.titleFont}`
  ctx.textAlign = 'center'
  ctx.textBaseline = 'middle'
  ctx.fillText('封面主图占位', x + w / 2, y + h / 2)
}

// 画 logo + 频道名 角标。logo 圆形 clip + 古铜金描边;
// 文字第一行 brandName,有 authorName 时跟在后面小字 @xxx。
function drawLogoAndAuthor(
  ctx: CanvasRenderingContext2D,
  x: number, y: number, h: number,
  brand: BrandIdentity,
  logoImg: HTMLImageElement | null,
  textColor: string,
) {
  let cursorX = x
  if (logoImg) {
    const size = h
    const cx = cursorX + size / 2
    const cy = y + size / 2
    const r = size / 2

    // 圆形 clip + 同色 backing(防止透明边角与封面色撞)
    ctx.save()
    ctx.beginPath()
    ctx.arc(cx, cy, r, 0, Math.PI * 2)
    ctx.closePath()
    ctx.clip()
    ctx.fillStyle = brand.bgColor
    ctx.fillRect(cursorX, y, size, size)
    // 用 max(scaleX, scaleY) 中心裁切,保证非方图也填满圆
    const scale = Math.max(size / logoImg.naturalWidth, size / logoImg.naturalHeight)
    const dw = logoImg.naturalWidth * scale
    const dh = logoImg.naturalHeight * scale
    ctx.drawImage(logoImg, cursorX + (size - dw) / 2, y + (size - dh) / 2, dw, dh)
    ctx.restore()

    // 描边(古铜金,跟着 brand.secondaryColor 走)
    ctx.strokeStyle = brand.secondaryColor
    ctx.lineWidth = Math.max(1.5, h * 0.04)
    ctx.beginPath()
    ctx.arc(cx, cy, r - ctx.lineWidth / 2, 0, Math.PI * 2)
    ctx.stroke()

    cursorX += size + h * 0.4
  }

  const primaryText = brand.brandName || ''
  const secondaryText = brand.authorName || ''
  if (!primaryText && !secondaryText) return
  ctx.textAlign = 'left'
  ctx.textBaseline = 'middle'
  ctx.fillStyle = textColor
  if (primaryText) {
    ctx.font = `${Math.round(h * 0.55)}px ${brand.titleFont}`
    ctx.fillText(primaryText, cursorX, y + h / 2)
    cursorX += ctx.measureText(primaryText).width
  }
  if (secondaryText) {
    const prefix = primaryText ? '  @' : '@'
    ctx.globalAlpha = 0.7
    ctx.font = `${Math.round(h * 0.42)}px ${brand.titleFont}`
    ctx.fillText(prefix + secondaryText, cursorX, y + h / 2)
    ctx.globalAlpha = 1
  }
}

// 模板 1:bottom-caption —— hero 填满 + 底部渐变蒙层 + 大字标题压图
const renderBottomCaption: CoverRenderFn = (ctx, w, h, brand, design, heroImg, logoImg) => {
  // 底色兜底
  ctx.fillStyle = brand.bgColor
  ctx.fillRect(0, 0, w, h)
  // 主图填满
  if (heroImg) drawCover(ctx, heroImg, 0, 0, w, h)
  else drawHeroPlaceholder(ctx, 0, 0, w, h, brand)

  // 底部渐变蒙层 —— 透明 → 主色,占 55% 高度,保证标题压在画面深色区
  const overlayH = h * 0.55
  const grad = ctx.createLinearGradient(0, h - overlayH, 0, h)
  grad.addColorStop(0, 'rgba(0,0,0,0)')
  grad.addColorStop(0.5, hexWithAlpha(brand.primaryColor, 0.55))
  grad.addColorStop(1, hexWithAlpha(brand.primaryColor, 0.92))
  ctx.fillStyle = grad
  ctx.fillRect(0, h - overlayH, w, overlayH)

  // 标题:占宽度 86%,自动换行
  const padX = w * 0.07
  const maxTextW = w - padX * 2
  // 标题字号 —— 3:4 用 w*0.095,4:3 / 16:9 用 h*0.13,保证视觉一致
  const ratio = w / h
  const titleSize = ratio < 1 ? Math.round(w * 0.095) : Math.round(h * 0.13)
  ctx.font = `bold ${titleSize}px ${brand.titleFont}`
  ctx.fillStyle = '#FFFFFF'
  ctx.textAlign = 'left'
  ctx.textBaseline = 'alphabetic'
  const titleLines = wrapText(ctx, design.titleText || '在这里写一个有钩子的标题', maxTextW)
  // 限最多 3 行,超出截断加省略号
  const cap = titleLines.slice(0, 3)
  if (titleLines.length > 3) cap[2] = cap[2].slice(0, -1) + '…'

  // logo 角标占位约 h*0.18 —— 标题贴底部上方
  const cornerH = Math.round(h * 0.06)
  const lineGap = titleSize * 0.18
  const titleBlockH = cap.length * (titleSize + lineGap) - lineGap
  const titleEndY = h - cornerH * 2 - h * 0.04
  const titleStartY = titleEndY - titleBlockH

  cap.forEach((line, i) => {
    const y = titleStartY + i * (titleSize + lineGap) + titleSize
    ctx.fillText(line, padX, y)
  })

  // 角标:logo + author
  drawLogoAndAuthor(ctx, padX, h - cornerH - h * 0.03, cornerH, brand, logoImg, '#FFFFFF')
}

// 模板 2:split-vertical —— 上 60% hero,下 40% 纯色块写标题
const renderSplitVertical: CoverRenderFn = (ctx, w, h, brand, design, heroImg, logoImg) => {
  const heroH = Math.round(h * 0.6)
  const captionH = h - heroH

  // 上:hero
  if (heroImg) drawCover(ctx, heroImg, 0, 0, w, heroH)
  else drawHeroPlaceholder(ctx, 0, 0, w, heroH, brand)

  // 下:色块标题区(底色 = brand.bgColor)
  ctx.fillStyle = brand.bgColor
  ctx.fillRect(0, heroH, w, captionH)

  // 顶端横线分隔(主色)
  ctx.fillStyle = brand.primaryColor
  ctx.fillRect(0, heroH, w, Math.max(4, h * 0.005))

  const padX = w * 0.07
  const maxTextW = w - padX * 2
  const ratio = w / h
  const titleSize = ratio > 1.5
    ? Math.round(captionH * 0.32)   // 16:9 caption 矮,字号靠 captionH
    : Math.round(captionH * 0.26)
  ctx.font = `bold ${titleSize}px ${brand.titleFont}`
  ctx.fillStyle = brand.primaryColor
  ctx.textAlign = 'left'
  ctx.textBaseline = 'alphabetic'
  const titleLines = wrapText(ctx, design.titleText || '主标题', maxTextW)
  const cap = titleLines.slice(0, 2)
  if (titleLines.length > 2) cap[1] = cap[1].slice(0, -1) + '…'
  const lineGap = titleSize * 0.2
  cap.forEach((line, i) => {
    ctx.fillText(line, padX, heroH + captionH * 0.32 + i * (titleSize + lineGap))
  })

  // logo + author 在右下角(轻量)
  const cornerH = Math.round(h * 0.05)
  ctx.save()
  ctx.translate(0, 0)
  drawLogoAndAuthor(ctx, padX, h - cornerH - h * 0.025, cornerH, brand,
    logoImg, brand.primaryColor)
  ctx.restore()
}

// 模板 3:diagonal —— hero 图右倾 5°,左侧粗体竖排标题
const renderDiagonal: CoverRenderFn = (ctx, w, h, brand, design, heroImg, logoImg) => {
  // 底色
  ctx.fillStyle = brand.bgColor
  ctx.fillRect(0, 0, w, h)

  // hero 占右 55%,稍微倾斜
  const heroX = w * 0.42
  const heroY = h * 0.08
  const heroW = w * 0.55
  const heroH = h * 0.84

  ctx.save()
  ctx.translate(heroX + heroW / 2, heroY + heroH / 2)
  ctx.rotate((-3 * Math.PI) / 180)
  ctx.translate(-(heroX + heroW / 2), -(heroY + heroH / 2))
  // 阴影
  ctx.shadowColor = 'rgba(0,0,0,0.25)'
  ctx.shadowBlur = w * 0.02
  ctx.shadowOffsetX = w * 0.005
  ctx.shadowOffsetY = w * 0.008
  if (heroImg) drawCover(ctx, heroImg, heroX, heroY, heroW, heroH)
  else drawHeroPlaceholder(ctx, heroX, heroY, heroW, heroH, brand)
  ctx.restore()

  // 左侧:大色块 + 粗体标题
  ctx.fillStyle = hexWithAlpha(brand.primaryColor, 0.92)
  // 切角,左上到右下的不规则块
  ctx.beginPath()
  ctx.moveTo(0, 0)
  ctx.lineTo(w * 0.46, 0)
  ctx.lineTo(w * 0.42, h)
  ctx.lineTo(0, h)
  ctx.closePath()
  ctx.fill()

  // 标题
  const padX = w * 0.04
  const maxTextW = w * 0.36
  const ratio = w / h
  const titleSize = ratio < 1 ? Math.round(w * 0.085) : Math.round(h * 0.11)
  ctx.font = `bold ${titleSize}px ${brand.titleFont}`
  ctx.fillStyle = '#FFFFFF'
  ctx.textAlign = 'left'
  ctx.textBaseline = 'alphabetic'
  const titleLines = wrapText(ctx, design.titleText || '示例标题', maxTextW)
  const cap = titleLines.slice(0, 4)
  if (titleLines.length > 4) cap[3] = cap[3].slice(0, -1) + '…'
  const lineGap = titleSize * 0.18
  const blockH = cap.length * (titleSize + lineGap) - lineGap
  const startY = h * 0.5 - blockH / 2 + titleSize
  cap.forEach((line, i) => {
    ctx.fillText(line, padX, startY + i * (titleSize + lineGap))
  })

  // 角标:logo + author 在左下
  const cornerH = Math.round(h * 0.05)
  drawLogoAndAuthor(ctx, padX, h - cornerH - h * 0.03, cornerH, brand, logoImg, '#FFFFFF')
}

// 模板 4:minimal —— 大字标题居中,hero 缩小成右下角圆角小图
const renderMinimal: CoverRenderFn = (ctx, w, h, brand, design, heroImg, logoImg) => {
  // 底色
  ctx.fillStyle = brand.bgColor
  ctx.fillRect(0, 0, w, h)

  // 顶部装饰条(品牌色)
  ctx.fillStyle = brand.primaryColor
  ctx.fillRect(0, 0, w, Math.max(6, h * 0.008))

  // 标题居中,占宽 80%
  const padX = w * 0.1
  const maxTextW = w - padX * 2
  const ratio = w / h
  const titleSize = ratio < 1 ? Math.round(w * 0.11) : Math.round(h * 0.14)
  ctx.font = `bold ${titleSize}px ${brand.titleFont}`
  ctx.fillStyle = brand.primaryColor
  ctx.textAlign = 'center'
  ctx.textBaseline = 'alphabetic'
  const titleLines = wrapText(ctx, design.titleText || '极简标题', maxTextW)
  const cap = titleLines.slice(0, 3)
  if (titleLines.length > 3) cap[2] = cap[2].slice(0, -1) + '…'
  const lineGap = titleSize * 0.25
  const blockH = cap.length * (titleSize + lineGap) - lineGap
  // 整体偏上 0.4(给 hero 角图留底空间)
  const startY = h * 0.4 - blockH / 2 + titleSize
  cap.forEach((line, i) => {
    ctx.fillText(line, w / 2, startY + i * (titleSize + lineGap))
  })

  // 右下角圆角小 hero
  const heroSide = Math.min(w, h) * 0.32
  const heroX = w - heroSide - w * 0.06
  const heroY = h - heroSide - h * 0.1
  ctx.save()
  const r = heroSide * 0.1
  roundedRectPath(ctx, heroX, heroY, heroSide, heroSide, r)
  ctx.clip()
  if (heroImg) drawCover(ctx, heroImg, heroX, heroY, heroSide, heroSide)
  else drawHeroPlaceholder(ctx, heroX, heroY, heroSide, heroSide, brand)
  ctx.restore()
  // 边框
  ctx.strokeStyle = brand.primaryColor
  ctx.lineWidth = Math.max(2, w * 0.003)
  roundedRectPath(ctx, heroX, heroY, heroSide, heroSide, r)
  ctx.stroke()

  // 左下角:logo + author
  const cornerH = Math.round(h * 0.05)
  drawLogoAndAuthor(ctx, w * 0.06, h - cornerH - h * 0.06, cornerH, brand, logoImg, brand.primaryColor)
}

// 模板 5:lifecopy-classic —— 录取通知书风格,红底圆角 hero(保留 hero 原色) + 底部主副标题
// 标题用 "\n" 分隔主副:第一行小字提示语,第二行大字主标题。无换行时整段当大字主标题。
// 参考"今天体验的人生"系列爆款封面
const renderLifecopyClassic: CoverRenderFn = (ctx, w, h, brand, design, heroImg, _logoImg) => {
  // 整体黑色外背景(写实质感)
  ctx.fillStyle = '#1a1a1a'
  ctx.fillRect(0, 0, w, h)

  // 红色圆角 hero 框,占上 70% 高度
  const padX = w * 0.04
  const heroY = h * 0.04
  const heroW = w - padX * 2
  const heroH = h * 0.66
  const radius = Math.min(heroW, heroH) * 0.04

  // 红底(hero 没图时兜底成红框,与录取通知书喜感一致;有 hero 图时被覆盖,不染色)
  ctx.save()
  roundedRectPath(ctx, padX, heroY, heroW, heroH, radius)
  ctx.fillStyle = '#8B1A1A'
  ctx.fill()
  ctx.clip()
  if (heroImg) drawCover(ctx, heroImg, padX, heroY, heroW, heroH)
  ctx.restore()

  // 标题区:占下 24%,主副两行黄底黑描边
  const titleAreaY = heroY + heroH + h * 0.04
  const titleAreaH = h - titleAreaY - h * 0.04

  // 拆主副:design.titleText 含 \n 时,第一行小字主标题,第二行大字副标题
  const rawTitle = design.titleText || '今天体验的人生:示例'
  const lines = rawTitle.split('\n').map((s) => s.trim()).filter(Boolean)
  const main = lines.length >= 2 ? lines[0] : null
  const sub = lines.length >= 2 ? lines.slice(1).join(' ') : lines[0] || ''

  const titleMaxW = w * 0.92
  const ratio = w / h
  // 大字(副标题/主标题):占 13% 高
  const subSize = ratio < 1 ? Math.round(w * 0.105) : Math.round(h * 0.13)
  // 小字(主标题/提示语):占大字的 60%
  const mainSize = Math.round(subSize * 0.6)

  ctx.textAlign = 'center'
  ctx.textBaseline = 'alphabetic'

  // 测量布局
  const lineGap = subSize * 0.18
  let totalH = subSize
  if (main) totalH += mainSize + lineGap

  let yCursor = titleAreaY + (titleAreaH - totalH) / 2

  // 第一行:小字主标题(可选) — 白字直接 fill,深色背景下不需要描边
  if (main) {
    yCursor += mainSize
    ctx.font = `bold ${mainSize}px ${brand.titleFont}`
    drawWhitePlain(ctx, main, w / 2, yCursor, mainSize, titleMaxW)
    yCursor += lineGap
  }

  // 第二行(或唯一一行):大字 — 黄色 + 黑描边(保留视觉重心)
  yCursor += subSize - (main ? 0 : 0)
  ctx.font = `900 ${subSize}px ${brand.titleFont}`
  drawStrokedYellow(ctx, sub, w / 2, yCursor, subSize, titleMaxW)
}

// 大字主标题:黄色填充 + 黑色粗描边(WordArt 风,平台压缩仍醒目)
function drawStrokedYellow(
  ctx: CanvasRenderingContext2D,
  text: string,
  cx: number, y: number,
  fontSize: number,
  maxW: number,
) {
  if (!text) return
  let line = text
  while (ctx.measureText(line).width > maxW && line.length > 1) {
    line = line.slice(0, -1)
  }
  if (line !== text) line = line.slice(0, -1) + '…'

  const strokeWidth = Math.max(4, fontSize * 0.08)
  ctx.strokeStyle = '#000000'
  ctx.lineWidth = strokeWidth
  ctx.lineJoin = 'round'
  ctx.miterLimit = 2
  ctx.strokeText(line, cx, y)
  ctx.fillStyle = '#FFD93D'
  ctx.fillText(line, cx, y)
}

// 小字主标题:纯白填充,标题区是 #1a1a1a 深底,白字本身已经够醒目
function drawWhitePlain(
  ctx: CanvasRenderingContext2D,
  text: string,
  cx: number, y: number,
  _fontSize: number,
  maxW: number,
) {
  if (!text) return
  let line = text
  while (ctx.measureText(line).width > maxW && line.length > 1) {
    line = line.slice(0, -1)
  }
  if (line !== text) line = line.slice(0, -1) + '…'
  ctx.fillStyle = '#FFFFFF'
  ctx.fillText(line, cx, y)
}


function roundedRectPath(
  ctx: CanvasRenderingContext2D,
  x: number, y: number, w: number, h: number, r: number,
) {
  ctx.beginPath()
  ctx.moveTo(x + r, y)
  ctx.lineTo(x + w - r, y)
  ctx.arcTo(x + w, y, x + w, y + r, r)
  ctx.lineTo(x + w, y + h - r)
  ctx.arcTo(x + w, y + h, x + w - r, y + h, r)
  ctx.lineTo(x + r, y + h)
  ctx.arcTo(x, y + h, x, y + h - r, r)
  ctx.lineTo(x, y + r)
  ctx.arcTo(x, y, x + r, y, r)
  ctx.closePath()
}

// hex(#RRGGBB)+ alpha → rgba 字符串
function hexWithAlpha(hex: string, alpha: number): string {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex)
  if (!m) return `rgba(0,0,0,${alpha})`
  const v = m[1]
  const r = parseInt(v.slice(0, 2), 16)
  const g = parseInt(v.slice(2, 4), 16)
  const b = parseInt(v.slice(4, 6), 16)
  return `rgba(${r},${g},${b},${alpha})`
}

export const COVER_TEMPLATES: CoverTemplateMeta[] = [
  {
    id: 'bottom-caption',
    name: '底部标题压图',
    description: 'hero 图填满,底部渐变蒙层 + 大字白色标题。抖音/小红书最常见的爆款打法。',
    render: renderBottomCaption,
  },
  {
    id: 'split-vertical',
    name: '上下分割',
    description: '上 60% 主图,下 40% 米色标题块。报刊/纪录片观感,4:3 与 16:9 都适合。',
    render: renderSplitVertical,
  },
  {
    id: 'diagonal',
    name: '对角强对比',
    description: '左侧深色块写标题,右侧 hero 图轻微倾斜带阴影。冲击力强,悬案题材常用。',
    render: renderDiagonal,
  },
  {
    id: 'minimal',
    name: '极简标题',
    description: '居中大字标题,hero 缩成右下角圆角小图。文字优先,B站知识区风格。',
    render: renderMinimal,
  },
  {
    id: 'lifecopy-classic',
    name: '录取通知书',
    description: '红色圆角 hero + 底部黄底黑描边大字加粗标题。"今天体验的人生"系列爆款打法,适合 lifecopy 漫画风。',
    render: renderLifecopyClassic,
  },
]

export function getTemplate(id: string): CoverTemplateMeta {
  return COVER_TEMPLATES.find((t) => t.id === id) ?? COVER_TEMPLATES[0]
}
