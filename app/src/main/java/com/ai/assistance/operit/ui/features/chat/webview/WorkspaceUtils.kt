package com.ai.assistance.operit.ui.features.chat.webview

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.IOException

fun createAndGetDefaultWorkspace(context: Context, chatId: String): File {
    // 首先检查是否存在旧的外部存储工作区
    val legacyWorkspacePath = getLegacyWorkspacePath(chatId)
    val legacyWorkspaceDir = File(legacyWorkspacePath)
    
    if (legacyWorkspaceDir.exists() && legacyWorkspaceDir.isDirectory) {
        // 如果旧工作区存在，继续使用它（保持向后兼容）
        LocalWebServer.createDefaultIndexHtmlIfNeeded(legacyWorkspaceDir)
        return legacyWorkspaceDir
    }
    
    // 否则创建新的内部存储工作区
    val workspacePath = getWorkspacePath(context, chatId)
    ensureWorkspaceDirExists(workspacePath)

    val webContentDir = File(workspacePath)

    // Reuse the createDefaultIndexHtmlIfNeeded logic from LocalWebServer
    LocalWebServer.createDefaultIndexHtmlIfNeeded(webContentDir)

    return webContentDir
}

/**
 * 获取工作区路径（新位置：内部存储）
 * 路径: /data/data/com.ai.assistance.operit/files/workspace/{chatId}
 */
fun getWorkspacePath(context: Context, chatId: String): String {
    return File(context.filesDir, "workspace/$chatId").absolutePath
}

/**
 * 获取旧的工作区路径（外部存储）
 * 路径: /sdcard/Download/Operit/workspace/{chatId}
 */
fun getLegacyWorkspacePath(chatId: String): String {
    val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    return "$downloadDir/Operit/workspace/$chatId"
}

fun ensureWorkspaceDirExists(path: String): File {
    val workspaceDir = File(path)
    if (!workspaceDir.exists()) {
        workspaceDir.mkdirs()
    }
    return workspaceDir
}