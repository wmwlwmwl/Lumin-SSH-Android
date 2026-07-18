package com.lumin.ssh.android

sealed interface AppScreen {
    data object Home : AppScreen
    data object Settings : AppScreen
    data object SyncSettings : AppScreen
    data object About : AppScreen
    data object QuickCommands : AppScreen
    data object Credentials : AppScreen
    data object ProxyNodes : AppScreen
    /** null = 新增；非 null = 编辑该服务器 */
    data class ConnectionEdit(val connection: Connection?) : AppScreen
    data class Terminal(val connection: Connection, val backgroundSessionId: String?) : AppScreen
}
