package com.examprep.service.ai;

/**
 * 大模型结构化输出的轻量修复器
 *
 * <p>本地 7B / 部分兼容端点经常返回「整体语义正确、局部语法畸形」的 JSON：
 * Markdown 围栏、行内注释、尾随逗号、缺失/多余的右括号、JSON 之后的解释性文字。
 * 这些都不该直接判定为「模型不可用」——先修复一次，能救回绝大多数样本。
 *
 * <p>只做<b>语法层</b>修复，不猜测缺失字段、不补造内容；修复失败仍由上层降级。
 */
public final class JsonOutputRepair {

    private JsonOutputRepair() {
    }

    /**
     * 修复常见 JSON 畸形
     *
     * @param raw 模型原始输出
     * @return 修复后的 JSON 文本；无法定位对象时返回原始文本
     */
    public static String repair(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.replace("\uFEFF", "").trim();
        // 去掉整行 Markdown 围栏（``` 或 ```json）
        text = text.replaceAll("(?m)^\\s*```[a-zA-Z0-9_-]*\\s*$", "").trim();
        int start = text.indexOf('{');
        if (start < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        StringBuilder stack = new StringBuilder();
        boolean inString = false;
        boolean escape = false;
        boolean rootClosed = false;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);

            if (inString) {
                out.append(c);
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            // 字符串外的注释：// 到行尾、/* ... */
            if (c == '/' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                if (next == '/') {
                    int nl = text.indexOf('\n', i);
                    i = nl < 0 ? text.length() : nl;
                    continue;
                }
                if (next == '*') {
                    int close = text.indexOf("*/", i + 2);
                    i = close < 0 ? text.length() : close + 1;
                    continue;
                }
            }

            if (c == '"') {
                inString = true;
                out.append(c);
                continue;
            }
            if (c == '{' || c == '[') {
                stack.append(c);
                out.append(c);
                continue;
            }
            if (c == '}' || c == ']') {
                if (stack.length() == 0) {
                    // 多余的右括号：直接丢弃
                    continue;
                }
                char open = stack.charAt(stack.length() - 1);
                if ((open == '{' && c == '}') || (open == '[' && c == ']')) {
                    stack.deleteCharAt(stack.length() - 1);
                    out.append(c);
                    if (stack.length() == 0) {
                        rootClosed = true;
                        break;
                    }
                } else {
                    // 括号不匹配：补上缺失的闭合再处理当前字符
                    closeAll(out, stack);
                    if (c == '}' || c == ']') {
                        out.append(c);
                    }
                    rootClosed = true;
                    break;
                }
                continue;
            }
            out.append(c);
        }

        if (inString) {
            out.append('"');
        }
        if (!rootClosed && stack.length() > 0) {
            closeAll(out, stack);
        }
        return removeTrailingCommas(out.toString());
    }

    private static void closeAll(StringBuilder out, StringBuilder stack) {
        for (int i = stack.length() - 1; i >= 0; i--) {
            out.append(stack.charAt(i) == '{' ? '}' : ']');
        }
        stack.setLength(0);
    }

    /** 删除 } / ] 前的尾随逗号（仅在字符串外） */
    static String removeTrailingCommas(String json) {
        StringBuilder out = new StringBuilder(json.length());
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                out.append(c);
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                continue;
            }
            if (c == ',') {
                int j = i + 1;
                while (j < json.length() && Character.isWhitespace(json.charAt(j))) {
                    j++;
                }
                if (j < json.length() && (json.charAt(j) == '}' || json.charAt(j) == ']')) {
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }
}
