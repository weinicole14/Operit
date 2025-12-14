package com.ai.assistance.operit.core.tools.defaultTool.debugger

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.core.tools.AppOperationData
import com.ai.assistance.operit.core.tools.NotificationData
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.SystemSettingData
import com.ai.assistance.operit.core.tools.defaultTool.accessbility.AccessibilitySystemOperationTools
import com.ai.assistance.operit.core.tools.system.AndroidShellExecutor
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult

/** 调试级别的系统操作工具，继承无障碍版本, 并使用shell命令覆盖部分实现 */
open class DebuggerSystemOperationTools(context: Context) :
    AccessibilitySystemOperationTools(context) {

    private val TAG = "DebuggerSystemTools"

    override suspend fun modifySystemSetting(tool: AITool): ToolResult {
        val setting = tool.parameters.find { it.name == "setting" }?.value ?: ""
        val value = tool.parameters.find { it.name == "value" }?.value ?: ""
        val namespace = tool.parameters.find { it.name == "namespace" }?.value ?: "system"

        if (setting.isBlank() || value.isBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "必须提供setting和value参数"
            )
        }

        val validNamespaces = listOf("system", "secure", "global")
        if (!validNamespaces.contains(namespace)) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "命名空间必须是以下之一: ${validNamespaces.joinToString(", ")}"
            )
        }

        return try {
            val command = "settings put $namespace $setting $value"
            val result = AndroidShellExecutor.executeShellCommand(command)

            if (result.success) {
                val resultData =
                    SystemSettingData(namespace = namespace, setting = setting, value = value)

                return ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = resultData,
                    error = ""
                )
            } else {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "设置失败: ${result.stderr}"
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "修改系统设置时出错", e)
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "修改系统设置时出错: ${e.message}"
            )
        }
    }

    override suspend fun getSystemSetting(tool: AITool): ToolResult {
        val setting = tool.parameters.find { it.name == "setting" }?.value ?: ""
        val namespace = tool.parameters.find { it.name == "namespace" }?.value ?: "system"

        if (setting.isBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "必须提供setting参数"
            )
        }

        val validNamespaces = listOf("system", "secure", "global")
        if (!validNamespaces.contains(namespace)) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "命名空间必须是以下之一: ${validNamespaces.joinToString(", ")}"
            )
        }

        return try {
            val command = "settings get $namespace $setting"
            val result = AndroidShellExecutor.executeShellCommand(command)

            if (result.success) {
                val resultData =
                    SystemSettingData(
                        namespace = namespace,
                        setting = setting,
                        value = result.stdout.trim()
                    )

                return ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = resultData,
                    error = ""
                )
            } else {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "获取设置失败: ${result.stderr}"
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "获取系统设置时出错", e)
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "获取系统设置时出错: ${e.message}"
            )
        }
    }

    override suspend fun installApp(tool: AITool): ToolResult {
        val apkPath = tool.parameters.find { it.name == "apk_path" }?.value ?: ""

        if (apkPath.isBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "必须提供apk_path参数"
            )
        }

        val existsResult =
            AndroidShellExecutor.executeShellCommand(
                "test -f $apkPath && echo 'exists' || echo 'not exists'"
            )
        if (existsResult.stdout.trim() != "exists") {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "APK文件不存在: $apkPath"
            )
        }

        return try {
            val command = "pm install -r $apkPath"
            val result = AndroidShellExecutor.executeShellCommand(command)

            if (result.success && result.stdout.contains("Success")) {
                val resultData =
                    AppOperationData(
                        operationType = "install",
                        packageName = apkPath,
                        success = true
                    )

                return ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = resultData,
                    error = ""
                )
            } else {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "安装失败: ${result.stderr}"
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "安装应用时出错", e)
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "安装应用时出错: ${e.message}"
            )
        }
    }

    override suspend fun uninstallApp(tool: AITool): ToolResult {
        val packageName = tool.parameters.find { it.name == "package_name" }?.value ?: ""
        val keepData = tool.parameters.find { it.name == "keep_data" }?.value?.toBoolean() ?: false

        if (packageName.isBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "必须提供package_name参数"
            )
        }

        val checkCommand = "pm list packages | grep -c \"$packageName\""
        val checkResult = AndroidShellExecutor.executeShellCommand(checkCommand)

        if (checkResult.stdout.trim() == "0") {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "应用未安装: $packageName"
            )
        }

        return try {
            val command =
                if (keepData) {
                    "pm uninstall -k $packageName"
                } else {
                    "pm uninstall $packageName"
                }

            val result = AndroidShellExecutor.executeShellCommand(command)

            if (result.success && result.stdout.contains("Success")) {
                val details = if (keepData) "(保留数据)" else ""
                val resultData =
                    AppOperationData(
                        operationType = "uninstall",
                        packageName = packageName,
                        success = true,
                        details = details
                    )

                return ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = resultData,
                    error = ""
                )
            } else {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "卸载失败: ${result.stderr}"
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "卸载应用时出错", e)
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "卸载应用时出错: ${e.message}"
            )
        }
    }

    override suspend fun startApp(tool: AITool): ToolResult {
        val packageName = tool.parameters.find { it.name == "package_name" }?.value ?: ""
        val activity = tool.parameters.find { it.name == "activity" }?.value ?: ""

        if (packageName.isBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "必须提供package_name参数"
            )
        }

        return try {
            val command =
                if (activity.isBlank()) {
                    // 使用 am start 命令而不是 monkey，避免修改系统设置（如屏幕旋转）
                    // 通过 Intent 启动应用的主 Activity，让系统自动找到合适的启动器
                    "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $packageName"
                } else {
                    "am start -n $packageName/$activity"
                }

            val result = AndroidShellExecutor.executeShellCommand(command)

            if (result.success) {
                val details = if (activity.isNotBlank()) "活动: $activity" else ""
                val resultData =
                    AppOperationData(
                        operationType = "start",
                        packageName = packageName,
                        success = true,
                        details = details
                    )

                return ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = resultData,
                    error = ""
                )
            } else {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "启动应用失败: ${result.stderr}"
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "启动应用时出错", e)
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "启动应用时出错: ${e.message}"
            )
        }
    }

    override suspend fun stopApp(tool: AITool): ToolResult {
        val packageName = tool.parameters.find { it.name == "package_name" }?.value ?: ""

        if (packageName.isBlank()) {
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "必须提供package_name参数"
            )
        }

        return try {
            val command = "am force-stop $packageName"
            val result = AndroidShellExecutor.executeShellCommand(command)

            if (result.success) {
                val resultData =
                    AppOperationData(
                        operationType = "stop",
                        packageName = packageName,
                        success = true
                    )

                return ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = resultData,
                    error = ""
                )
            } else {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "停止应用失败: ${result.stderr}"
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "停止应用时出错", e)
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "停止应用时出错: ${e.message}"
            )
        }
    }

    override suspend fun getNotifications(tool: AITool): ToolResult {
        val limit = tool.parameters.find { it.name == "limit" }?.value?.toIntOrNull() ?: 10
        val includeOngoing =
            tool.parameters.find { it.name == "include_ongoing" }?.value?.toBoolean() ?: false

        return try {
            val command =
                if (includeOngoing) {
                    "dumpsys notification --noredact | grep -E 'pkg=|text=' | head -${limit * 2}"
                } else {
                    "dumpsys notification --noredact | grep -v 'ongoing' | grep -E 'pkg=|text=' | head -${limit * 2}"
                }

            val result = AndroidShellExecutor.executeShellCommand(command)

            if (result.success) {
                val lines = result.stdout.split("\n")
                val notifications = mutableListOf<NotificationData.Notification>()

                var currentPackage = ""
                var currentText = ""

                for (line in lines) {
                    when {
                        line.contains("pkg=") -> {
                            if (currentPackage.isNotEmpty() && currentText.isNotEmpty()) {
                                notifications.add(
                                    NotificationData.Notification(
                                        packageName = currentPackage,
                                        text = currentText,
                                        timestamp = System.currentTimeMillis()
                                    )
                                )
                                currentText = ""
                            }
                            
                            val pkgMatch = Regex("pkg=(\\S+)").find(line)
                            currentPackage = pkgMatch?.groupValues?.getOrNull(1) ?: ""
                        }
                        line.contains("text=") -> {
                            val textMatch = Regex("text=(.+)").find(line)
                            currentText = textMatch?.groupValues?.getOrNull(1) ?: ""
                        }
                    }
                }
                
                if (currentPackage.isNotEmpty() && currentText.isNotEmpty()) {
                    notifications.add(
                        NotificationData.Notification(
                            packageName = currentPackage,
                            text = currentText,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }

                val resultData =
                    NotificationData(
                        notifications = notifications,
                        timestamp = System.currentTimeMillis()
                    )

                return ToolResult(
                    toolName = tool.name,
                    success = true,
                    result = resultData,
                    error = ""
                )
            } else {
                return ToolResult(
                    toolName = tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "获取通知失败: ${result.stderr}"
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "获取通知时出错", e)
            return ToolResult(
                toolName = tool.name,
                success = false,
                result = StringResultData(""),
                error = "获取通知时出错: ${e.message}"
            )
        }
    }
}
