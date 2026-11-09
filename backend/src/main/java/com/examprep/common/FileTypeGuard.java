package com.examprep.common;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * 文件类型守卫：扩展名 → MIME 的<b>服务端权威映射</b> + 头部字节特征识别。
 *
 * <p><b>为什么要这个类（第 9 周 P0 修复）</b>
 * <ol>
 *   <li><b>MIME 不可信</b>：{@code MultipartFile.getContentType()} 完全由客户端提供。
 *       一旦把它当权威写库、再原样写回响应头（历史实现见
 *       {@code FileController.audio}），攻击者就能让本服务在<b>同源</b>下托管
 *       {@code text/html}，形成存储型 XSS；管理端 token 存在 localStorage，
 *       等价于管理员会话被劫持。</li>
 *   <li><b>音频此前无魔数校验</b>：{@code mp3/wav/m4a/aac/ogg} 不在
 *       {@code FileStorageService.MAGIC_CHECKED_TYPES} 内，任意字节都能伪装成 {@code .mp3}。</li>
 *   <li><b>文档侧只能"白名单扩展名"</b>：{@code .txt} 等文本类型可以塞进 HTML/SVG，
 *       一旦将来被内联渲染即为 XSS。这里用 {@link #looksLikeMarkup(byte[])} 做兜底拒绝。</li>
 * </ol>
 *
 * <p><b>设计约束</b>：纯静态、无 Spring / 无 IO 依赖，可直接用 JUnit 5 单测覆盖
 * （{@code src/test/java/com/examprep/common/FileTypeGuardTest.java}）。
 * 所有方法对 {@code null} / 空数组均安全返回，不抛异常——校验类工具宁可返回
 * "不像"也不要制造 500。
 */
public final class FileTypeGuard {

    /** 已知音频扩展名 → 权威 MIME */
    private static final Map<String, String> AUDIO_MIME = Map.of(
            "mp3", "audio/mpeg",
            "wav", "audio/wav",
            "m4a", "audio/mp4",
            "aac", "audio/aac",
            "ogg", "audio/ogg");

    /** 已知图片扩展名 → 权威 MIME（与 app.upload.allowed-image-types 保持一致） */
    private static final Map<String, String> IMAGE_MIME = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "webp", "image/webp",
            "gif", "image/gif");

    /** 已知文档扩展名 → 权威 MIME（与 FileStorageService.allowedDocTypes 保持一致） */
    private static final Map<String, String> DOCUMENT_MIME = Map.ofEntries(
            Map.entry("pdf", "application/pdf"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("zip", "application/zip"),
            Map.entry("txt", "text/plain"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("mov", "video/quicktime"),
            Map.entry("avi", "video/x-msvideo"),
            Map.entry("webm", "video/webm"),
            // 音频也允许出现在文档上传白名单里，复用同一份映射
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("wav", "audio/wav"),
            Map.entry("m4a", "audio/mp4"));

    /** 未识别扩展名的兜底类型：浏览器一律当二进制下载，不渲染、不执行 */
    private static final String OCTET_STREAM = "application/octet-stream";

    /** 认定"看起来像标记语言"的头部前缀（小写比较） */
    private static final String[] MARKUP_PREFIXES = {
            "<!doctype html", "<html", "<script", "<?xml", "<svg", "<!doctype svg"
    };

    /** 头部样本足够判定多少个字节（读 64 字节即可覆盖所有已知魔数） */
    public static final int HEAD_BYTES = 64;

    private FileTypeGuard() {
    }

    // ==================================================================
    // 扩展名 → MIME
    // ==================================================================

    /**
     * 音频扩展名 → 权威 MIME。
     *
     * @param ext 扩展名（大小写不敏感，可带前导 '.'）
     * @return 已知音频类型返回对应 {@code audio/*}；未知返回 {@code application/octet-stream}
     */
    public static String canonicalAudioMime(String ext) {
        String key = normalizeExt(ext);
        String mime = AUDIO_MIME.get(key);
        return mime == null ? OCTET_STREAM : mime;
    }

    /**
     * 任意受支持扩展名 → 权威 MIME（图片 / 音频 / 文档三张表合并）。
     *
     * <p>落库与回显都必须用本方法的返回值，<b>不得</b>使用客户端提交的 Content-Type。
     *
     * @param ext 扩展名（大小写不敏感，可带前导 '.'）
     * @return 已知类型返回对应 MIME；未知返回 {@code application/octet-stream}
     */
    public static String canonicalMime(String ext) {
        String key = normalizeExt(ext);
        String mime = AUDIO_MIME.get(key);
        if (mime == null) {
            mime = IMAGE_MIME.get(key);
        }
        if (mime == null) {
            mime = DOCUMENT_MIME.get(key);
        }
        return mime == null ? OCTET_STREAM : mime;
    }

    // ==================================================================
    // 头部特征识别
    // ==================================================================

    /**
     * 头部字节是否像一个真实的音频容器。
     *
     * <p>支持的签名：
     * <ul>
     *   <li>{@code ID3} —— mp3（带 ID3v2 标签）</li>
     *   <li>{@code RIFF....WAVE} —— wav</li>
     *   <li>{@code fLaC} —— flac（虽未在白名单，但出现即说明是音频流，便于放宽判断）</li>
     *   <li>{@code OggS} —— ogg / opus</li>
     *   <li>{@code ....ftyp} —— m4a / mp4（ISO BMFF，box 长度占前 4 字节）</li>
     *   <li>{@code 0xFF 0xFx} —— ADTS AAC 同步字</li>
     * </ul>
     *
     * @param head 文件头部字节（建议前 64 字节）
     * @return true 表示更像音频；false 表示无法确认（调用方应拒绝）
     */
    public static boolean looksLikeAudio(byte[] head) {
        if (head == null || head.length < 4) {
            return false;
        }
        // mp3 with ID3v2 tag: "ID3"
        if (head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
            return true;
        }
        // wav: "RIFF" + 4 字节长度 + "WAVE"
        if (matches(head, 0, "RIFF") && head.length >= 12 && matches(head, 8, "WAVE")) {
            return true;
        }
        // flac: "fLaC"
        if (matches(head, 0, "fLaC")) {
            return true;
        }
        // ogg: "OggS"
        if (matches(head, 0, "OggS")) {
            return true;
        }
        // m4a / mp4: 前 4 字节是 box 长度（任意），随后 "ftyp"
        if (head.length >= 8 && matches(head, 4, "ftyp")) {
            return true;
        }
        // ADTS AAC 同步字：0xFF 0xFx（12 位全 1 同步 + MPEG 版本位）
        int b0 = head[0] & 0xFF;
        int b1 = head[1] & 0xFF;
        return b0 == 0xFF && (b1 & 0xF0) == 0xF0;
    }

    /**
     * 头部字节是否像 HTML / XML / SVG 等<b>可被浏览器执行</b>的标记语言。
     *
     * <p>用于拒绝「纯文本/标记语言伪装成 .txt / .docx / .mp3」这类内容，
     * 因为此类文件一旦被内联渲染就等价于在本服务同源下执行脚本。
     *
     * <p>判定规则：跳过 UTF-8 BOM 与前导空白后，首部命中
     * {@code <!doctype html}、{@code <html}、{@code <script}、{@code <?xml}、
     * {@code <svg}、{@code <!doctype svg} 之一即视为标记语言。
     * 其中 {@code <?xml} 会连带拒掉 XML 声明过的 {@code <svg>}（XML 文档首部本就如此），
     * 这种"宁可误拒"的取向对上传场景是安全的：白名单里没有 xml/svg 扩展名。
     *
     * @param head 文件头部字节（建议前 64 字节）
     * @return true 表示应拒绝
     */
    public static boolean looksLikeMarkup(byte[] head) {
        if (head == null || head.length == 0) {
            return false;
        }
        int offset = skipBomAndWhitespace(head);
        if (offset >= head.length) {
            return false;
        }
        // 头部是二进制控制字符时不可能命中标记语言前缀，用 ISO-8859-1 逐字节映射即可，避免解码异常
        String text = new String(head, offset, head.length - offset, StandardCharsets.ISO_8859_1)
                .toLowerCase(Locale.ROOT);
        for (String prefix : MARKUP_PREFIXES) {
            if (text.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    /** 去掉 BOM(EF BB BF) 与前导空白（空格 / 制表 / 回车 / 换行 / NUL），返回首个有效字节下标 */
    private static int skipBomAndWhitespace(byte[] head) {
        int i = 0;
        if (head.length >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB
                && (head[2] & 0xFF) == 0xBF) {
            i = 3;
        }
        while (i < head.length) {
            byte b = head[i];
            if (b == ' ' || b == '\t' || b == '\r' || b == '\n' || b == 0) {
                i++;
                continue;
            }
            break;
        }
        return i;
    }

    /** 从 offset 起逐个字节比较 ASCII 字面量（区分大小写） */
    private static boolean matches(byte[] data, int offset, String ascii) {
        if (data == null || offset < 0 || data.length < offset + ascii.length()) {
            return false;
        }
        for (int i = 0; i < ascii.length(); i++) {
            if ((data[offset + i] & 0xFF) != ascii.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** 扩展名归一化：去空白、去前导 '.'、转小写 */
    private static String normalizeExt(String ext) {
        if (ext == null) {
            return "";
        }
        String key = ext.trim().toLowerCase(Locale.ROOT);
        while (key.startsWith(".")) {
            key = key.substring(1);
        }
        return key;
    }
}
