package com.quantlm.yaser.domain.model

/**
 * Tool calling / Function calling support for LLM.
 * Inspired by Google AI Edge Gallery's Mobile Actions feature and LiteRT-LM's tool support.
 * 
 * Enables LLMs to call predefined functions to perform actions like:
 * - Device control (flashlight, volume, brightness)
 * - Contacts and communication (call, SMS, email)
 * - Calendar and reminders
 * - Web search and navigation
 * 
 * @see <a href="https://github.com/google-ai-edge/gallery">Google AI Edge Gallery</a>
 * @see <a href="https://github.com/google-ai-edge/LiteRT-LM">LiteRT-LM Tool Support</a>
 */

/**
 * Represents a tool/function that can be called by the LLM
 */
data class Tool(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter>,
    val category: ToolCategory,
    val requiresPermission: String? = null,
    val isEnabled: Boolean = true
) {
    /**
     * Generate the tool schema for the LLM
     */
    fun toSchema(): Map<String, Any> {
        return mapOf(
            "name" to name,
            "description" to description,
            "parameters" to mapOf(
                "type" to "object",
                "properties" to parameters.associate { param ->
                    param.name to mapOf(
                        "type" to param.type.jsonType,
                        "description" to param.description
                    ).let { props ->
                        if (param.enumValues != null) {
                            props + ("enum" to param.enumValues)
                        } else props
                    }
                },
                "required" to parameters.filter { it.required }.map { it.name }
            )
        )
    }
}

/**
 * Parameter for a tool function
 */
data class ToolParameter(
    val name: String,
    val description: String,
    val type: ToolParameterType,
    val required: Boolean = true,
    val enumValues: List<String>? = null,
    val defaultValue: Any? = null
)

/**
 * Types for tool parameters
 */
enum class ToolParameterType(val jsonType: String) {
    STRING("string"),
    INTEGER("integer"),
    FLOAT("number"),
    BOOLEAN("boolean"),
    ARRAY("array")
}

/**
 * Categories for organizing tools
 */
enum class ToolCategory(val displayName: String, val icon: String) {
    DEVICE("Device Control", "📱"),
    COMMUNICATION("Communication", "💬"),
    CALENDAR("Calendar & Reminders", "📅"),
    SEARCH("Search & Navigation", "🔍"),
    MEDIA("Media", "🎵"),
    SYSTEM("System", "⚙️"),
    CUSTOM("Custom", "🔧")
}

/**
 * Result from a tool execution
 */
data class ToolResult(
    val toolName: String,
    val success: Boolean,
    val result: Any? = null,
    val error: String? = null,
    val metadata: Map<String, Any> = emptyMap()
) {
    /**
     * Convert result to string for LLM feedback
     */
    fun toFeedbackString(): String {
        return if (success) {
            "Tool '$toolName' executed successfully. Result: $result"
        } else {
            "Tool '$toolName' failed. Error: $error"
        }
    }
}

/**
 * A tool call request parsed from LLM output
 */
data class ToolCall(
    val name: String,
    val arguments: Map<String, Any>
)

/**
 * Interface for executing tools
 */
interface ToolExecutor {
    /**
     * Execute a tool call
     */
    suspend fun execute(call: ToolCall): ToolResult
    
    /**
     * Check if a tool is available
     */
    fun isAvailable(toolName: String): Boolean
    
    /**
     * Get all available tools
     */
    fun getAvailableTools(): List<Tool>
}

/**
 * Predefined tools for common mobile actions
 */
object MobileTools {
    
    // ========== DEVICE CONTROL TOOLS ==========
    
    val SET_FLASHLIGHT = Tool(
        name = "set_flashlight",
        description = "Turn the device flashlight on or off",
        parameters = listOf(
            ToolParameter(
                name = "enabled",
                description = "Whether to turn the flashlight on (true) or off (false)",
                type = ToolParameterType.BOOLEAN
            )
        ),
        category = ToolCategory.DEVICE,
        requiresPermission = "android.permission.CAMERA"
    )
    
    val SET_BRIGHTNESS = Tool(
        name = "set_brightness",
        description = "Set the screen brightness level",
        parameters = listOf(
            ToolParameter(
                name = "level",
                description = "Brightness level from 0 (minimum) to 100 (maximum)",
                type = ToolParameterType.INTEGER
            )
        ),
        category = ToolCategory.DEVICE,
        requiresPermission = "android.permission.WRITE_SETTINGS"
    )
    
    val SET_VOLUME = Tool(
        name = "set_volume",
        description = "Set the device volume level",
        parameters = listOf(
            ToolParameter(
                name = "level",
                description = "Volume level from 0 (mute) to 100 (maximum)",
                type = ToolParameterType.INTEGER
            ),
            ToolParameter(
                name = "stream",
                description = "Which audio stream to adjust",
                type = ToolParameterType.STRING,
                required = false,
                enumValues = listOf("media", "ring", "alarm", "notification"),
                defaultValue = "media"
            )
        ),
        category = ToolCategory.DEVICE
    )
    
    // ========== COMMUNICATION TOOLS ==========
    
    val MAKE_CALL = Tool(
        name = "make_call",
        description = "Make a phone call to a contact or number",
        parameters = listOf(
            ToolParameter(
                name = "number",
                description = "Phone number to call",
                type = ToolParameterType.STRING
            )
        ),
        category = ToolCategory.COMMUNICATION,
        requiresPermission = "android.permission.CALL_PHONE"
    )
    
    val SEND_SMS = Tool(
        name = "send_sms",
        description = "Send an SMS text message",
        parameters = listOf(
            ToolParameter(
                name = "number",
                description = "Phone number to send the message to",
                type = ToolParameterType.STRING
            ),
            ToolParameter(
                name = "message",
                description = "The message text to send",
                type = ToolParameterType.STRING
            )
        ),
        category = ToolCategory.COMMUNICATION,
        requiresPermission = "android.permission.SEND_SMS"
    )
    
    val COMPOSE_EMAIL = Tool(
        name = "compose_email",
        description = "Compose and open an email draft",
        parameters = listOf(
            ToolParameter(
                name = "to",
                description = "Recipient email address",
                type = ToolParameterType.STRING
            ),
            ToolParameter(
                name = "subject",
                description = "Email subject line",
                type = ToolParameterType.STRING,
                required = false
            ),
            ToolParameter(
                name = "body",
                description = "Email body text",
                type = ToolParameterType.STRING,
                required = false
            )
        ),
        category = ToolCategory.COMMUNICATION
    )
    
    // ========== CALENDAR TOOLS ==========
    
    val CREATE_REMINDER = Tool(
        name = "create_reminder",
        description = "Create a reminder or alarm",
        parameters = listOf(
            ToolParameter(
                name = "title",
                description = "Title or description of the reminder",
                type = ToolParameterType.STRING
            ),
            ToolParameter(
                name = "time",
                description = "Time for the reminder (e.g., '3:00 PM', 'in 2 hours', 'tomorrow 9am')",
                type = ToolParameterType.STRING
            )
        ),
        category = ToolCategory.CALENDAR,
        requiresPermission = "android.permission.SET_ALARM"
    )
    
    val CREATE_CALENDAR_EVENT = Tool(
        name = "create_calendar_event",
        description = "Create a calendar event",
        parameters = listOf(
            ToolParameter(
                name = "title",
                description = "Title of the event",
                type = ToolParameterType.STRING
            ),
            ToolParameter(
                name = "start_time",
                description = "Start time of the event",
                type = ToolParameterType.STRING
            ),
            ToolParameter(
                name = "end_time",
                description = "End time of the event",
                type = ToolParameterType.STRING,
                required = false
            ),
            ToolParameter(
                name = "location",
                description = "Location of the event",
                type = ToolParameterType.STRING,
                required = false
            ),
            ToolParameter(
                name = "description",
                description = "Description or notes for the event",
                type = ToolParameterType.STRING,
                required = false
            )
        ),
        category = ToolCategory.CALENDAR,
        requiresPermission = "android.permission.WRITE_CALENDAR"
    )
    
    // ========== SEARCH & NAVIGATION TOOLS ==========
    
    val WEB_SEARCH = Tool(
        name = "web_search",
        description = "Search the web for information",
        parameters = listOf(
            ToolParameter(
                name = "query",
                description = "Search query",
                type = ToolParameterType.STRING
            )
        ),
        category = ToolCategory.SEARCH
    )
    
    val OPEN_MAP = Tool(
        name = "open_map",
        description = "Open a map to a location or get directions",
        parameters = listOf(
            ToolParameter(
                name = "location",
                description = "Location to show on map or destination for directions",
                type = ToolParameterType.STRING
            ),
            ToolParameter(
                name = "mode",
                description = "Navigation mode",
                type = ToolParameterType.STRING,
                required = false,
                enumValues = listOf("driving", "walking", "transit", "bicycling"),
                defaultValue = "driving"
            )
        ),
        category = ToolCategory.SEARCH
    )
    
    val OPEN_URL = Tool(
        name = "open_url",
        description = "Open a URL in the browser",
        parameters = listOf(
            ToolParameter(
                name = "url",
                description = "The URL to open",
                type = ToolParameterType.STRING
            )
        ),
        category = ToolCategory.SEARCH
    )
    
    // ========== MEDIA TOOLS ==========
    
    val PLAY_MUSIC = Tool(
        name = "play_music",
        description = "Play music or search for a song",
        parameters = listOf(
            ToolParameter(
                name = "query",
                description = "Song, artist, or album to play",
                type = ToolParameterType.STRING
            )
        ),
        category = ToolCategory.MEDIA
    )
    
    val TAKE_PHOTO = Tool(
        name = "take_photo",
        description = "Open the camera to take a photo",
        parameters = listOf(
            ToolParameter(
                name = "camera",
                description = "Which camera to use",
                type = ToolParameterType.STRING,
                required = false,
                enumValues = listOf("front", "back"),
                defaultValue = "back"
            )
        ),
        category = ToolCategory.MEDIA,
        requiresPermission = "android.permission.CAMERA"
    )
    
    // ========== SYSTEM TOOLS ==========
    
    val SET_TIMER = Tool(
        name = "set_timer",
        description = "Set a countdown timer",
        parameters = listOf(
            ToolParameter(
                name = "duration",
                description = "Duration of the timer (e.g., '5 minutes', '1 hour 30 minutes')",
                type = ToolParameterType.STRING
            ),
            ToolParameter(
                name = "label",
                description = "Label for the timer",
                type = ToolParameterType.STRING,
                required = false
            )
        ),
        category = ToolCategory.SYSTEM,
        requiresPermission = "android.permission.SET_ALARM"
    )
    
    val OPEN_APP = Tool(
        name = "open_app",
        description = "Open an application",
        parameters = listOf(
            ToolParameter(
                name = "app_name",
                description = "Name of the app to open",
                type = ToolParameterType.STRING
            )
        ),
        category = ToolCategory.SYSTEM
    )
    
    val GET_WEATHER = Tool(
        name = "get_weather",
        description = "Get current weather information",
        parameters = listOf(
            ToolParameter(
                name = "location",
                description = "Location to get weather for (optional, uses current location if not specified)",
                type = ToolParameterType.STRING,
                required = false
            )
        ),
        category = ToolCategory.SYSTEM
    )
    
    /**
     * Get all available mobile tools
     */
    fun getAllTools(): List<Tool> = listOf(
        // Device
        SET_FLASHLIGHT, SET_BRIGHTNESS, SET_VOLUME,
        // Communication
        MAKE_CALL, SEND_SMS, COMPOSE_EMAIL,
        // Calendar
        CREATE_REMINDER, CREATE_CALENDAR_EVENT,
        // Search & Navigation
        WEB_SEARCH, OPEN_MAP, OPEN_URL,
        // Media
        PLAY_MUSIC, TAKE_PHOTO,
        // System
        SET_TIMER, OPEN_APP, GET_WEATHER
    )
    
    /**
     * Get tools by category
     */
    fun getToolsByCategory(): Map<ToolCategory, List<Tool>> {
        return getAllTools().groupBy { it.category }
    }
    
    /**
     * Find a tool by name
     */
    fun getToolByName(name: String): Tool? {
        return getAllTools().find { it.name == name }
    }
    
    /**
     * Generate system prompt describing available tools
     */
    fun generateToolsSystemPrompt(tools: List<Tool> = getAllTools()): String {
        return buildString {
            appendLine("You have access to the following tools to help the user:")
            appendLine()
            tools.forEach { tool ->
                appendLine("- ${tool.name}: ${tool.description}")
                if (tool.parameters.isNotEmpty()) {
                    appendLine("  Parameters:")
                    tool.parameters.forEach { param ->
                        val required = if (param.required) "(required)" else "(optional)"
                        appendLine("    - ${param.name} $required: ${param.description}")
                    }
                }
                appendLine()
            }
            appendLine()
            appendLine("To use a tool, respond with a JSON object like:")
            appendLine("""{"tool": "tool_name", "arguments": {"param1": "value1"}}""")
            appendLine()
            appendLine("Only use tools when the user's request clearly requires one.")
        }
    }
}

/**
 * Parser for extracting tool calls from LLM responses
 */
object ToolCallParser {
    private val toolCallRegex = Regex(
        """\{[^{}]*"tool"\s*:\s*"([^"]+)"[^{}]*"arguments"\s*:\s*\{([^{}]*)\}[^{}]*\}""",
        RegexOption.DOT_MATCHES_ALL
    )
    
    /**
     * Parse a tool call from LLM output
     */
    fun parseToolCall(text: String): ToolCall? {
        val match = toolCallRegex.find(text) ?: return null
        
        try {
            val toolName = match.groupValues[1]
            val argsString = match.groupValues[2]
            
            // Parse simple key-value pairs from arguments
            val arguments = mutableMapOf<String, Any>()
            val argRegex = Regex(""""([^"]+)"\s*:\s*(?:"([^"]*)"|(\d+(?:\.\d+)?)|(\w+))""")
            argRegex.findAll(argsString).forEach { argMatch ->
                val key = argMatch.groupValues[1]
                val stringValue = argMatch.groupValues[2]
                val numberValue = argMatch.groupValues[3]
                val boolValue = argMatch.groupValues[4]
                
                val value: Any = when {
                    stringValue.isNotEmpty() -> stringValue
                    numberValue.isNotEmpty() -> {
                        if (numberValue.contains(".")) numberValue.toDouble()
                        else numberValue.toInt()
                    }
                    boolValue.equals("true", ignoreCase = true) -> true
                    boolValue.equals("false", ignoreCase = true) -> false
                    else -> boolValue
                }
                
                arguments[key] = value
            }
            
            return ToolCall(toolName, arguments)
        } catch (e: Exception) {
            return null
        }
    }
    
    /**
     * Check if text contains a tool call
     */
    fun containsToolCall(text: String): Boolean {
        return toolCallRegex.containsMatchIn(text)
    }
    
    /**
     * Extract the text before a tool call (for display)
     */
    fun extractTextBeforeToolCall(text: String): String {
        val match = toolCallRegex.find(text) ?: return text
        return text.substring(0, match.range.first).trim()
    }
}
