package com.examprep.service;

import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * 图片水印服务：给资料预览图叠加平铺水印
 *
 * 水印内容包含访问者标识（脱敏学号 / 游客），资料被截图传播时可追溯来源 ——
 * 这就是大纲里「下载与预览带水印」的真实意图：不是防看，是防无责任传播。
 */
@Service
public class WatermarkService {

    private static final String PLATFORM = "ExamPilot";

    /**
     * 在原图上叠加 45° 平铺水印
     *
     * @param src          原图（会被直接修改并返回）
     * @param visitorLabel 访问者标识，如「2021****01」；null 表示游客
     */
    public BufferedImage apply(BufferedImage src, String visitorLabel) {
        String text = PLATFORM + " · "
                + (visitorLabel == null || visitorLabel.isBlank() ? "游客预览" : visitorLabel);

        int w = src.getWidth();
        int h = src.getHeight();

        // 关键：统一转为 TYPE_INT_RGB。
        // PNG（尤其索引色/带 alpha）直接喂给 JPEG 编码器会静默失败（ImageIO.write 返回 false），
        // 表现为接口 200 但响应体 0 字节 —— 先重绘到 RGB 画布规避。
        BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D copy = canvas.createGraphics();
        try {
            copy.drawImage(src, 0, 0, null);
        } finally {
            copy.dispose();
        }

        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int fontSize = Math.max(22, Math.min(36, w / 18));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
            // 半透明黑：任何底色的图上都可见，又不影响阅读
            g.setColor(new Color(0, 0, 0, 42));

            g.rotate(Math.toRadians(-24), w / 2.0, h / 2.0);
            FontMetrics fm = g.getFontMetrics();
            int stepX = fm.stringWidth(text) + 140;
            int stepY = fm.getHeight() + 110;
            for (int y = -h; y < h * 2; y += stepY) {
                for (int x = -w; x < w * 2; x += stepX) {
                    g.drawString(text, x, y);
                }
            }
        } finally {
            g.dispose();
        }
        return canvas;
    }

    /** 访问者水印标识（学号脱敏） */
    public static String maskStudentNo(String studentNo) {
        if (studentNo == null || studentNo.length() <= 6) {
            return "用户";
        }
        return studentNo.substring(0, 4) + "****" + studentNo.substring(studentNo.length() - 2);
    }
}
