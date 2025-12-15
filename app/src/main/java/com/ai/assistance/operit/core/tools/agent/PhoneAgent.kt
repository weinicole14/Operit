package com.ai.assistance.operit.core.tools.agent

import android.content.Context
import com.ai.assistance.operit.api.chat.llmprovider.AIService
import com.ai.assistance.operit.core.tools.AppListData
import com.ai.assistance.operit.core.tools.defaultTool.ToolGetter
import com.ai.assistance.operit.core.tools.defaultTool.standard.StandardUITools
import com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.preferences.androidPermissionPreferences
import com.ai.assistance.operit.ui.common.displays.UIAutomationProgressOverlay
import com.ai.assistance.operit.ui.common.displays.VirtualDisplayOverlay
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import android.graphics.BitmapFactory

/** Configuration for the PhoneAgent. */
data class AgentConfig(
    val maxSteps: Int = 20
)

/** Result of a single agent step. */
data class StepResult(
    val success: Boolean,
    val finished: Boolean,
    val action: ParsedAgentAction?,
    val thinking: String?,
    val message: String? = null
)

/** Parsed action from the model's response. */
data class ParsedAgentAction(
    val metadata: String,
    val actionName: String?,
    val fields: Map<String, String>
)

/**
 * AI-powered agent for automating Android phone interactions.
 *
 * The agent uses a vision-language model to understand screen content
 * and decide on actions to complete user tasks.
 */
class PhoneAgent(
    private val context: Context,
    private val config: AgentConfig,
    private val uiService: AIService, // 改为依赖 AIService 接口
    private val actionHandler: ActionHandler
) {
    private var _stepCount = 0
    val stepCount: Int
        get() = _stepCount

    private val _contextHistory = mutableListOf<Pair<String, String>>()
    val contextHistory: List<Pair<String, String>>
        get() = _contextHistory.toList()

    private var pauseFlow: StateFlow<Boolean>? = null

    private suspend fun awaitIfPaused() {
        val flow = pauseFlow ?: return
        while (flow.value) {
            delay(200)
        }
    }

    /**
     * Run the agent to complete a task.
     *
     * @param task Natural language description of the task.
     * @param systemPrompt System prompt for the UI automation agent.
     * @param onStep Optional callback invoked after each step with the StepResult.
     * @return Final message from the agent.
     */
    suspend fun run(
        task: String,
        systemPrompt: String,
        onStep: (suspend (StepResult) -> Unit)? = null,
        isPausedFlow: StateFlow<Boolean>? = null
    ): String {
        reset()
        _contextHistory.add("system" to systemPrompt)
        pauseFlow = isPausedFlow

        // First step with user prompt
        awaitIfPaused()
        var result = _executeStep(task, isFirst = true)
        onStep?.invoke(result)

        if (result.finished) {
            return result.message ?: "Task completed"
        }

        // Continue until finished or max steps reached
        while (_stepCount < config.maxSteps) {
            awaitIfPaused()
            result = _executeStep(null, isFirst = false)
            onStep?.invoke(result)

            if (result.finished) {
                pauseFlow = null
                return result.message ?: "Task completed"
            }
        }

        pauseFlow = null
        return "Max steps reached"
    }

    /** Reset the agent state for a new task. */
    fun reset() {
        _contextHistory.clear()
        _stepCount = 0
    }

    /** Execute a single step of the agent loop. */
    private suspend fun _executeStep(userPrompt: String?, isFirst: Boolean): StepResult {
        _stepCount++

        val screenshotLink = actionHandler.captureScreenshotForAgent()
        val screenInfo = buildString {
            if (screenshotLink != null) {
                appendLine("[SCREENSHOT] Below is the latest screen image:")
                appendLine(screenshotLink)
            } else {
                appendLine("No screenshot available for this step.")
            }
        }.trim()

        val userMessage = if (isFirst) {
            "$userPrompt\n\n$screenInfo"
        } else {
            "** Screen Info **\n\n$screenInfo"
        }

        _contextHistory.add("user" to userMessage)

        // 直接使用传入的、已经为UI_CONTROLLER配置好的AIService实例
        val responseStream = uiService.sendMessage(
            message = userMessage,
            chatHistory = _contextHistory.toList(),
            enableThinking = false, // 保持 false 以获取原始输出
            stream = true,
            preserveThinkInHistory = true // 确保在多步任务中保留完整的上下文
        )

        val contentBuilder = StringBuilder()
        responseStream.collect { chunk -> contentBuilder.append(chunk) }
        val fullResponse = contentBuilder.toString().trim()

        // 对齐官方 Python 客户端：
        // 1. 优先用 finish(message=) / do(action=) 切分思考和动作
        // 2. 没有这些标记时，再回退到 <think>/<answer> 标签解析
        val (thinking, answer) = parseThinkingAndAction(fullResponse)

        // 严格按照官方格式将思考和动作重新组合，然后添加到历史记录中
        // 确保传递给模型的上下文是干净且格式正确的
        val historyEntry = "<think>$thinking</think><answer>$answer</answer>"
        _contextHistory.add("assistant" to historyEntry)

        val parsedAction = parseAgentAction(answer)

        AppLogger.d("PhoneAgent", "Step $_stepCount: metadata=${parsedAction.metadata}, action=${parsedAction.actionName}")

        actionHandler.removeImagesFromLastUserMessage(_contextHistory)

        if (parsedAction.metadata == "finish") {
            val message = parsedAction.fields["message"] ?: "Task finished."
            return StepResult(success = true, finished = true, action = parsedAction, thinking = thinking, message = message)
        }

        if (parsedAction.metadata == "do") {
            awaitIfPaused()
            val execResult = actionHandler.executeAgentAction(parsedAction)
            if (execResult.shouldFinish) {
                 return StepResult(success = execResult.success, finished = true, action = parsedAction, thinking = thinking, message = execResult.message)
            }
            return StepResult(success = execResult.success, finished = false, action = parsedAction, thinking = thinking, message = execResult.message)
        }

        // Unknown action type
        val errorMessage = "Unknown action format: ${parsedAction.metadata}"
        return StepResult(success = false, finished = true, action = parsedAction, thinking = thinking, message = errorMessage)
    }

    private fun extractTagContent(text: String, tag: String): String? {
        val pattern = Regex("""<$tag>(.*?)</$tag>""", RegexOption.DOT_MATCHES_ALL)
        return pattern.find(text)?.groupValues?.getOrNull(1)?.trim()
    }

    /**
     * 将完整回复拆分为思考(thinking)和动作(answer)两部分。
     * 规则与官方 Python ModelClient._parse_response 保持一致：
     * 1. 如果包含 "finish(message="，则其前面是思考，后面(含该标记)是动作；
     * 2. 否则如果包含 "do(action="，则其前面是思考，后面(含该标记)是动作；
     * 3. 否则退回到 <think>/<answer> 标签解析；
     * 4. 再否则，视为没有显式思考，整个内容作为动作。
     */
    private fun parseThinkingAndAction(content: String): Pair<String?, String> {
        val full = content.trim()

        // Rule 1: finish(message=
        val finishMarker = "finish(message="
        val finishIndex = full.indexOf(finishMarker)
        if (finishIndex >= 0) {
            val thinking = full.substring(0, finishIndex).trim().ifEmpty { null }
            val action = full.substring(finishIndex).trim()
            return thinking to action
        }

        // Rule 2: do(action=
        val doMarker = "do(action="
        val doIndex = full.indexOf(doMarker)
        if (doIndex >= 0) {
            val thinking = full.substring(0, doIndex).trim().ifEmpty { null }
            val action = full.substring(doIndex).trim()
            return thinking to action
        }

        // Rule 3: fallback to legacy XML-style tags
        val thinkTag = extractTagContent(full, "think")
        val answerTag = extractTagContent(full, "answer")
        if (thinkTag != null || answerTag != null) {
            return thinkTag to (answerTag ?: full)
        }

        // Rule 4: no markers at all
        return null to full
    }

    private fun parseAgentAction(raw: String): ParsedAgentAction {
        val original = raw.trim()
        val finishIndex = original.lastIndexOf("finish(")
        val doIndex = original.lastIndexOf("do(")
        val startIndex = when {
            finishIndex >= 0 && doIndex >= 0 -> maxOf(finishIndex, doIndex)
            finishIndex >= 0 -> finishIndex
            doIndex >= 0 -> doIndex
            else -> -1
        }

        val trimmed = if (startIndex >= 0) original.substring(startIndex).trim() else original

        if (trimmed.startsWith("finish")) {
            val messageRegex = Regex("""finish\s*\(\s*message\s*=\s*\"(.*)\"\s*\)""", RegexOption.DOT_MATCHES_ALL)
            val message = messageRegex.find(trimmed)?.groupValues?.getOrNull(1) ?: ""
            return ParsedAgentAction(metadata = "finish", actionName = null, fields = mapOf("message" to message))
        }

        if (!trimmed.startsWith("do")) {
            return ParsedAgentAction(metadata = "unknown", actionName = null, fields = emptyMap())
        }

        val inner = trimmed.removePrefix("do").trim().removeSurrounding("(", ")")
        val fields = mutableMapOf<String, String>()
        val regex = Regex("""(\w+)\s*=\s*(?:\[(.*?)\]|\"(.*?)\"|'([^']*)'|([^,)]+))""")
        regex.findAll(inner).forEach { matchResult ->
            val key = matchResult.groupValues[1]
            val value = matchResult.groupValues.drop(2).firstOrNull { it.isNotEmpty() } ?: ""
            fields[key] = value
        }

        return ParsedAgentAction(metadata = "do", actionName = fields["action"], fields = fields)
    }
}

/** Handles the execution of parsed actions. */
class ActionHandler(
    private val context: Context,
    private var screenWidth: Int, // Changed to var
    private var screenHeight: Int, // Changed to var,
    private val toolImplementations: ToolImplementations
) {
    data class ActionExecResult(
        val success: Boolean,
        val shouldFinish: Boolean,
        val message: String?
    )

    suspend fun captureScreenshotForAgent(): String? {
        val screenshotTool = buildScreenshotTool()
        val (screenshotLink, dimensions) = toolImplementations.captureScreenshot(screenshotTool)
        if (dimensions != null) {
            screenWidth = dimensions.first
            screenHeight = dimensions.second
            AppLogger.d("ActionHandler", "Updated screen dimensions from screenshot: w=$screenWidth, h=$screenHeight")
        }
        return screenshotLink
    }

    private fun buildScreenshotTool(): AITool {
        val params = mutableListOf<ToolParameter>()
        try {
            val virtualId = VirtualDisplayManager.getInstance(context).getDisplayId()
            if (virtualId != null) {
                params += ToolParameter("display", virtualId.toString())
            }
        } catch (e: Exception) {
            AppLogger.e("ActionHandler", "Error getting virtual display id for screenshot tool", e)
        }
        return AITool(
            name = "capture_screenshot",
            parameters = params
        )
    }

    fun removeImagesFromLastUserMessage(history: MutableList<Pair<String, String>>) {
        // This is a placeholder for the actual image link removal logic
        val lastUserMessageIndex = history.indexOfLast { it.first == "user" }
        if (lastUserMessageIndex != -1) {
            val (role, content) = history[lastUserMessageIndex]
            if (content.contains("<link type=\"image\"")) {
                val stripped = content.replace(Regex("""<link type=\"image\".*?</link>"""), "").trim()
                history[lastUserMessageIndex] = role to stripped
            }
        }
    }

    suspend fun executeAgentAction(parsed: ParsedAgentAction): ActionExecResult {
        val actionName = parsed.actionName ?: return fail(message = "Missing action name")
        val fields = parsed.fields

        val progressOverlay = UIAutomationProgressOverlay.getInstance(context)
        return try {
            progressOverlay.setOverlayAlpha(0f)
            when (actionName) {
                "Launch" -> {
                    val app = fields["app"]?.takeIf { it.isNotBlank() } ?: return fail(message = "No app name specified for Launch")
                    val packageName = resolveAppPackageName(app)
                    try {
                        val systemTools = ToolGetter.getSystemOperationTools(context)
                        val result = systemTools.startApp(AITool("start_app", listOf(ToolParameter("package_name", packageName))))
                        if (result.success) {
                            ensureVirtualDisplayIfAdbOrHigher()
                            ok()
                        } else {
                            fail(message = result.error ?: "Failed to launch app: $packageName")
                        }
                    } catch (e: Exception) {
                        fail(message = "Exception while launching app $packageName: ${e.message}")
                    }
                }
                "Tap" -> {
                    val element = fields["element"] ?: return fail(message = "No element for Tap")
                    val (x, y) = parseRelativePoint(element) ?: return fail(message = "Invalid coordinates for Tap: $element")
                    val params = withDisplayParam(
                        listOf(
                            ToolParameter("x", x.toString()),
                            ToolParameter("y", y.toString())
                        )
                    )
                    val result = toolImplementations.tap(AITool("tap", params))
                    if (result.success) ok() else fail(message = result.error ?: "Tap failed at ($x,$y)")
                }
                "Type" -> {
                    val text = fields["text"] ?: ""
                    val params = withDisplayParam(listOf(ToolParameter("text", text)))
                    val result = toolImplementations.setInputText(AITool("set_input_text", params))
                    if (result.success) ok() else fail(message = result.error ?: "Set input text failed")
                }
                "Swipe" -> {
                    val start = fields["start"] ?: return fail(message = "Missing swipe start")
                    val end = fields["end"] ?: return fail(message = "Missing swipe end")
                    val (sx, sy) = parseRelativePoint(start) ?: return fail(message = "Invalid swipe start: $start")
                    val (ex, ey) = parseRelativePoint(end) ?: return fail(message = "Invalid swipe end: $end")
                    val params = withDisplayParam(
                        listOf(
                            ToolParameter("start_x", sx.toString()),
                            ToolParameter("start_y", sy.toString()),
                            ToolParameter("end_x", ex.toString()),
                            ToolParameter("end_y", ey.toString())
                        )
                    )
                    val result = toolImplementations.swipe(AITool("swipe", params))
                    if (result.success) ok() else fail(message = result.error ?: "Swipe failed")
                }
                "Back" -> {
                    val params = withDisplayParam(listOf(ToolParameter("key_code", "KEYCODE_BACK")))
                    val result = toolImplementations.pressKey(AITool("press_key", params))
                    if (result.success) ok() else fail(message = result.error ?: "Back key failed")
                }
                "Home" -> {
                    val params = withDisplayParam(listOf(ToolParameter("key_code", "KEYCODE_HOME")))
                    val result = toolImplementations.pressKey(AITool("press_key", params))
                    if (result.success) ok() else fail(message = result.error ?: "Home key failed")
                }
                "Wait" -> {
                    val seconds = fields["duration"]?.replace("seconds", "")?.trim()?.toDoubleOrNull() ?: 1.0
                    delay((seconds * 1000).toLong().coerceAtLeast(0L))
                    ok()
                }
                "Take_over" -> ok(shouldFinish = true, message = fields["message"] ?: "User takeover required")
                else -> fail(message = "Unknown action: $actionName")
            }
        } finally {
            progressOverlay.setOverlayAlpha(1f)
        }
    }

    private fun ensureVirtualDisplayIfAdbOrHigher() {
        try {
            val level = androidPermissionPreferences.getPreferredPermissionLevel() ?: AndroidPermissionLevel.STANDARD
            val isAdbOrHigher = when (level) {
                AndroidPermissionLevel.DEBUGGER,
                AndroidPermissionLevel.ADMIN,
                AndroidPermissionLevel.ROOT -> true
                else -> false
            }

            if (!isAdbOrHigher) {
                return
            }

            val manager = VirtualDisplayManager.getInstance(context)
            val id = manager.ensureVirtualDisplay()
            if (id != null) {
                VirtualDisplayOverlay.getInstance(context).show(id)
                AppLogger.d("ActionHandler", "Virtual display ensured and overlay shown for id=$id")
            } else {
                AppLogger.w("ActionHandler", "Failed to create virtual display at ADB-level permission")
            }
        } catch (e: Exception) {
            AppLogger.e("ActionHandler", "Error ensuring virtual display", e)
        }
    }

    private fun withDisplayParam(params: List<ToolParameter>): List<ToolParameter> {
        return try {
            val id = VirtualDisplayManager.getInstance(context).getDisplayId()
            if (id != null) params + ToolParameter("display", id.toString()) else params
        } catch (e: Exception) {
            AppLogger.e("ActionHandler", "Error getting virtual display id", e)
            params
        }
    }

    private fun ok(shouldFinish: Boolean = false, message: String? = null) = ActionExecResult(true, shouldFinish, message)
    private fun fail(shouldFinish: Boolean = false, message: String) = ActionExecResult(false, shouldFinish, message)

    private fun parseRelativePoint(value: String): Pair<Int, Int>? {
        val parts = value.trim().removeSurrounding("[", "]").split(",").map { it.trim() }
        if (parts.size < 2) return null
        val relX = parts[0].toIntOrNull() ?: return null
        val relY = parts[1].toIntOrNull() ?: return null
        return (relX / 1000.0 * screenWidth).toInt() to (relY / 1000.0 * screenHeight).toInt()
    }

    private fun resolveAppPackageName(app: String): String {
        return StandardUITools.APP_PACKAGES[app]
            ?: StandardUITools.APP_PACKAGES[app.trim()]
            ?: StandardUITools.APP_PACKAGES[app.trim().lowercase(Locale.getDefault())]
            ?: app.trim()
    }
}

/** Interface for providing tool implementations to the ActionHandler. */
interface ToolImplementations {
    suspend fun tap(tool: AITool): ToolResult
    suspend fun longPress(tool: AITool): ToolResult
    suspend fun setInputText(tool: AITool): ToolResult
    suspend fun swipe(tool: AITool): ToolResult
    suspend fun pressKey(tool: AITool): ToolResult
    suspend fun captureScreenshot(tool: AITool): Pair<String?, Pair<Int, Int>?>
}
