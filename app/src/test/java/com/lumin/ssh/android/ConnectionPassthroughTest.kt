package com.lumin.ssh.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 透传字段往返保真：PC 端新增的连接字段（autoReconnect 等）经安卓 JSON 序列化
 * 往返后必须原样保留，否则安卓参与同步回写时会整条覆盖并把 PC 配置抹掉。
 */
class ConnectionPassthroughTest {

    @Test
    fun autoReconnectSurvivesJsonRoundTrip() {
        val src = Connection(
            id = "c1",
            name = "srv",
            host = "example.com",
            username = "root",
            autoReconnect = true,
        )
        val json = connectionsToJson(listOf(src))
        val parsed = connectionsFromJson(json)
        assertEquals(1, parsed.size)
        assertTrue(parsed[0].autoReconnect, "autoReconnect=true 应透传保留")
        assertTrue(json.contains("autoReconnect"), "序列化应写出 autoReconnect 字段")
    }

    @Test
    fun autoReconnectDefaultsFalseWhenAbsent() {
        val json = """[{"id":"c2","name":"srv","host":"example.com","username":"root"}]"""
        val parsed = connectionsFromJson(json)
        assertEquals(false, parsed[0].autoReconnect, "缺省时应回落 false")
    }
}