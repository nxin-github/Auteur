package com.auteur.cover;

import com.auteur.storage.TosStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * 服务端封面渲染器。把前端 lib/coverTemplates.ts 的 4 个 canvas 渲染函数 1:1 翻成 Java2D。
 * 4 模板:bottom-caption / split-vertical / diagonal / minimal。
 *
 * canvas → Java2D 关键差异:
 *   - textBaseline=middle 没原生支持,手算 y + ascent - (ascent+descent)/2
 *   - 渐变用 LinearGradientPaint(stops) / GradientPaint(两段)
 *   - 圆角裁切走 setClip(RoundRectangle2D)
 *   - shadow 直接省略
 */
@Slf4j
@Component
public class Java2DCoverRenderer {

    private final CoverProperties props;
    private final TosStorageService tos;
    private final com.auteur.runtimeconfig.RuntimeConfig runtimeConfig;

    public Java2DCoverRenderer(CoverProperties props, TosStorageService tos,
                               com.auteur.runtimeconfig.RuntimeConfig runtimeConfig) {
        this.props = props;
        this.tos = tos;
        this.runtimeConfig = runtimeConfig;
    }

    public record RenderRequest(
            Long scriptId,
            String ratio,
            int width,
            int height,
            String templateId,
            String titleText,
            Path heroImagePath,
            BrandIdentity brand
    ) {}

    public record RenderResult(Path file, long sizeBytes, String url) {}

    public RenderResult render(RenderRequest req) throws IOException {
        BufferedImage img = new BufferedImage(req.width(), req.height(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

            BufferedImage hero = req.heroImagePath() != null && Files.exists(req.heroImagePath())
                    ? safeRead(req.heroImagePath()) : null;

            String tpl = req.templateId() != null ? req.templateId() : "bottom-caption";
            switch (tpl) {
                case "split-vertical"   -> renderSplitVertical(g, req, hero);
                case "diagonal"         -> renderDiagonal(g, req, hero);
                case "minimal"          -> renderMinimal(g, req, hero);
                case "lifecopy-classic" -> renderLifecopyClassic(g, req, hero);
                default                 -> renderBottomCaption(g, req, hero);
            }
        } finally {
            g.dispose();
        }

        Path dir = Path.of(props.getStorage().getLocalDir()).toAbsolutePath();
        Files.createDirectories(dir);
        String ratioSafe = req.ratio().replace(':', 'x');
        String filename = "cover-" + req.scriptId() + "-" + ratioSafe + "-" + UUID.randomUUID() + ".png";
        Path out = dir.resolve(filename);
        ImageIO.write(img, "PNG", out.toFile());
        long size = Files.size(out);
        String url = tos.upload(
                TosStorageService.buildKey(req.scriptId(), "cover", filename), out, "image/png");
        try { Files.deleteIfExists(out); } catch (Exception ignored) {}
        log.info("[CoverRender] uploaded {} ({} bytes, {}x{}) → {}", filename, size, req.width(), req.height(), url);
        return new RenderResult(out, size, url);
    }

    private BufferedImage safeRead(Path p) {
        try {
            return ImageIO.read(p.toFile());
        } catch (IOException e) {
            log.warn("[CoverRender] read hero failed {}: {}", p, e.toString());
            return null;
        }
    }

    // ===================== 模板 1: bottom-caption =====================
    private void renderBottomCaption(Graphics2D g, RenderRequest req, BufferedImage hero) {
        int w = req.width(), h = req.height();
        BrandIdentity brand = req.brand();

        g.setColor(parseColor(brand.getBgColor(), Color.BLACK));
        g.fillRect(0, 0, w, h);

        if (hero != null) drawCover(g, hero, 0, 0, w, h);
        else drawHeroPlaceholder(g, 0, 0, w, h, brand);

        // 底部渐变蒙层(透明 → 主色)
        int overlayH = (int) (h * 0.55);
        Color primary = parseColor(brand.getPrimaryColor(), Color.BLACK);
        LinearGradientPaint grad = new LinearGradientPaint(
                new Point2D.Float(0, h - overlayH), new Point2D.Float(0, h),
                new float[]{0f, 0.5f, 1f},
                new Color[]{
                        new Color(0, 0, 0, 0),
                        withAlpha(primary, 0.55f),
                        withAlpha(primary, 0.92f),
                });
        g.setPaint(grad);
        g.fillRect(0, h - overlayH, w, overlayH);

        int padX = (int) (w * 0.07);
        int maxTextW = w - padX * 2;
        double ratio = (double) w / h;
        int titleSize = ratio < 1 ? (int) Math.round(w * 0.095) : (int) Math.round(h * 0.13);
        Font titleFont = pickBoldFont(titleSize);
        g.setFont(titleFont);
        FontMetrics fm = g.getFontMetrics();
        List<String> lines = capLines(wrapText(fm, nz(req.titleText(), "在这里写一个有钩子的标题"), maxTextW), 3);

        int cornerH = (int) Math.round(h * 0.06);
        double lineGap = titleSize * 0.18;
        double titleBlockH = lines.size() * (titleSize + lineGap) - lineGap;
        double titleEndY = h - cornerH * 2 - h * 0.04;
        double titleStartY = titleEndY - titleBlockH;

        g.setColor(Color.WHITE);
        for (int i = 0; i < lines.size(); i++) {
            double y = titleStartY + i * (titleSize + lineGap) + titleSize;
            g.drawString(lines.get(i), padX, (float) y);
        }

        drawLogoAndAuthor(g, padX, h - cornerH - (int) (h * 0.03), cornerH, brand, Color.WHITE);
    }

    // ===================== 模板 2: split-vertical =====================
    private void renderSplitVertical(Graphics2D g, RenderRequest req, BufferedImage hero) {
        int w = req.width(), h = req.height();
        BrandIdentity brand = req.brand();

        int heroH = (int) Math.round(h * 0.6);
        int captionH = h - heroH;

        if (hero != null) drawCover(g, hero, 0, 0, w, heroH);
        else drawHeroPlaceholder(g, 0, 0, w, heroH, brand);

        g.setColor(parseColor(brand.getBgColor(), new Color(0xF5, 0xEF, 0xE0)));
        g.fillRect(0, heroH, w, captionH);

        g.setColor(parseColor(brand.getPrimaryColor(), Color.BLACK));
        g.fillRect(0, heroH, w, Math.max(4, (int) (h * 0.005)));

        int padX = (int) (w * 0.07);
        int maxTextW = w - padX * 2;
        double ratio = (double) w / h;
        int titleSize = ratio > 1.5
                ? (int) Math.round(captionH * 0.32)
                : (int) Math.round(captionH * 0.26);
        g.setFont(pickBoldFont(titleSize));
        FontMetrics fm = g.getFontMetrics();
        List<String> lines = capLines(wrapText(fm, nz(req.titleText(), "主标题"), maxTextW), 2);        g.setColor(parseColor(brand.getPrimaryColor(), Color.BLACK));
        double lineGap = titleSize * 0.2;
        for (int i = 0; i < lines.size(); i++) {
            double y = heroH + captionH * 0.32 + i * (titleSize + lineGap) + titleSize;
            g.drawString(lines.get(i), padX, (float) y);
        }

        int cornerH = (int) Math.round(h * 0.05);
        drawLogoAndAuthor(g, padX, h - cornerH - (int) (h * 0.025), cornerH, brand,
                parseColor(brand.getPrimaryColor(), Color.BLACK));
    }

    // ===================== 模板 3: diagonal =====================
    private void renderDiagonal(Graphics2D g, RenderRequest req, BufferedImage hero) {
        int w = req.width(), h = req.height();
        BrandIdentity brand = req.brand();

        g.setColor(parseColor(brand.getBgColor(), new Color(0xF5, 0xEF, 0xE0)));
        g.fillRect(0, 0, w, h);

        double heroX = w * 0.42;
        double heroY = h * 0.08;
        double heroW = w * 0.55;
        double heroH = h * 0.84;

        AffineTransform old = g.getTransform();
        AffineTransform t = new AffineTransform();
        t.rotate(Math.toRadians(-3), heroX + heroW / 2, heroY + heroH / 2);
        g.transform(t);
        if (hero != null) drawCover(g, hero, (int) heroX, (int) heroY, (int) heroW, (int) heroH);
        else drawHeroPlaceholder(g, (int) heroX, (int) heroY, (int) heroW, (int) heroH, brand);
        g.setTransform(old);

        Color primary = parseColor(brand.getPrimaryColor(), Color.BLACK);
        g.setColor(withAlpha(primary, 0.92f));
        int[] xs = { 0, (int) (w * 0.46), (int) (w * 0.42), 0 };
        int[] ys = { 0, 0, h, h };
        g.fillPolygon(xs, ys, 4);

        int padX = (int) (w * 0.04);
        int maxTextW = (int) (w * 0.36);
        double ratio = (double) w / h;
        int titleSize = ratio < 1 ? (int) Math.round(w * 0.085) : (int) Math.round(h * 0.11);
        g.setFont(pickBoldFont(titleSize));
        FontMetrics fm = g.getFontMetrics();
        List<String> lines = capLines(wrapText(fm, nz(req.titleText(), "示例标题"), maxTextW), 4);

        double lineGap = titleSize * 0.18;
        double blockH = lines.size() * (titleSize + lineGap) - lineGap;
        double startY = h * 0.5 - blockH / 2 + titleSize;
        g.setColor(Color.WHITE);
        for (int i = 0; i < lines.size(); i++) {
            g.drawString(lines.get(i), padX, (float) (startY + i * (titleSize + lineGap)));
        }

        int cornerH = (int) Math.round(h * 0.05);
        drawLogoAndAuthor(g, padX, h - cornerH - (int) (h * 0.03), cornerH, brand, Color.WHITE);
    }

    // ===================== 模板 4: minimal =====================
    private void renderMinimal(Graphics2D g, RenderRequest req, BufferedImage hero) {
        int w = req.width(), h = req.height();
        BrandIdentity brand = req.brand();

        g.setColor(parseColor(brand.getBgColor(), new Color(0xF5, 0xEF, 0xE0)));
        g.fillRect(0, 0, w, h);

        Color primary = parseColor(brand.getPrimaryColor(), Color.BLACK);
        g.setColor(primary);
        g.fillRect(0, 0, w, Math.max(6, (int) (h * 0.008)));

        int padX = (int) (w * 0.1);
        int maxTextW = w - padX * 2;
        double ratio = (double) w / h;
        int titleSize = ratio < 1 ? (int) Math.round(w * 0.11) : (int) Math.round(h * 0.14);
        g.setFont(pickBoldFont(titleSize));
        FontMetrics fm = g.getFontMetrics();
        List<String> lines = capLines(wrapText(fm, nz(req.titleText(), "极简标题"), maxTextW), 3);

        double lineGap = titleSize * 0.25;
        double blockH = lines.size() * (titleSize + lineGap) - lineGap;
        double startY = h * 0.4 - blockH / 2 + titleSize;
        g.setColor(primary);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int textW = fm.stringWidth(line);
            g.drawString(line, (w - textW) / 2f, (float) (startY + i * (titleSize + lineGap)));
        }

        // 右下角圆角小 hero
        double heroSide = Math.min(w, h) * 0.32;
        double heroX = w - heroSide - w * 0.06;
        double heroY = h - heroSide - h * 0.1;
        double r = heroSide * 0.1;
        Shape oldClip = g.getClip();
        Shape rounded = new RoundRectangle2D.Double(heroX, heroY, heroSide, heroSide, r * 2, r * 2);
        g.setClip(rounded);
        if (hero != null) drawCover(g, hero, (int) heroX, (int) heroY, (int) heroSide, (int) heroSide);
        else drawHeroPlaceholder(g, (int) heroX, (int) heroY, (int) heroSide, (int) heroSide, brand);
        g.setClip(oldClip);

        g.setColor(primary);
        g.setStroke(new java.awt.BasicStroke((float) Math.max(2, w * 0.003)));
        g.draw(new RoundRectangle2D.Double(heroX, heroY, heroSide, heroSide, r * 2, r * 2));

        int cornerH = (int) Math.round(h * 0.05);
        drawLogoAndAuthor(g, (int) (w * 0.06), h - cornerH - (int) (h * 0.06), cornerH, brand, primary);
    }

    // ===================== 模板 5: lifecopy-classic =====================
    // 录取通知书风格:黑底外框 + 红底圆角 hero(保留 hero 原色)+ 底部主副标题
    // 标题 titleText 含 \n 时拆主副:小字主标题 + 大字副标题;无换行时整段当大字主标题
    private void renderLifecopyClassic(Graphics2D g, RenderRequest req, BufferedImage hero) {
        int w = req.width(), h = req.height();

        // 整体黑色外背景(写实质感)
        g.setColor(new Color(0x1a, 0x1a, 0x1a));
        g.fillRect(0, 0, w, h);

        // 红色圆角 hero 框,占上 70% 高度
        int padX = (int) (w * 0.04);
        int heroY = (int) (h * 0.04);
        int heroW = w - padX * 2;
        int heroH = (int) (h * 0.66);
        double radius = Math.min(heroW, heroH) * 0.04;

        // 红底(hero 没图时兜底成红框;有 hero 图时被覆盖,不再叠加染色)
        Shape oldClip = g.getClip();
        Shape heroClip = new RoundRectangle2D.Double(padX, heroY, heroW, heroH, radius * 2, radius * 2);
        g.setColor(new Color(0x8B, 0x1A, 0x1A));
        g.fill(heroClip);
        g.setClip(heroClip);
        if (hero != null) drawCover(g, hero, padX, heroY, heroW, heroH);
        g.setClip(oldClip);

        // 标题区:占下 24%,主副两行黄底黑描边
        int titleAreaY = heroY + heroH + (int) (h * 0.04);
        int titleAreaH = h - titleAreaY - (int) (h * 0.04);
        int titleMaxW = (int) (w * 0.92);
        double ratio = (double) w / h;

        // 拆主副:titleText 含 \n 时,第一行小字主标题,第二行大字副标题
        String rawTitle = nz(req.titleText(), "今天体验的人生:示例");
        String[] parts = rawTitle.split("\\n");
        String main = null;
        String sub;
        if (parts.length >= 2) {
            main = parts[0].trim();
            // 第二行及之后合并成 sub
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < parts.length; i++) {
                if (i > 1) sb.append(' ');
                sb.append(parts[i].trim());
            }
            sub = sb.toString();
        } else {
            sub = parts[0].trim();
        }

        int subSize = ratio < 1 ? (int) Math.round(w * 0.105) : (int) Math.round(h * 0.13);
        int mainSize = (int) Math.round(subSize * 0.6);
        int lineGap = (int) (subSize * 0.18);

        int totalH = subSize;
        if (main != null && !main.isBlank()) totalH += mainSize + lineGap;

        int yCursor = titleAreaY + (titleAreaH - totalH) / 2;

        if (main != null && !main.isBlank()) {
            yCursor += mainSize;
            g.setFont(pickBoldFont(mainSize));
            // 主标题:白色无描边(深色背景下已足够清晰,不抢副标题视觉重心)
            drawWhitePlain(g, main, w / 2f, yCursor, mainSize, titleMaxW);
            yCursor += lineGap;
        }
        yCursor += subSize;
        g.setFont(pickBoldFont(subSize));
        drawStrokedYellow(g, sub, w / 2f, yCursor, subSize, titleMaxW);
    }

    /** 大字主体:黑色描边 + 黄色填充。GlyphVector 取轮廓,跟 canvas strokeText/fillText 视觉一致。 */
    private void drawStrokedYellow(Graphics2D g, String text, float cx, float baselineY,
                                   int fontSize, int maxW) {
        if (text == null || text.isBlank()) return;
        FontMetrics fm = g.getFontMetrics();

        String line = text;
        if (fm.stringWidth(line) > maxW) {
            while (line.length() > 1 && fm.stringWidth(line + "…") > maxW) {
                line = line.substring(0, line.length() - 1);
            }
            line = line + "…";
        }
        int textW = fm.stringWidth(line);
        float lineX = cx - textW / 2f;

        float strokeWidth = (float) Math.max(4, fontSize * 0.08);
        java.awt.Stroke oldStroke = g.getStroke();
        g.setStroke(new java.awt.BasicStroke(strokeWidth,
                java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));

        java.awt.font.GlyphVector gv = g.getFont().createGlyphVector(g.getFontRenderContext(), line);
        Shape outline = gv.getOutline(lineX, baselineY);
        g.setColor(Color.BLACK);
        g.draw(outline);
        g.setColor(new Color(0xFF, 0xD9, 0x3D));
        g.fill(outline);

        g.setStroke(oldStroke);
    }

    /** 小字主标题:纯白填充。标题区是深色底,不需要描边。 */
    private void drawWhitePlain(Graphics2D g, String text, float cx, float baselineY,
                                int fontSize, int maxW) {
        if (text == null || text.isBlank()) return;
        FontMetrics fm = g.getFontMetrics();
        String line = text;
        if (fm.stringWidth(line) > maxW) {
            while (line.length() > 1 && fm.stringWidth(line + "…") > maxW) {
                line = line.substring(0, line.length() - 1);
            }
            line = line + "…";
        }
        int textW = fm.stringWidth(line);
        g.setColor(Color.WHITE);
        g.drawString(line, cx - textW / 2f, baselineY);
    }

    // ===================== 工具 =====================

    /** background-size: cover —— 填满目标区域,中心裁切。 */
    private void drawCover(Graphics2D g, BufferedImage img, int x, int y, int w, int h) {
        double imgRatio = (double) img.getWidth() / img.getHeight();
        double boxRatio = (double) w / h;
        int sx = 0, sy = 0, sw = img.getWidth(), sh = img.getHeight();
        if (imgRatio > boxRatio) {
            sw = (int) (img.getHeight() * boxRatio);
            sx = (img.getWidth() - sw) / 2;
        } else {
            sh = (int) (img.getWidth() / boxRatio);
            sy = (img.getHeight() - sh) / 2;
        }
        g.drawImage(img, x, y, x + w, y + h, sx, sy, sx + sw, sy + sh, null);
    }

    /** hero 没传时:左上 primary → 右下 secondary 渐变 + "封面主图占位" 文字。 */
    private void drawHeroPlaceholder(Graphics2D g, int x, int y, int w, int h, BrandIdentity brand) {
        Color p = parseColor(brand.getPrimaryColor(), Color.DARK_GRAY);
        Color s = parseColor(brand.getSecondaryColor(), Color.GRAY);
        g.setPaint(new GradientPaint(x, y, p, x + w, y + h, s));
        g.fillRect(x, y, w, h);
        g.setColor(new Color(255, 255, 255, 90));
        int sz = (int) (Math.min(w, h) * 0.06);
        g.setFont(pickPlainFont(sz));
        FontMetrics fm = g.getFontMetrics();
        String txt = "封面主图占位";
        int tw = fm.stringWidth(txt);
        // textBaseline=middle 等价: y + ascent - (ascent+descent)/2
        int by = y + h / 2 + fm.getAscent() - (fm.getAscent() + fm.getDescent()) / 2;
        g.drawString(txt, x + (w - tw) / 2f, by);
    }

    /** logo 圆形 clip + 古铜金描边 + 第一行 brandName + @author 小字 */
    private void drawLogoAndAuthor(Graphics2D g, int x, int y, int h, BrandIdentity brand, Color textColor) {
        int cursorX = x;
        BufferedImage logoImg = decodeLogo(brand.getLogoDataUrl());
        if (logoImg != null) {
            int size = h;
            int cx = cursorX + size / 2;
            int cy = y + size / 2;
            float r = size / 2f;

            Shape oldClip = g.getClip();
            g.setClip(new Ellipse2D.Float(cursorX, y, size, size));
            // backing 防透明边角与封面色撞
            g.setColor(Color.decode(brand.getBgColor()));
            g.fillRect(cursorX, y, size, size);
            // max(scaleX, scaleY) 中心裁切,保证非方图也填满圆
            float sx = (float) size / logoImg.getWidth();
            float sy = (float) size / logoImg.getHeight();
            float scale = Math.max(sx, sy);
            int dw = Math.round(logoImg.getWidth() * scale);
            int dh = Math.round(logoImg.getHeight() * scale);
            int dx = cursorX + (size - dw) / 2;
            int dy = y + (size - dh) / 2;
            g.drawImage(logoImg, dx, dy, dw, dh, null);
            g.setClip(oldClip);

            float strokeW = Math.max(1.5f, h * 0.04f);
            g.setColor(Color.decode(brand.getSecondaryColor()));
            g.setStroke(new BasicStroke(strokeW));
            g.draw(new Ellipse2D.Float(cursorX + strokeW / 2f, y + strokeW / 2f,
                    size - strokeW, size - strokeW));

            cursorX += size + Math.round(h * 0.4);
        }

        String primaryText = brand.getBrandName() == null ? "" : brand.getBrandName().trim();
        String secondaryText = brand.getAuthorName() == null ? "" : brand.getAuthorName().trim();
        if (primaryText.isEmpty() && secondaryText.isEmpty()) return;

        g.setColor(textColor);
        FontMetrics fm;
        if (!primaryText.isEmpty()) {
            int sz = Math.round(h * 0.55f);
            g.setFont(pickPlainFont(sz));
            fm = g.getFontMetrics();
            int by = y + h / 2 + fm.getAscent() - (fm.getAscent() + fm.getDescent()) / 2;
            g.drawString(primaryText, cursorX, by);
            cursorX += fm.stringWidth(primaryText);
        }
        if (!secondaryText.isEmpty()) {
            String prefix = primaryText.isEmpty() ? "@" : "  @";
            int sz = Math.round(h * 0.42f);
            g.setFont(pickPlainFont(sz));
            fm = g.getFontMetrics();
            // 小字降透明度到 70%
            Composite oldC = g.getComposite();            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.7f));
            int by = y + h / 2 + fm.getAscent() - (fm.getAscent() + fm.getDescent()) / 2;
            g.drawString(prefix + secondaryText, cursorX, by);
            g.setComposite(oldC);
        }
    }

    /** data:image/png;base64,xxx → BufferedImage,失败返 null。 */
    private BufferedImage decodeLogo(String dataUrl) {
        if (dataUrl == null || dataUrl.isBlank()) return null;
        try {
            int comma = dataUrl.indexOf(',');
            String b64 = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
            byte[] bytes = Base64.getDecoder().decode(b64);
            try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
                return ImageIO.read(in);
            }
        } catch (Exception e) {
            log.warn("[Java2DCoverRenderer] decode logo failed: {}", e.getMessage());
            return null;
        }
    }

    /** 中文宽度按字宽贪心断行,Java FontMetrics 与 canvas measureText 行为一致。 */
    private List<String> wrapText(FontMetrics fm, String text, int maxWidth) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        StringBuilder cur = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int charLen = Character.charCount(cp);
            String ch = new String(Character.toChars(cp));
            i += charLen;
            if ("\n".equals(ch)) {
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            String next = cur + ch;
            if (fm.stringWidth(next) > maxWidth && cur.length() > 0) {
                out.add(cur.toString());
                cur = new StringBuilder(ch);
            } else {
                cur.append(ch);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** 截最多 N 行,溢出最后一行末尾换 …。 */
    private List<String> capLines(List<String> lines, int max) {
        if (lines.size() <= max) return lines;
        List<String> cap = new ArrayList<>(lines.subList(0, max));
        String last = cap.get(max - 1);
        if (!last.isEmpty()) {
            cap.set(max - 1, last.substring(0, last.length() - 1) + "…");
        }
        return cap;
    }

    private Font pickBoldFont(int size) {
        return pickFont(Font.BOLD, size);
    }

    private Font pickPlainFont(int size) {
        return pickFont(Font.PLAIN, size);
    }

    /** Java 找不到 family 自动回退到 SansSerif,不会抛错。
     *  字体 OS-aware:DB 有值用 DB;DB 空 + macOS 用 yml(默认 PingFang SC);
     *  DB 空 + Linux/其它 用 Noto Sans CJK SC。V14 已把 V11 写入的 'PingFang SC' 清空,
     *  让 fresh Linux/Docker 部署自动走 Noto。 */
    private Font pickFont(int style, int size) {
        String family = runtimeConfig.get("auteur.cover.java2d.font-family");
        if (family.isBlank()) {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("mac") || os.contains("darwin")) {
                family = props.getJava2d().getFontFamily(); // yml 默认 PingFang SC
            } else {
                family = "Noto Sans CJK SC";
            }
        }
        if (family == null || family.isBlank()) family = Font.SANS_SERIF;
        return new Font(family, style, size);
    }

    /** "#RRGGBB" → Color。解析失败用 fallback。 */
    private static Color parseColor(String hex, Color fallback) {
        if (hex == null || hex.isBlank()) return fallback;
        String s = hex.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() == 6) {
            try {
                int r = Integer.parseInt(s.substring(0, 2), 16);
                int gn = Integer.parseInt(s.substring(2, 4), 16);
                int b = Integer.parseInt(s.substring(4, 6), 16);
                return new Color(r, gn, b);
            } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    private static Color withAlpha(Color c, float alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(alpha * 255));
    }

    @SuppressWarnings("unused")
    private static final MultipleGradientPaint.CycleMethod NO_CYCLE_REF = MultipleGradientPaint.CycleMethod.NO_CYCLE;

    private static String nz(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    @SuppressWarnings("unused")
    private static Rectangle2D.Double rect(double x, double y, double w, double h) {
        return new Rectangle2D.Double(x, y, w, h);
    }
}
