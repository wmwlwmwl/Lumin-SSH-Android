package com.lumin.ssh.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 用数值构造期望控制符，避免源码里出现不可见字符。 */
private fun ctrl(code: Int) = code.toChar().toString()

class CtrlKeyPayloadTest {
    @Test
    fun lettersMapToControlCodes() {
        // nano 保存 ^O=15 / 退出 ^X=24 / 剪切 ^K=11 / 搜索 ^W=23
        assertEquals(ctrl(15), ctrlKeyPayload("o"))
        assertEquals(ctrl(24), ctrlKeyPayload("x"))
        assertEquals(ctrl(11), ctrlKeyPayload("k"))
        assertEquals(ctrl(23), ctrlKeyPayload("w"))
        // 原快捷键栏的 ^C=3 / ^D=4 行为不变
        assertEquals(ctrl(3), ctrlKeyPayload("c"))
        assertEquals(ctrl(4), ctrlKeyPayload("d"))
        assertEquals(ctrl(1), ctrlKeyPayload("a"))
        assertEquals(ctrl(26), ctrlKeyPayload("z"))
    }

    @Test
    fun uppercaseMatchesLowercase() {
        assertEquals(ctrlKeyPayload("o"), ctrlKeyPayload("O"))
    }

    @Test
    fun punctuationMapsToControlCodes() {
        assertEquals(ctrl(0), ctrlKeyPayload("@"))
        assertEquals(ctrl(27), ctrlKeyPayload("["))
        assertEquals(ctrl(28), ctrlKeyPayload("\\"))
        assertEquals(ctrl(31), ctrlKeyPayload("_"))
        assertEquals(ctrl(127), ctrlKeyPayload("?"))
    }

    @Test
    fun nonMappableInputReturnsNull() {
        // 多字符（IME 联想 / 粘贴 / 方向键转义序列）与无对应控制符的字符：调用方原样发送
        assertNull(ctrlKeyPayload(""))
        assertNull(ctrlKeyPayload("ok"))
        assertNull(ctrlKeyPayload("1"))
        assertNull(ctrlKeyPayload("中"))
    }
}
