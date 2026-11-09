package com.examprep.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FileTypeGuard} 单元测试（纯 JUnit 5，不启动 Spring）。
 *
 * <p>覆盖第 9 周两个 P0 的判定逻辑：
 * <ul>
 *   <li><b>P0-2 前半</b>：音频魔数识别——合法容器头必须放行，纯文本 / HTML 必须拦下；</li>
 *   <li><b>P0-2 后半</b>：标记语言识别——HTML / SVG(含 XML 声明) 必须拦下，PDF 与普通文本不能误伤；</li>
 *   <li><b>MIME 权威映射</b>：回显与落库都只能用这里的返回值，未知扩展名统一
 *       {@code application/octet-stream}，绝不回落到客户端声明的类型。</li>
 * </ul>
 */
@DisplayName("FileTypeGuard：音频魔数 / 标记语言识别 / MIME 权威映射")
class FileTypeGuardTest {

    // ==================================================================
    // looksLikeAudio —— 合法音频必须放行
    // ==================================================================

    @Test
    @DisplayName("ID3：mp3 带 ID3v2 标签头 → true")
    void audioMp3WithId3Tag() {
        byte[] head = concat(bytes("ID3"), new byte[]{0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
        assertTrue(FileTypeGuard.looksLikeAudio(head));
    }

    @Test
    @DisplayName("RIFF....WAVE：wav 容器头 → true（同族 RIFF 但非 WAVE 不算）")
    void audioWavRiffWave() {
        byte[] wav = concat(bytes("RIFF"), new byte[]{0x24, 0x08, 0x00, 0x00}, bytes("WAVEfmt "));
        assertTrue(FileTypeGuard.looksLikeAudio(wav));

        // 反向：RIFF 但不是 WAVE（如 AVI）不应被误判为音频
        byte[] riffNotWave = concat(bytes("RIFF"), new byte[]{0x24, 0x08, 0x00, 0x00}, bytes("AVI LIST"));
        assertFalse(FileTypeGuard.looksLikeAudio(riffNotWave));
    }

    @Test
    @DisplayName("fLaC：flac 容器头 → true")
    void audioFlac() {
        assertTrue(FileTypeGuard.looksLikeAudio(concat(bytes("fLaC"), new byte[]{0x00, 0x00, 0x00, 0x22})));
    }

    @Test
    @DisplayName("OggS：ogg/opus 容器头 → true")
    void audioOgg() {
        assertTrue(FileTypeGuard.looksLikeAudio(concat(bytes("OggS"), new byte[]{0x00, 0x02, 0x00, 0x00})));
    }

    @Test
    @DisplayName("ftyp：ISO BMFF（m4a/mp4）头 → true")
    void audioM4aFtyp() {
        byte[] m4a = concat(new byte[]{0x00, 0x00, 0x00, 0x20}, bytes("ftypM4A "), new byte[]{0x00, 0x00, 0x00});
        assertTrue(FileTypeGuard.looksLikeAudio(m4a));
    }

    @Test
    @DisplayName("ADTS 同步字 0xFF 0xFx：aac 帧头 → true")
    void audioAdtsSync() {
        assertTrue(FileTypeGuard.looksLikeAudio(new byte[]{(byte) 0xFF, (byte) 0xF1, 0x50, (byte) 0x80, 0x00}));
        assertTrue(FileTypeGuard.looksLikeAudio(new byte[]{(byte) 0xFF, (byte) 0xF9, 0x50, (byte) 0x80, 0x00}));
    }

    // ==================================================================
    // looksLikeAudio —— 非音频必须拦下
    // ==================================================================

    @Test
    @DisplayName("纯文本 / HTML / 空数组 / null → false（不得误放行）")
    void audioRejectsTextAndHtml() {
        assertFalse(FileTypeGuard.looksLikeAudio(bytes("this is plain text, not audio at all")));
        assertFalse(FileTypeGuard.looksLikeAudio(bytes("<html><body>x</body></html>")));
        assertFalse(FileTypeGuard.looksLikeAudio(new byte[0]));
        assertFalse(FileTypeGuard.looksLikeAudio(null));
        assertFalse(FileTypeGuard.looksLikeAudio(new byte[]{0x49, 0x44})); // 长度不足 4
    }

    // ==================================================================
    // looksLikeMarkup —— HTML / SVG 必须拦下
    // ==================================================================

    @Test
    @DisplayName("<!DOCTYPE html> / <html> / <script> → true")
    void markupHtml() {
        assertTrue(FileTypeGuard.looksLikeMarkup(bytes("<!DOCTYPE html><html><body>hi")));
        assertTrue(FileTypeGuard.looksLikeMarkup(bytes("<html lang=\"zh\"><body>hi")));
        assertTrue(FileTypeGuard.looksLikeMarkup(bytes("<script>alert(document.domain)</script>")));
    }

    @Test
    @DisplayName("前导空白 / 前导换行 / UTF-8 BOM 不影响判定 → true")
    void markupWithLeadingWhitespaceAndBom() {
        assertTrue(FileTypeGuard.looksLikeMarkup(bytes("   \r\n\t <html></html>")));
        assertTrue(FileTypeGuard.looksLikeMarkup(concat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                bytes("<!DOCTYPE html>"))));
        assertTrue(FileTypeGuard.looksLikeMarkup(bytes("\n\n  <!doctype html>")));
    }

    @Test
    @DisplayName("<svg / <?xml ... <svg>  → true（XML 声明的 SVG 也要拦）")
    void markupSvg() {
        assertTrue(FileTypeGuard.looksLikeMarkup(bytes("<svg xmlns=\"http://www.w3.org/2000/svg\">")));
        assertTrue(FileTypeGuard.looksLikeMarkup(
                bytes("<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\">")));
        assertTrue(FileTypeGuard.looksLikeMarkup(bytes("<!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 1.1//EN\">")));
    }

    @Test
    @DisplayName("普通文本 / PDF 头 / 二进制噪声 → false（不误伤正常上传）")
    void markupRejectsNormalContent() {
        assertFalse(FileTypeGuard.looksLikeMarkup(bytes("this is a normal txt content, 1 + 1 = 2")));
        assertFalse(FileTypeGuard.looksLikeMarkup(bytes("%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj")));
        assertFalse(FileTypeGuard.looksLikeMarkup(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A}));
        assertFalse(FileTypeGuard.looksLikeMarkup(new byte[0]));
        assertFalse(FileTypeGuard.looksLikeMarkup(null));
        // "<html" 出现在文本中间不算（只认头部）
        assertFalse(FileTypeGuard.looksLikeMarkup(bytes("note: use <html> tag in your homework")));
    }

    // ==================================================================
    // canonicalAudioMime / canonicalMime —— MIME 权威映射
    // ==================================================================

    @Test
    @DisplayName("canonicalAudioMime：5 个已知后缀 + 未知后缀回退 octet-stream")
    void canonicalAudioMimeMapping() {
        assertEquals("audio/mpeg", FileTypeGuard.canonicalAudioMime("mp3"));
        assertEquals("audio/wav", FileTypeGuard.canonicalAudioMime("wav"));
        assertEquals("audio/mp4", FileTypeGuard.canonicalAudioMime("m4a"));
        assertEquals("audio/aac", FileTypeGuard.canonicalAudioMime("aac"));
        assertEquals("audio/ogg", FileTypeGuard.canonicalAudioMime("ogg"));
        // 未知后缀必须回落到二进制流，绝不回显客户端声明的 text/html
        assertEquals("application/octet-stream", FileTypeGuard.canonicalAudioMime("html"));
        assertEquals("application/octet-stream", FileTypeGuard.canonicalAudioMime(null));
        assertEquals("application/octet-stream", FileTypeGuard.canonicalAudioMime(""));
    }

    @Test
    @DisplayName("canonicalAudioMime：大小写与前导点归一化（.MP3 / .Wav 同样识别）")
    void canonicalAudioMimeNormalize() {
        assertEquals("audio/mpeg", FileTypeGuard.canonicalAudioMime(".MP3"));
        assertEquals("audio/wav", FileTypeGuard.canonicalAudioMime(" Wav "));
        assertEquals("audio/ogg", FileTypeGuard.canonicalAudioMime(".oGg"));
    }

    @Test
    @DisplayName("canonicalMime：图片/文档/音频全覆盖，未知回落 octet-stream")
    void canonicalMimeMapping() {
        assertEquals("image/jpeg", FileTypeGuard.canonicalMime("jpg"));
        assertEquals("image/png", FileTypeGuard.canonicalMime("PNG"));
        assertEquals("application/pdf", FileTypeGuard.canonicalMime("pdf"));
        assertEquals("text/plain", FileTypeGuard.canonicalMime("txt"));
        assertEquals("application/zip", FileTypeGuard.canonicalMime("zip"));
        assertEquals("audio/mpeg", FileTypeGuard.canonicalMime("mp3"));
        assertEquals("video/mp4", FileTypeGuard.canonicalMime("mp4"));
        assertEquals("application/octet-stream", FileTypeGuard.canonicalMime("exe"));
        assertEquals("application/octet-stream", FileTypeGuard.canonicalMime(null));
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 字符串按 UTF-8 转字节（测试数据都是 ASCII，等价于逐字节） */
    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** 顺序拼接多个字节数组 */
    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) {
            len += p.length;
        }
        byte[] out = new byte[len];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }
}
