package com.quantlm.yaser.domain.model

/**
 * Prompt templates for single-turn LLM use cases.
 * Inspired by Google AI Edge Gallery's Prompt Lab feature.
 * 
 * Each template provides a specific use case with:
 * - A system context/instruction
 * - A placeholder pattern for user input
 * - Suggested generation parameters
 * 
 * @see <a href="https://github.com/google-ai-edge/gallery">Google AI Edge Gallery</a>
 */
data class PromptTemplate(
    val id: String,
    val name: String,
    val description: String,
    val icon: String, // Emoji icon for display
    val systemPrompt: String,
    val userPromptTemplate: String, // Use {input} as placeholder
    val category: PromptCategory,
    val suggestedTemperature: Float = 0.7f,
    val suggestedMaxTokens: Int = 512
) {
    /**
     * Generate the full prompt by substituting user input
     */
    fun generatePrompt(userInput: String): String {
        return userPromptTemplate.replace("{input}", userInput)
    }
    
    /**
     * Generate full conversation with system prompt
     */
    fun generateFullPrompt(userInput: String): Pair<String, String> {
        return systemPrompt to generatePrompt(userInput)
    }
}

/**
 * Categories for organizing prompt templates
 */
enum class PromptCategory(val displayName: String, val icon: String) {
    WRITING("Writing", "✍️"),
    CODING("Coding", "💻"),
    ANALYSIS("Analysis", "🔍"),
    CREATIVITY("Creativity", "🎨"),
    PRODUCTIVITY("Productivity", "📋"),
    LEARNING("Learning", "📚"),
    FUN("Fun", "🎮")
}

/**
 * Predefined prompt templates for common use cases
 */
object PromptTemplates {
    
    // ========== WRITING TEMPLATES ==========
    
    val SUMMARIZE = PromptTemplate(
        id = "summarize",
        name = "Summarize",
        description = "Condense text into a brief summary",
        icon = "📝",
        systemPrompt = "You are a skilled summarizer. Create concise, accurate summaries that capture the key points.",
        userPromptTemplate = "Summarize the following text in 2-3 sentences:\n\n{input}",
        category = PromptCategory.WRITING,
        suggestedTemperature = 0.3f,
        suggestedMaxTokens = 256
    )
    
    val REWRITE = PromptTemplate(
        id = "rewrite",
        name = "Rewrite",
        description = "Rewrite text to improve clarity and style",
        icon = "🔄",
        systemPrompt = "You are an expert editor. Rewrite text to be clearer, more engaging, and grammatically correct while preserving the original meaning.",
        userPromptTemplate = "Rewrite the following text to improve clarity and style:\n\n{input}",
        category = PromptCategory.WRITING,
        suggestedTemperature = 0.7f,
        suggestedMaxTokens = 512
    )
    
    val EXPAND = PromptTemplate(
        id = "expand",
        name = "Expand",
        description = "Expand a brief idea into detailed content",
        icon = "📖",
        systemPrompt = "You are a creative writer. Take brief ideas and expand them into detailed, well-structured content.",
        userPromptTemplate = "Expand on the following idea with more detail and examples:\n\n{input}",
        category = PromptCategory.WRITING,
        suggestedTemperature = 0.8f,
        suggestedMaxTokens = 1024
    )
    
    val PROOFREAD = PromptTemplate(
        id = "proofread",
        name = "Proofread",
        description = "Check and correct grammar, spelling, and punctuation",
        icon = "✅",
        systemPrompt = "You are a meticulous proofreader. Identify and correct all grammatical errors, spelling mistakes, and punctuation issues.",
        userPromptTemplate = "Proofread and correct the following text:\n\n{input}",
        category = PromptCategory.WRITING,
        suggestedTemperature = 0.1f,
        suggestedMaxTokens = 512
    )
    
    val EMAIL_PROFESSIONAL = PromptTemplate(
        id = "email_professional",
        name = "Professional Email",
        description = "Write a professional email from brief notes",
        icon = "📧",
        systemPrompt = "You are a professional business communicator. Write clear, polite, and effective professional emails.",
        userPromptTemplate = "Write a professional email based on these notes:\n\n{input}",
        category = PromptCategory.WRITING,
        suggestedTemperature = 0.5f,
        suggestedMaxTokens = 512
    )
    
    // ========== CODING TEMPLATES ==========
    
    val EXPLAIN_CODE = PromptTemplate(
        id = "explain_code",
        name = "Explain Code",
        description = "Explain what code does in plain English",
        icon = "💡",
        systemPrompt = "You are a patient programming teacher. Explain code clearly, breaking down complex logic into understandable parts.",
        userPromptTemplate = "Explain what this code does:\n\n```\n{input}\n```",
        category = PromptCategory.CODING,
        suggestedTemperature = 0.3f,
        suggestedMaxTokens = 512
    )
    
    val DEBUG_CODE = PromptTemplate(
        id = "debug_code",
        name = "Debug Code",
        description = "Find and fix bugs in code",
        icon = "🐛",
        systemPrompt = "You are an expert debugger. Analyze code to identify bugs, explain what's wrong, and provide the corrected version.",
        userPromptTemplate = "Find and fix bugs in this code:\n\n```\n{input}\n```",
        category = PromptCategory.CODING,
        suggestedTemperature = 0.2f,
        suggestedMaxTokens = 1024
    )
    
    val OPTIMIZE_CODE = PromptTemplate(
        id = "optimize_code",
        name = "Optimize Code",
        description = "Improve code performance and readability",
        icon = "⚡",
        systemPrompt = "You are a code optimization expert. Improve code for better performance, readability, and maintainability while preserving functionality.",
        userPromptTemplate = "Optimize this code for better performance and readability:\n\n```\n{input}\n```",
        category = PromptCategory.CODING,
        suggestedTemperature = 0.3f,
        suggestedMaxTokens = 1024
    )
    
    val WRITE_TESTS = PromptTemplate(
        id = "write_tests",
        name = "Write Tests",
        description = "Generate unit tests for code",
        icon = "🧪",
        systemPrompt = "You are a test automation expert. Write comprehensive unit tests that cover edge cases and common scenarios.",
        userPromptTemplate = "Write unit tests for this code:\n\n```\n{input}\n```",
        category = PromptCategory.CODING,
        suggestedTemperature = 0.3f,
        suggestedMaxTokens = 1024
    )
    
    val CODE_REVIEW = PromptTemplate(
        id = "code_review",
        name = "Code Review",
        description = "Review code and suggest improvements",
        icon = "👀",
        systemPrompt = "You are a senior code reviewer. Provide constructive feedback on code quality, best practices, potential issues, and suggestions for improvement.",
        userPromptTemplate = "Review this code and provide feedback:\n\n```\n{input}\n```",
        category = PromptCategory.CODING,
        suggestedTemperature = 0.4f,
        suggestedMaxTokens = 1024
    )
    
    // ========== ANALYSIS TEMPLATES ==========
    
    val ANALYZE_SENTIMENT = PromptTemplate(
        id = "analyze_sentiment",
        name = "Sentiment Analysis",
        description = "Analyze the sentiment and tone of text",
        icon = "😊",
        systemPrompt = "You are a sentiment analysis expert. Analyze text to determine its overall sentiment (positive, negative, neutral) and emotional tone.",
        userPromptTemplate = "Analyze the sentiment and tone of this text:\n\n{input}",
        category = PromptCategory.ANALYSIS,
        suggestedTemperature = 0.2f,
        suggestedMaxTokens = 256
    )
    
    val EXTRACT_KEYWORDS = PromptTemplate(
        id = "extract_keywords",
        name = "Extract Keywords",
        description = "Extract key terms and concepts from text",
        icon = "🔑",
        systemPrompt = "You are an information extraction specialist. Identify and extract the most important keywords, phrases, and concepts from text.",
        userPromptTemplate = "Extract the key terms and concepts from this text:\n\n{input}",
        category = PromptCategory.ANALYSIS,
        suggestedTemperature = 0.1f,
        suggestedMaxTokens = 256
    )
    
    val COMPARE_CONTRAST = PromptTemplate(
        id = "compare_contrast",
        name = "Compare & Contrast",
        description = "Compare two or more items",
        icon = "⚖️",
        systemPrompt = "You are an analytical thinker. Provide balanced, thorough comparisons highlighting similarities and differences.",
        userPromptTemplate = "Compare and contrast the following:\n\n{input}",
        category = PromptCategory.ANALYSIS,
        suggestedTemperature = 0.5f,
        suggestedMaxTokens = 512
    )
    
    // ========== CREATIVITY TEMPLATES ==========
    
    val BRAINSTORM = PromptTemplate(
        id = "brainstorm",
        name = "Brainstorm Ideas",
        description = "Generate creative ideas on a topic",
        icon = "💭",
        systemPrompt = "You are a creative brainstorming partner. Generate diverse, innovative ideas without judgment.",
        userPromptTemplate = "Brainstorm 10 creative ideas for:\n\n{input}",
        category = PromptCategory.CREATIVITY,
        suggestedTemperature = 0.9f,
        suggestedMaxTokens = 512
    )
    
    val WRITE_STORY = PromptTemplate(
        id = "write_story",
        name = "Write Story",
        description = "Create a short story from a prompt",
        icon = "📚",
        systemPrompt = "You are a creative storyteller. Write engaging, imaginative stories with vivid descriptions and compelling characters.",
        userPromptTemplate = "Write a short story based on this prompt:\n\n{input}",
        category = PromptCategory.CREATIVITY,
        suggestedTemperature = 0.9f,
        suggestedMaxTokens = 1024
    )
    
    val WRITE_POEM = PromptTemplate(
        id = "write_poem",
        name = "Write Poem",
        description = "Compose a poem on a theme",
        icon = "🎭",
        systemPrompt = "You are a poet. Create evocative poems with rhythm, imagery, and emotional depth.",
        userPromptTemplate = "Write a poem about:\n\n{input}",
        category = PromptCategory.CREATIVITY,
        suggestedTemperature = 0.9f,
        suggestedMaxTokens = 512
    )
    
    // ========== PRODUCTIVITY TEMPLATES ==========
    
    val TODO_LIST = PromptTemplate(
        id = "todo_list",
        name = "Create To-Do List",
        description = "Convert notes into organized tasks",
        icon = "✔️",
        systemPrompt = "You are a productivity expert. Create clear, actionable to-do lists from unstructured notes.",
        userPromptTemplate = "Create an organized to-do list from these notes:\n\n{input}",
        category = PromptCategory.PRODUCTIVITY,
        suggestedTemperature = 0.3f,
        suggestedMaxTokens = 512
    )
    
    val MEETING_NOTES = PromptTemplate(
        id = "meeting_notes",
        name = "Meeting Notes",
        description = "Structure meeting notes with action items",
        icon = "📋",
        systemPrompt = "You are an executive assistant. Organize meeting notes into a clear format with key points, decisions, and action items.",
        userPromptTemplate = "Organize these meeting notes:\n\n{input}",
        category = PromptCategory.PRODUCTIVITY,
        suggestedTemperature = 0.3f,
        suggestedMaxTokens = 512
    )
    
    // ========== LEARNING TEMPLATES ==========
    
    val EXPLAIN_CONCEPT = PromptTemplate(
        id = "explain_concept",
        name = "Explain Concept",
        description = "Explain a complex concept simply",
        icon = "🎓",
        systemPrompt = "You are a patient teacher. Explain complex concepts in simple terms using analogies and examples.",
        userPromptTemplate = "Explain this concept in simple terms:\n\n{input}",
        category = PromptCategory.LEARNING,
        suggestedTemperature = 0.5f,
        suggestedMaxTokens = 512
    )
    
    val QUIZ_ME = PromptTemplate(
        id = "quiz_me",
        name = "Quiz Me",
        description = "Generate quiz questions on a topic",
        icon = "❓",
        systemPrompt = "You are an educational quiz master. Create engaging questions that test understanding of a topic.",
        userPromptTemplate = "Create 5 quiz questions about:\n\n{input}",
        category = PromptCategory.LEARNING,
        suggestedTemperature = 0.6f,
        suggestedMaxTokens = 512
    )
    
    val FLASHCARDS = PromptTemplate(
        id = "flashcards",
        name = "Create Flashcards",
        description = "Generate flashcards for studying",
        icon = "🃏",
        systemPrompt = "You are a study aid creator. Create effective flashcards with clear questions and concise answers.",
        userPromptTemplate = "Create flashcards (Q&A format) for:\n\n{input}",
        category = PromptCategory.LEARNING,
        suggestedTemperature = 0.4f,
        suggestedMaxTokens = 512
    )
    
    // ========== FUN TEMPLATES ==========
    
    val ROAST_ME = PromptTemplate(
        id = "roast_me",
        name = "Roast Me",
        description = "Get a humorous roast (keep it light!)",
        icon = "🔥",
        systemPrompt = "You are a witty comedian. Give a playful, good-natured roast that's funny but not mean-spirited.",
        userPromptTemplate = "Give a friendly roast based on:\n\n{input}",
        category = PromptCategory.FUN,
        suggestedTemperature = 0.9f,
        suggestedMaxTokens = 256
    )
    
    val DAD_JOKE = PromptTemplate(
        id = "dad_joke",
        name = "Dad Joke",
        description = "Generate a groan-worthy dad joke",
        icon = "👨",
        systemPrompt = "You are the king of dad jokes. Create wholesome, pun-filled jokes that make people groan.",
        userPromptTemplate = "Create a dad joke about:\n\n{input}",
        category = PromptCategory.FUN,
        suggestedTemperature = 0.9f,
        suggestedMaxTokens = 128
    )
    
    /**
     * Get all available templates
     */
    fun getAllTemplates(): List<PromptTemplate> = listOf(
        // Writing
        SUMMARIZE, REWRITE, EXPAND, PROOFREAD, EMAIL_PROFESSIONAL,
        // Coding
        EXPLAIN_CODE, DEBUG_CODE, OPTIMIZE_CODE, WRITE_TESTS, CODE_REVIEW,
        // Analysis
        ANALYZE_SENTIMENT, EXTRACT_KEYWORDS, COMPARE_CONTRAST,
        // Creativity
        BRAINSTORM, WRITE_STORY, WRITE_POEM,
        // Productivity
        TODO_LIST, MEETING_NOTES,
        // Learning
        EXPLAIN_CONCEPT, QUIZ_ME, FLASHCARDS,
        // Fun
        ROAST_ME, DAD_JOKE
    )
    
    /**
     * Get templates by category
     */
    fun getTemplatesByCategory(): Map<PromptCategory, List<PromptTemplate>> {
        return getAllTemplates().groupBy { it.category }
    }
    
    /**
     * Get templates for a specific category
     */
    fun getTemplatesForCategory(category: PromptCategory): List<PromptTemplate> {
        return getAllTemplates().filter { it.category == category }
    }
    
    /**
     * Find template by ID
     */
    fun getTemplateById(id: String): PromptTemplate? {
        return getAllTemplates().find { it.id == id }
    }
}
