package com.examprep.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("日志脱敏工具测试")
class LogMaskUtilsTest {

    @Test
    void masksStudentNoAndPhone() {
        assertEquals("20***01", LogMaskUtils.maskAccount("2021001"));
        assertEquals("a***n", LogMaskUtils.maskAccount("admin"));
        assertEquals("a*c", LogMaskUtils.maskAccount("abc"));
        assertEquals("***", LogMaskUtils.maskAccount(null));
        assertEquals("138****5678", LogMaskUtils.maskPhone("13812345678"));
    }
}
