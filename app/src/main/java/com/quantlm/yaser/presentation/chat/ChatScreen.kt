@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.quantlm.yaser.presentation.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.quantlm.yaser.domain.model.DownloadableModel
import com.quantlm.yaser.domain.model.DownloadState
import com.quantlm.yaser.domain.model.GenerationState
import com.quantlm.yaser.domain.model.GenerationStats
import com.quantlm.yaser.domain.model.isVisionModel
import com.quantlm.yaser.domain.model.Message
import com.quantlm.yaser.domain.model.PromptCategory
import com.quantlm.yaser.domain.model.PromptTemplate
import com.quantlm.yaser.domain.model.PromptTemplates
import com.quantlm.yaser.domain.model.ToolCall
import com.quantlm.yaser.domain.model.ToolResult
import com.quantlm.yaser.domain.model.MobileTools
import com.quantlm.yaser.domain.model.VisionQuickAction
import com.quantlm.yaser.domain.model.WebSourceRef
import com.quantlm.yaser.domain.tts.TtsVoiceProfile
import com.quantlm.yaser.data.audio.AudioInputState
import com.quantlm.yaser.presentation.models.ModelDownloadViewModel
import com.quantlm.yaser.presentation.modern.ModernDateSeparator
import androidx.compose.foundation.gestures.animateScrollBy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.Locale

private sealed class MessageSegment {
    data class Text(val text: String) : MessageSegment()
    data class Code(val language: String?, val code: String) : MessageSegment()
}

private sealed class TextBlock {
    data class Heading(val level: Int, val content: String) : TextBlock()
    data class Paragraph(val content: String) : TextBlock()
    data class BulletList(val items: List<String>) : TextBlock()
    data class OrderedList(val items: List<String>, val startIndex: Int) : TextBlock()
    data class BlockQuote(val content: String) : TextBlock()
    object HorizontalRule : TextBlock()
}

private const val LINK_TAG = "link-tag"

// Fix [1.3]: single tag for Log.* in this file (replaces string literals / printStackTrace)
private const val TAG = "ChatScreen"
private const val EMAIL_TAG = "email-tag"

private enum class PendingVisionAction {
    PHOTOS,
    CAMERA
}

private val urlRegex = Regex("((https?://)|(www\\.))[-A-Za-z0-9+&@#/%?=~_|!:,.;]*[-A-Za-z0-9+&@#/%=~_|]")
private val emailRegex = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

private fun IntRange.overlaps(other: IntRange): Boolean {
    return first <= other.last && other.first <= last
}

private fun isSameLocalDay(a: Long, b: Long): Boolean {
    val cal = Calendar.getInstance()
    cal.timeInMillis = a
    val ya = cal.get(Calendar.YEAR)
    val da = cal.get(Calendar.DAY_OF_YEAR)
    cal.timeInMillis = b
    val yb = cal.get(Calendar.YEAR)
    val db = cal.get(Calendar.DAY_OF_YEAR)
    return ya == yb && da == db
}

// Helper function to find closing marker for italic text, ensuring it's not part of double markers
private fun findClosingMarker(text: String, startIndex: Int, marker: Char): Int {
    var i = startIndex
    while (i < text.length) {
        if (text[i] == marker) {
            // Make sure it's not a double marker (** or __)
            val isDouble = (i + 1 < text.length && text[i + 1] == marker)
            if (!isDouble) {
                return i
            }
            // Skip the double marker
            i += 2
            continue
        }
        i++
    }
    return -1
}

// Preprocess text to convert HTML tags to markdown-friendly format
private fun preprocessText(text: String): String {
    var result = text
    
    // Convert HTML line breaks to newlines
    result = result.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
    
    // Convert HTML anchor tags to markdown links: <a href="url">text</a> -> [text](url)
    result = result.replace(Regex("<a\\s+href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", RegexOption.IGNORE_CASE), "[$2]($1)")
    
    // Convert HTML bold tags to markdown
    result = result.replace(Regex("<b>(.*?)</b>", RegexOption.IGNORE_CASE), "**$1**")
    result = result.replace(Regex("<strong>(.*?)</strong>", RegexOption.IGNORE_CASE), "**$1**")
    
    // Convert HTML italic tags to markdown
    result = result.replace(Regex("<i>(.*?)</i>", RegexOption.IGNORE_CASE), "*$1*")
    result = result.replace(Regex("<em>(.*?)</em>", RegexOption.IGNORE_CASE), "*$1*")
    
    // Convert HTML strikethrough tags to markdown
    result = result.replace(Regex("<s>(.*?)</s>", RegexOption.IGNORE_CASE), "~~$1~~")
    result = result.replace(Regex("<strike>(.*?)</strike>", RegexOption.IGNORE_CASE), "~~$1~~")
    result = result.replace(Regex("<del>(.*?)</del>", RegexOption.IGNORE_CASE), "~~$1~~")
    
    // Convert HTML underline to markdown (display as italic since MD doesn't have underline)
    result = result.replace(Regex("<u>(.*?)</u>", RegexOption.IGNORE_CASE), "*$1*")
    
    // Convert HTML code tags to markdown
    result = result.replace(Regex("<code>(.*?)</code>", RegexOption.IGNORE_CASE), "`$1`")
    
    // Convert HTML horizontal rule to markdown
    result = result.replace(Regex("<hr\\s*/?>", RegexOption.IGNORE_CASE), "\n---\n")
    
    // Convert HTML paragraph tags
    result = result.replace(Regex("<p>", RegexOption.IGNORE_CASE), "\n")
    result = result.replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
    
    // Convert HTML list items (basic support)
    result = result.replace(Regex("<li>(.*?)</li>", RegexOption.IGNORE_CASE), "- $1\n")
    result = result.replace(Regex("<ul>|</ul>|<ol>|</ol>", RegexOption.IGNORE_CASE), "\n")
    
    // Clean up multiple consecutive newlines (max 2)
    result = result.replace(Regex("\n{3,}"), "\n\n")
    
    return result.trim()
}

private fun parseMessageContent(text: String): List<MessageSegment> {
    val preprocessed = preprocessText(text)
    if (!preprocessed.contains("```")) {
        return listOf(MessageSegment.Text(preprocessed))
    }
    val segments = mutableListOf<MessageSegment>()
    val regex = Regex("```([\\w+-]*)\\s*\n([\\s\\S]*?)```", RegexOption.MULTILINE)
    var lastIndex = 0
    regex.findAll(preprocessed).forEach { matchResult ->
        val start = matchResult.range.first
        if (start > lastIndex) {
            val preceding = preprocessed.substring(lastIndex, start)
            if (preceding.isNotEmpty()) {
                segments.add(MessageSegment.Text(preceding))
            }
        }
        val language = matchResult.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
        val code = matchResult.groupValues.getOrNull(2)?.replace("\r\n", "\n") ?: ""
        segments.add(MessageSegment.Code(language, code))
        lastIndex = matchResult.range.last + 1
    }
    if (lastIndex < preprocessed.length) {
        val remaining = preprocessed.substring(lastIndex)
        if (remaining.isNotEmpty()) {
            segments.add(MessageSegment.Text(remaining))
        }
    }
    if (segments.isEmpty()) {
        segments.add(MessageSegment.Text(preprocessed))
    }
    return segments
}

private fun parseTextBlocks(text: String): List<TextBlock> {
    val blocks = mutableListOf<TextBlock>()
    val lines = text.lines()
    var index = 0
    val orderedRegex = Regex("^(\\d+)\\.\\s+")
    val horizontalRuleRegex = Regex("^(-{3,}|\\*{3,}|_{3,})$")

    while (index < lines.size) {
        val rawLine = lines[index]
        if (rawLine.isBlank()) {
            index++
            continue
        }

        val trimmed = rawLine.trim()

        // Horizontal rule detection
        if (horizontalRuleRegex.matches(trimmed)) {
            blocks.add(TextBlock.HorizontalRule)
            index++
            continue
        }

        if (trimmed.startsWith("#")) {
            val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 6)
            val content = trimmed.drop(level).trim()
            if (content.isNotEmpty()) {
                blocks.add(TextBlock.Heading(level, content))
            }
            index++
            continue
        }

        // Block quote detection
        if (trimmed.startsWith("> ") || trimmed == ">") {
            val quoteLines = mutableListOf<String>()
            while (index < lines.size) {
                val current = lines[index].trim()
                if (current.startsWith("> ")) {
                    quoteLines.add(current.drop(2).trim())
                    index++
                } else if (current == ">") {
                    quoteLines.add("")
                    index++
                } else if (current.isBlank()) {
                    index++
                    break
                } else {
                    break
                }
            }
            if (quoteLines.isNotEmpty()) {
                blocks.add(TextBlock.BlockQuote(quoteLines.joinToString("\n")))
            }
            continue
        }

        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
            val items = mutableListOf<String>()
            while (index < lines.size) {
                val current = lines[index].trim()
                if (current.startsWith("- ") || current.startsWith("* ") || current.startsWith("+ ")) {
                    items.add(current.drop(2).trim())
                    index++
                } else if (current.isBlank()) {
                    index++
                    break
                } else {
                    break
                }
            }
            if (items.isNotEmpty()) {
                blocks.add(TextBlock.BulletList(items))
            }
            continue
        }

        val orderedMatch = orderedRegex.find(trimmed)
        if (orderedMatch != null) {
            val items = mutableListOf<String>()
            val startIndex = orderedMatch.groupValues[1].toIntOrNull() ?: 1
            var cursor = index
            while (cursor < lines.size) {
                val currentTrimmed = lines[cursor].trim()
                val match = orderedRegex.find(currentTrimmed)
                if (match != null) {
                    val content = currentTrimmed.substring(match.value.length).trim()
                    if (content.isNotEmpty()) {
                        items.add(content)
                    }
                    cursor++
                } else if (currentTrimmed.isBlank()) {
                    cursor++
                    break
                } else {
                    break
                }
            }
            if (items.isNotEmpty()) {
                blocks.add(TextBlock.OrderedList(items, startIndex))
            }
            index = cursor
            continue
        }

        val paragraphLines = mutableListOf<String>()
        while (index < lines.size && lines[index].isNotBlank()) {
            paragraphLines.add(lines[index])
            index++
        }
        if (paragraphLines.isNotEmpty()) {
            blocks.add(TextBlock.Paragraph(paragraphLines.joinToString("\n")))
        }
    }

    return blocks
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatThreadScaffold(
    viewModel: ChatViewModel,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToSystemLogs: () -> Unit = {},
    // Phase 2 (§3.5): chooser "Download" action routes here, optionally with a
    // pre-selected model id to scroll to / pre-expand in the download screen.
    onNavigateToDownloads: (preselectModelId: String?) -> Unit = { _ -> },
    topBar: @Composable (
        onOpenHistory: () -> Unit,
        onNewChat: () -> Unit,
        onClearHistory: () -> Unit
    ) -> Unit,
    bubbleStyle: MessageBubbleStyle = MessageBubbleStyle.Classic,
    enableConversationListDialog: Boolean = true,
    useModernComposer: Boolean = false
) {
    val context = LocalContext.current
    val modelDownloadViewModel: ModelDownloadViewModel = hiltViewModel()
    val clipboardManager = LocalClipboardManager.current
    val messages by viewModel.messages.collectAsState()
    // Narrowed reads: collecting the raw GenerationState here meant the whole
    // scaffold's snapshot scope was invalidated on every streaming-token
    // emission (~2.5×/sec). These derived flows only emit when the *predicate*
    // flips, so the parent body recomposes once per state transition. The
    // streaming text itself is read inside [StreamingAutoScroll] and the
    // generating bubble — both narrow children that own their own recompositions.
    val isGenerating by remember(viewModel) {
        viewModel.generationState
            .map { it is GenerationState.Generating }
            .distinctUntilChanged()
    }.collectAsState(initial = false)
    val isLoadingOrSearching by remember(viewModel) {
        viewModel.generationState
            .map {
                // Any pre-token "we're working on it" phase. Includes the new
                // audio / vision / reasoning indicators so input-disable, stop
                // button, and any other gates that observed loading-or-searching
                // continue to fire correctly throughout the prefill window.
                it is GenerationState.Loading ||
                    it is GenerationState.SearchingWeb ||
                    it is GenerationState.TranscribingAudio ||
                    it is GenerationState.AnalyzingImage ||
                    it is GenerationState.PreparingReasoning
            }
            .distinctUntilChanged()
    }.collectAsState(initial = false)
    val inputText by viewModel.inputText.collectAsState()
    val loadedModel by viewModel.loadedModel.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val currentConversationId by viewModel.currentConversationId.collectAsState()
    val selectedImages by viewModel.selectedImages.collectAsState()
    val selectedAudio by viewModel.selectedAudio.collectAsState()
    val activeQuickAction by viewModel.activeQuickAction.collectAsState()
    val selectedTemplate by viewModel.selectedTemplate.collectAsState()
    val isToolCallingEnabled by viewModel.isToolCallingEnabled.collectAsState()
    val pendingToolCall by viewModel.pendingToolCall.collectAsState()
    val toolResult by viewModel.toolResult.collectAsState()
    val isVisionSupported by viewModel.isVisionSupportedFlow.collectAsState()
    val visionModelState by viewModel.visionModelState.collectAsState()
    val isLoadingVisionModel by viewModel.isLoadingVisionModel.collectAsState()
    val modelDownloadStates by modelDownloadViewModel.downloadStates.collectAsState()
    val audioInputState by viewModel.audioInputManager.audioInputState.collectAsState()
    val generationSettings by viewModel.generationSettings.collectAsState()
    val loadedModelCapabilities by viewModel.loadedModelCapabilities.collectAsState()
    val liveThinkingContent by viewModel.liveThinkingContent.collectAsState()
    val liveThoughtSummary by viewModel.liveThoughtSummary.collectAsState()
    val listState = rememberLazyListState()
    var autoScrollEnabled by remember { mutableStateOf(true) }
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            if (layoutInfo.totalItemsCount == 0) {
                true
            } else {
                val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisibleItemIndex >= layoutInfo.totalItemsCount - 1
            }
        }
    }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    
    // Debug log vision state
    LaunchedEffect(isVisionSupported) {
        Log.d(TAG, "isVisionSupported changed to: $isVisionSupported")
    }
    
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var showVisionModelDialog by remember { mutableStateOf(false) }
    // Phase 2 (§3.5 / §4.4): generalized capability chooser sheet target.
    var pendingCapabilityChooser by remember {
        mutableStateOf<com.quantlm.yaser.domain.model.ModelCapability?>(null)
    }
    // Phase 2 (§3.8): pending audio-source picker. Skill manager bottom sheet trigger
    // lives in ChatViewModel (Sub-phase F) — exposed via the composer toggle.
    var showAudioSourceDialog by remember { mutableStateOf(false) }
    var showAudioRecorderDialog by remember { mutableStateOf(false) }
    var showSkillManagerSheet by remember { mutableStateOf(false) }
    var showImageSourceDialog by remember { mutableStateOf(false) }
    var pendingVisionAction by remember { mutableStateOf<PendingVisionAction?>(null) }
    var pendingVisionDownloadModelId by remember { mutableStateOf<String?>(null) }
    var messageSheetMessage by remember { mutableStateOf<Message?>(null) }
    var editMessage by remember { mutableStateOf<Message?>(null) }
    var editDraft by remember { mutableStateOf("") }
    
    // Camera capture file URI
    var cameraImageUri by remember { mutableStateOf<Uri?>(null) }
    var pendingCameraLaunch by remember { mutableStateOf(false) }
    
    // Camera permission launcher
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission granted, launch camera
            val uri = createImageUri(context)
            cameraImageUri = uri
            pendingCameraLaunch = true
        } else {
            Toast.makeText(context, "Camera permission is required to take photos", Toast.LENGTH_SHORT).show()
        }
    }
    
    // Image picker launcher (gallery)
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { 
            // Copy image to app's internal storage and get path
            val imagePath = copyImageToInternalStorage(context, it)
            imagePath?.let { path -> viewModel.addImage(path) }
        }
    }
    
    // Multi-image picker launcher (legacy SAF / file manager)
    val multiImagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        uris.take(ChatViewModel.MAX_IMAGES - selectedImages.size).forEach { uri ->
            val imagePath = copyImageToInternalStorage(context, uri)
            imagePath?.let { path -> viewModel.addImage(path) }
        }
    }

    // Fix [3.1][3.3]: API 33+ Photo Picker — respects partial / full photo access without broad storage permission
    val photoPickerMultipleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(
            maxItems = ChatViewModel.MAX_IMAGES
        )
    ) { uris: List<Uri> ->
        uris.take(ChatViewModel.MAX_IMAGES - selectedImages.size).forEach { uri ->
            val imagePath = copyImageToInternalStorage(context, uri)
            imagePath?.let { path -> viewModel.addImage(path) }
        }
    }
    
    // Audio file picker launcher (Audio Scribe — pick a file)
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.pickAudioFile(it) }
    }

    // Microphone permission launcher for the Audio Scribe recorder
    val recorderPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            showAudioRecorderDialog = true
        } else {
            Toast.makeText(context, "Microphone permission is required to record audio", Toast.LENGTH_SHORT).show()
        }
    }

    // Camera launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success) {
            cameraImageUri?.let { uri ->
                val imagePath = copyImageToInternalStorage(context, uri)
                imagePath?.let { path -> viewModel.addImage(path) }
            }
        }
    }
    
    // Launch camera when permission is granted and pending
    LaunchedEffect(pendingCameraLaunch, cameraImageUri) {
        if (pendingCameraLaunch && cameraImageUri != null) {
            cameraLauncher.launch(cameraImageUri!!)
            pendingCameraLaunch = false
        }
    }
    
    // Function to request camera permission or launch camera if already granted
    val launchCameraWithPermission: () -> Unit = {
        when {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Permission already granted, launch camera directly
                val uri = createImageUri(context)
                cameraImageUri = uri
                uri?.let { cameraLauncher.launch(it) }
            }
            else -> {
                // Request permission
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }
    
    // Handler for image button click
    val handleImageClick: () -> Unit = {
        showImageSourceDialog = true
    }

    val launchPhotoSelection: () -> Unit = {
        if (selectedImages.size < ChatViewModel.MAX_IMAGES) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                photoPickerMultipleLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            } else {
                multiImagePickerLauncher.launch("image/*")
            }
        }
    }

    val continueAfterVisionReady: () -> Unit = {
        when (pendingVisionAction) {
            PendingVisionAction.PHOTOS -> launchPhotoSelection()
            PendingVisionAction.CAMERA -> launchCameraWithPermission()
            null -> imagePickerLauncher.launch("image/*")
        }
        pendingVisionAction = null
    }

    val visionModelsById = remember {
        com.quantlm.yaser.domain.model.AvailableModels.getAllModels()
            .filter { it.isVisionModel }
            .associateBy { it.id }
    }

    val pendingVisionDownloadState = pendingVisionDownloadModelId?.let { modelDownloadStates[it] }
    LaunchedEffect(pendingVisionDownloadModelId, pendingVisionDownloadState) {
        val pendingModelId = pendingVisionDownloadModelId ?: return@LaunchedEffect
        when (val state = pendingVisionDownloadState) {
            is DownloadState.Success -> {
                pendingVisionDownloadModelId = null
                val downloadedModel = visionModelsById[pendingModelId] ?: return@LaunchedEffect
                viewModel.refreshVisionModelState()
                viewModel.switchToVisionModel(downloadedModel) { success ->
                    if (success) {
                        showVisionModelDialog = false
                        continueAfterVisionReady()
                    } else {
                        Toast.makeText(context, "Download complete, but model load failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            is DownloadState.Error -> {
                pendingVisionDownloadModelId = null
                Toast.makeText(
                    context,
                    state.message.ifBlank { "Vision model download failed" },
                    Toast.LENGTH_LONG
                ).show()
            }
            is DownloadState.Cancelled -> {
                pendingVisionDownloadModelId = null
                Toast.makeText(context, "Vision model download cancelled", Toast.LENGTH_SHORT).show()
            }
            else -> Unit
        }
    }
    
    // Audio permission launcher
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission granted, start listening
            viewModel.audioInputManager.startListening(
                onResult = { text -> 
                    viewModel.setInputText(text)
                    viewModel.audioInputManager.resetState()
                },
                onError = { error ->
                    Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                    viewModel.audioInputManager.resetState()
                }
            )
        } else {
            Toast.makeText(context, "Microphone permission is required for voice input", Toast.LENGTH_SHORT).show()
        }
    }
    
    // Handler for microphone button click
    val handleMicClick: () -> Unit = {
        when {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Permission already granted, start listening
                viewModel.audioInputManager.startListening(
                    onResult = { text -> 
                        viewModel.setInputText(text)
                        viewModel.audioInputManager.resetState()
                    },
                    onError = { error ->
                        Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                        viewModel.audioInputManager.resetState()
                    }
                )
            }
            else -> {
                // Request permission
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
    
    val handleMicStop: () -> Unit = {
        viewModel.audioInputManager.stopListening()
    }

    val openHistoryMenu: () -> Unit = {
        if (enableConversationListDialog) {
            drawerScope.launch { drawerState.open() }
        }
    }

    val closeHistoryMenu: () -> Unit = {
        if (drawerState.isOpen) {
            drawerScope.launch { drawerState.close() }
        }
    }
    
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            // If user manually scrolls away from the bottom, stop pinning to bottom.
            autoScrollEnabled = false
        }
    }

    LaunchedEffect(isAtBottom) {
        if (isAtBottom) {
            autoScrollEnabled = true
        }
    }

    // Auto-scroll to bottom when new messages arrive (only if user is pinned to bottom).
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && autoScrollEnabled) {
            listState.scrollToItem((messages.size - 1).coerceAtLeast(0))
        }
    }

    // Owns its own generationState read so per-token emissions don't invalidate
    // the parent scaffold. Also scrolls to keep the *bottom* of the streaming
    // bubble in view (the prior scrollToItem pinned its top, so the user saw the
    // beginning of the growing bubble rather than the latest tokens).
    StreamingAutoScroll(
        viewModel = viewModel,
        listState = listState,
        autoScrollEnabled = autoScrollEnabled,
        hasMessages = messages.isNotEmpty(),
    )
    
    // Image source selection dialog
    if (showImageSourceDialog) {
        ImageSourceDialog(
            onDismiss = { showImageSourceDialog = false },
            onPhotosSelect = {
                showImageSourceDialog = false
                if (!isVisionSupported) {
                    pendingVisionAction = PendingVisionAction.PHOTOS
                    viewModel.refreshVisionModelState()
                    showVisionModelDialog = true
                    return@ImageSourceDialog
                }
                launchPhotoSelection()
            },
            onCameraCapture = {
                showImageSourceDialog = false
                if (!isVisionSupported) {
                    pendingVisionAction = PendingVisionAction.CAMERA
                    viewModel.refreshVisionModelState()
                    showVisionModelDialog = true
                    return@ImageSourceDialog
                }
                launchCameraWithPermission()
            },
            canAddMore = selectedImages.size < ChatViewModel.MAX_IMAGES
        )
    }
    
    // Vision model dialog
    if (showVisionModelDialog) {
        VisionModelDialog(
            visionModelState = visionModelState,
            downloadStates = modelDownloadStates,
            isLoading = isLoadingVisionModel,
            onDismiss = {
                showVisionModelDialog = false
                pendingVisionAction = null
                pendingVisionDownloadModelId = null
            },
            onSwitchToVisionModel = { selectedModel ->
                viewModel.switchToVisionModel(selectedModel) { success ->
                    if (success) {
                        showVisionModelDialog = false
                        continueAfterVisionReady()
                    } else {
                        Toast.makeText(context, "Failed to load selected vision model", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDownloadVisionModel = { selectedModel ->
                pendingVisionDownloadModelId = selectedModel.id
                val existingState = modelDownloadStates[selectedModel.id]
                if (existingState !is DownloadState.Downloading && existingState !is DownloadState.Queued) {
                    modelDownloadViewModel.enqueueDownload(selectedModel)
                }
            }
        )
    }
    
    // Phase 2 (§3.5 / §4.4): capability-restricted model chooser. Lists both
    // already-downloaded models that declare the capability and ones still
    // available to download. "Use" reuses [ChatViewModel.switchToModelForCapability];
    // "Download" navigates to the existing download flow.
    val pendingCapability = pendingCapabilityChooser
    val isLoadingCapabilityModel by viewModel.isLoadingCapabilityModel.collectAsState()
    if (pendingCapability != null) {
        val state by (viewModel.capabilityModelStates[pendingCapability]
            ?: viewModel.capabilityModelStates.values.first())
            .collectAsState()
        val downloaded: List<DownloadableModel> = (state as? CapabilityModelState.Downloaded)?.allDownloaded ?: emptyList()
        val available: List<DownloadableModel> = (state as? CapabilityModelState.NotDownloaded)?.available
            ?: com.quantlm.yaser.domain.model.AvailableModels.getAllModels()
                .filter { pendingCapability in it.capabilities }
        val activeFileName = loadedModel?.filePath?.let { java.io.File(it).name }
        CapabilityModelChooserSheet(
            capability = pendingCapability,
            downloaded = downloaded,
            available = available.filter { it !in downloaded },
            onUse = { selected ->
                viewModel.switchToModelForCapability(pendingCapability, selected) { success ->
                    if (success) pendingCapabilityChooser = null
                    else Toast.makeText(context, "Failed to load model", Toast.LENGTH_SHORT).show()
                }
            },
            onDownload = { selected ->
                pendingCapabilityChooser = null
                onNavigateToDownloads(selected.id)
            },
            onDismiss = { if (!isLoadingCapabilityModel) pendingCapabilityChooser = null },
            activeModelFileName = activeFileName,
        )
    }

    // Audio source picker (Record a real clip / Pick an audio file).
    if (showAudioSourceDialog) {
        AudioSourceDialog(
            onDismiss = { showAudioSourceDialog = false },
            onRecord = {
                showAudioSourceDialog = false
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    showAudioRecorderDialog = true
                } else {
                    recorderPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onPickFile = {
                showAudioSourceDialog = false
                audioPickerLauncher.launch("audio/*")
            },
        )
    }

    // Audio Scribe recorder — captures a real audio clip for the model.
    if (showAudioRecorderDialog) {
        AudioRecorderDialog(
            audioInputManager = viewModel.audioInputManager,
            onAttach = { pcmPath -> viewModel.attachRecordedAudio(pcmPath) },
            onDismiss = { showAudioRecorderDialog = false },
        )
    }

    // Phase 2 (§3.9): skill manager bottom sheet (full body in Sub-phase F).
    if (showSkillManagerSheet) {
        SkillManagerBottomSheet(
            onDismiss = { showSkillManagerSheet = false },
            viewModel = viewModel,
        )
    }

    // Clear history confirmation dialog
    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text("Clear Chat History") },
            text = { Text("This will delete all conversations and messages. This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllHistory()
                        showClearHistoryDialog = false
                    }
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    if (bubbleStyle == MessageBubbleStyle.Modern) {
        messageSheetMessage?.let { sheetMsg ->
            ModalBottomSheet(
                onDismissRequest = { messageSheetMessage = null },
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(Modifier.padding(bottom = 24.dp)) {
                    Text(
                        "Message",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("Copy") },
                        leadingContent = {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                        },
                        modifier = Modifier.clickable {
                            messageSheetMessage = null
                            clipboardManager.setText(AnnotatedString(sheetMsg.content))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        }
                    )
                    if (sheetMsg.isUser) {
                        ListItem(
                            headlineContent = { Text("Edit") },
                            leadingContent = {
                                Icon(Icons.Default.Edit, contentDescription = null)
                            },
                            modifier = Modifier.clickable {
                                messageSheetMessage = null
                                editMessage = sheetMsg
                                editDraft = sheetMsg.content
                            }
                        )
                        ListItem(
                            headlineContent = { Text("Regenerate") },
                            leadingContent = {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                            },
                            modifier = Modifier.clickable {
                                messageSheetMessage = null
                                viewModel.reProcessMessage(sheetMsg)
                            }
                        )
                    } else {
                        ListItem(
                            headlineContent = { Text("Regenerate") },
                            leadingContent = {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                            },
                            modifier = Modifier.clickable {
                                messageSheetMessage = null
                                viewModel.regenerateAssistantResponse(sheetMsg)
                            }
                        )
                    }
                    ListItem(
                        headlineContent = {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        },
                        leadingContent = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        modifier = Modifier.clickable {
                            messageSheetMessage = null
                            viewModel.deleteMessage(sheetMsg)
                        }
                    )
                    ListItem(
                        headlineContent = { Text("Share") },
                        leadingContent = {
                            Icon(Icons.Default.Share, contentDescription = null)
                        },
                        modifier = Modifier.clickable {
                            messageSheetMessage = null
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, sheetMsg.content)
                            }
                            context.startActivity(Intent.createChooser(send, "Share"))
                        }
                    )
                }
            }
        }
    }

    editMessage?.let { em ->
        AlertDialog(
            onDismissRequest = { editMessage = null },
            title = { Text("Edit message") },
            text = {
                OutlinedTextField(
                    value = editDraft,
                    onValueChange = { editDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.editAndResendUserMessage(em, editDraft)
                        editMessage = null
                    }
                ) {
                    Text("Save & resend")
                }
            },
            dismissButton = {
                TextButton(onClick = { editMessage = null }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = enableConversationListDialog,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxHeight()
            ) {
                ConversationHistoryDrawerContent(
                    conversations = conversations,
                    currentConversationId = currentConversationId,
                    onConversationSelected = { conversationId ->
                        closeHistoryMenu()
                        viewModel.loadConversation(conversationId)
                    },
                    onNewConversation = {
                        closeHistoryMenu()
                        viewModel.startNewConversation()
                    },
                    onDeleteConversation = viewModel::deleteConversation,
                    onOpenSettings = {
                        closeHistoryMenu()
                        onNavigateToSettings()
                    },
                    onOpenSystemLogs = {
                        closeHistoryMenu()
                        onNavigateToSystemLogs()
                    },
                    onClearHistory = {
                        closeHistoryMenu()
                        showClearHistoryDialog = true
                    }
                )
            }
        }
    ) {
        val snackbarHostState = remember { SnackbarHostState() }
        val snackScope = rememberCoroutineScope()
        LaunchedEffect(Unit) {
            viewModel.snackbarMessages.collect { message ->
                snackScope.launch {
                    snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
                }
            }
        }

        Scaffold(
            topBar = {
                topBar(
                    { openHistoryMenu() },
                    { viewModel.startNewConversation() },
                    { showClearHistoryDialog = true }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
        ) {
            // Messages list
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (messages.isEmpty() && !isGenerating) {
                    // Empty state
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = when {
                                loadedModel == null ->
                                    "No model loaded.\nPlease select a model in Settings."
                                bubbleStyle == MessageBubbleStyle.Modern ->
                                    "QuantLM\n\nStart a conversation"
                                else ->
                                    "Start a conversation with your AI assistant"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(if (bubbleStyle == MessageBubbleStyle.Modern) 8.dp else 12.dp)
                    ) {
                        itemsIndexed(
                            items = messages,
                            key = { _, message -> message.id }
                        ) { index, message ->
                            // Model-switch marker: visual separator only, never a real message.
                            if (message.isModelChangeMarker) {
                                ModelSwitchSeparator(modelName = message.markerModelName ?: "new model")
                                return@itemsIndexed
                            }

                            if (bubbleStyle == MessageBubbleStyle.Modern) {
                                val showDate = index == 0 ||
                                    !isSameLocalDay(messages[index - 1].timestamp, message.timestamp)
                                if (showDate) {
                                    ModernDateSeparator(timestampMillis = message.timestamp)
                                }
                            }
                            // Phase 2 (§3.6 / §4.3): thinking bubble rendered
                            // above the assistant content when the message
                            // carries a persisted reasoning block.
                            if (!message.isUser && !message.thinkingContent.isNullOrBlank()) {
                                MessageBodyThinking(
                                    text = message.thinkingContent,
                                    thoughtSummary = message.thoughtSummary,
                                    inProgress = false,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }
                            MessageBubble(
                                message = message,
                                onReResponse = if (message.isUser) {
                                    { viewModel.reProcessMessage(message) }
                                } else null,
                                style = bubbleStyle,
                                onLongPress = if (bubbleStyle == MessageBubbleStyle.Modern) {
                                    { messageSheetMessage = message }
                                } else null,
                                ttsVoiceProfile = generationSettings.ttsVoiceProfile
                            )
                        }

                        generatingResponseSection(
                            viewModel = viewModel,
                            bubbleStyle = bubbleStyle,
                            messages = messages,
                            toolResult = toolResult,
                            liveThinkingContent = liveThinkingContent,
                            liveThoughtSummary = liveThoughtSummary,
                        )
                    }
                }
            }
            
            // Vision quick actions (shown when images are selected)
            if (selectedImages.isNotEmpty() && isVisionSupported) {
                VisionQuickActionsBar(
                    activeAction = activeQuickAction,
                    onActionSelected = viewModel::setQuickAction
                )
            }

            // Phase 2 (§3.4 / §4.2): ProductivityTemplateQuickBar removed per
            // spec. TODO_LIST / MEETING_NOTES remain reachable via the prompt
            // library entry in the `+` menu.

            // Input area
            ChatInputArea(
                inputText = inputText,
                onInputChange = viewModel::setInputText,
                onSend = viewModel::sendMessage,
                onStop = viewModel::stopGeneration,
                isGenerating = isGenerating,
                isLoading = isLoadingOrSearching,
                isEnabled = true,  // Always enable keyboard - will show error if no model loaded
                selectedImages = selectedImages,
                onImageSelect = handleImageClick,
                onImageRemove = viewModel::removeImage,
                onImagesClear = viewModel::clearSelectedImages,
                selectedAudio = selectedAudio,
                onAudioRemove = viewModel::removeAudio,
                isVisionSupported = isVisionSupported,
                maxImages = ChatViewModel.MAX_IMAGES,
                onTemplateSelect = viewModel::selectTemplate,
                selectedTemplate = selectedTemplate,
                onClearTemplate = viewModel::clearTemplate,
                audioInputState = audioInputState,
                onMicClick = handleMicClick,
                onMicStop = handleMicStop,
                useModernChrome = useModernComposer,
                loadedModelCapabilities = loadedModelCapabilities,
                reasoningEnabled = generationSettings.enableThinking,
                speculativeDecodingEnabled = generationSettings.enableSpeculativeDecoding,
                agentSkillsEnabled = generationSettings.enableAgentSkills,
                webSearchEnabled = generationSettings.enableWebSearch,
                onReasoningToggle = viewModel::setEnableThinking,
                onSpeculativeDecodingToggle = viewModel::setEnableSpeculativeDecoding,
                onAgentSkillsToggle = viewModel::setEnableAgentSkills,
                onWebSearchToggle = viewModel::setEnableWebSearch,
                onAudioScribe = { showAudioSourceDialog = true },
                onShowCapabilityChooser = { capability ->
                    viewModel.refreshCapabilityModelStates()
                    viewModel.logCapabilityChooserOpened(capability, reason = "disabled_tap")
                    pendingCapabilityChooser = capability
                },
                onOpenSkillManager = { showSkillManagerSheet = true },
                enabledSkillNames = viewModel.enabledSkillNames.collectAsState().value,
            )
        }
        }
    }
}

/**
 * Owns the per-token recomposition for streaming auto-scroll. Reading
 * [ChatViewModel.generationState] in here (rather than at the scaffold's top)
 * confines invalidations to this composable; the parent only re-runs when
 * boolean flags flip.
 *
 * Scroll behavior: when the streaming bubble's bottom drifts past the viewport
 * end, animate the list down by exactly that overhang so the latest tokens are
 * visible. The previous `scrollToItem(messages.size)` pinned the *top* of the
 * streaming bubble to the viewport top, so as the bubble grew the user saw its
 * beginning rather than the freshest tokens. The key is bucketed to ~80 chars
 * so a long reply doesn't fire 2.5 scrolls/sec for the entire duration.
 */
@Composable
private fun StreamingAutoScroll(
    viewModel: ChatViewModel,
    listState: androidx.compose.foundation.lazy.LazyListState,
    autoScrollEnabled: Boolean,
    hasMessages: Boolean,
) {
    val generationState by viewModel.generationState.collectAsState()
    val isGenerating = generationState is GenerationState.Generating
    // Phase 1B: rescroll once per ~3 lines of text rather than once per ~1 line.
    // Each scrollKey change re-reads listState.layoutInfo and animates a scroll,
    // both of which force a layout pass on the entire LazyColumn. At 80-char
    // granularity that fires too often during streaming and contributes to
    // main-thread frame budget exhaustion.
    val scrollKey = ((generationState as? GenerationState.Generating)
        ?.currentText?.length ?: 0) / 240
    LaunchedEffect(scrollKey, autoScrollEnabled, isGenerating, hasMessages) {
        if (!isGenerating || !autoScrollEnabled || !hasMessages) return@LaunchedEffect
        val info = listState.layoutInfo
        val lastIndex = info.totalItemsCount - 1
        if (lastIndex < 0) return@LaunchedEffect
        val lastItem = info.visibleItemsInfo.lastOrNull { it.index == lastIndex }
        if (lastItem == null) {
            listState.scrollToItem(lastIndex)
            return@LaunchedEffect
        }
        val overhang = (lastItem.offset + lastItem.size) - info.viewportEndOffset
        if (overhang > 0) {
            listState.animateScrollBy(overhang.toFloat())
        }
    }
}

private fun LazyListScope.generatingResponseSection(
    viewModel: ChatViewModel,
    bubbleStyle: MessageBubbleStyle,
    messages: List<Message>,
    toolResult: ToolResult?,
    liveThinkingContent: String?,
    liveThoughtSummary: String?,
) {
    item {
        GeneratingResponseStateContent(
            viewModel = viewModel,
            bubbleStyle = bubbleStyle,
            messages = messages,
            toolResult = toolResult,
            liveThinkingContent = liveThinkingContent,
            liveThoughtSummary = liveThoughtSummary,
        )
    }
}

@Composable
private fun GeneratingResponseStateContent(
    viewModel: ChatViewModel,
    bubbleStyle: MessageBubbleStyle,
    messages: List<Message>,
    toolResult: ToolResult?,
    liveThinkingContent: String?,
    liveThoughtSummary: String?,
) {
    val generationState by viewModel.generationState.collectAsState()
    val pendingToolCall by viewModel.pendingToolCall.collectAsState()

    Column(modifier = Modifier.fillMaxWidth()) {
        if (generationState is GenerationState.Loading) {
            val lastUserMessage = messages.lastOrNull { it.isUser }
            val isVisionQuery = lastUserMessage?.hasImages == true
            ThinkingIndicator(isVisionQuery = isVisionQuery, style = bubbleStyle)
        }

        if (generationState is GenerationState.SearchingWeb) {
            WebSearchIndicator(
                message = (generationState as GenerationState.SearchingWeb).message,
                style = bubbleStyle,
            )
        }

        if (generationState is GenerationState.TranscribingAudio) {
            val audioState = generationState as GenerationState.TranscribingAudio
            AudioScribeIndicator(
                message = audioState.message,
                style = bubbleStyle,
                withReasoning = audioState.alsoReasoning,
            )
        }

        if (generationState is GenerationState.AnalyzingImage) {
            val visionState = generationState as GenerationState.AnalyzingImage
            VisionIndicator(
                message = visionState.message,
                style = bubbleStyle,
                withReasoning = visionState.alsoReasoning,
            )
        }

        if (generationState is GenerationState.PreparingReasoning) {
            ReasoningIndicator(
                message = (generationState as GenerationState.PreparingReasoning).message,
                style = bubbleStyle,
            )
        }

        val showThinking = generationState is GenerationState.Thinking ||
            (generationState is GenerationState.Generating && liveThinkingContent != null)
        if (showThinking) {
            val liveState = generationState as? GenerationState.Thinking
            val thinkingText = liveState?.content ?: liveThinkingContent ?: ""
            val inProgress = liveState?.partial == true
            val liveSummary = liveState?.thoughtSummary ?: liveThoughtSummary
            MessageBodyThinking(
                text = thinkingText,
                thoughtSummary = liveSummary,
                inProgress = inProgress,
                modifier = Modifier.padding(end = 16.dp),
            )
        }

        if (generationState is GenerationState.Generating) {
            val currentText = (generationState as GenerationState.Generating).currentText
            StreamingMessageBubble(text = currentText, style = bubbleStyle)
        }

        if (generationState is GenerationState.Cancelled) {
            CancelledMessageBubble()
        }

        if (generationState is GenerationState.Error) {
            val errorMessage = (generationState as GenerationState.Error).message
            ErrorMessageBubble(errorMessage = errorMessage)
        }

        pendingToolCall?.let { toolCall ->
            ToolCallBubble(
                toolCall = toolCall,
                result = toolResult,
                isPending = toolResult == null,
                onApprove = { viewModel.executeToolCall(toolCall) },
                onReject = { viewModel.rejectToolCall() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel(),
    onNavigateToDownloads: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToSystemLogs: () -> Unit = {}
) {
    com.quantlm.yaser.presentation.util.LogScreenLifecycle("ChatScreen")
    val loadedModel by viewModel.loadedModel.collectAsState()
    ChatThreadScaffold(
        viewModel = viewModel,
        onNavigateToSettings = onNavigateToSettings,
        onNavigateToSystemLogs = onNavigateToSystemLogs,
        onNavigateToDownloads = { _ -> onNavigateToDownloads() },
        topBar = { onOpenHistory, _onNewChat, _onClearHistory ->
            val modelSubtitle = loadedModel?.name ?: "Tap model icon to choose model"
            Surface(
                tonalElevation = 2.dp,
                shadowElevation = 2.dp,
                color = Color.Transparent
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary
                                )
                            )
                        )
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    IconButton(
                        onClick = onOpenHistory,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = "Open history",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Surface(
                            onClick = onNavigateToDownloads,
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 2.dp
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(1.dp)
                            ) {
                                Text(
                                    text = "QuantLM",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = modelSubtitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = onNavigateToDownloads,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        Icon(
                            Icons.Default.Memory,
                            contentDescription = "Models",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        bubbleStyle = MessageBubbleStyle.Modern,
        enableConversationListDialog = true,
        useModernComposer = true
    )
}

/**
 * Collapsible "Sources" element shown under a web-search-grounded answer.
 * Collapsed by default — tapping the header reveals the source list; tapping a
 * source opens it in the browser.
 */
@Composable
private fun MessageSourcesSection(
    sources: List<WebSourceRef>,
    contentColor: Color,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val mutedColor = contentColor.copy(alpha = 0.7f)

    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp, horizontal = 2.dp),
        ) {
            Icon(
                imageVector = Icons.Default.TravelExplore,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = mutedColor,
            )
            Text(
                text = "Sources (${sources.size})",
                style = MaterialTheme.typography.labelMedium,
                color = mutedColor,
            )
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Hide sources" else "Show sources",
                modifier = Modifier
                    .size(16.dp)
                    .rotate(if (expanded) 180f else 0f),
                tint = mutedColor,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                sources.forEachIndexed { index, source ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(source.url))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                            .padding(vertical = 4.dp, horizontal = 2.dp),
                    ) {
                        Text(
                            text = "${index + 1}.",
                            style = MaterialTheme.typography.labelMedium,
                            color = mutedColor,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = source.title,
                                style = MaterialTheme.typography.bodySmall,
                                color = contentColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = source.domain,
                                style = MaterialTheme.typography.labelSmall,
                                color = mutedColor,
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = mutedColor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MessageBubble(
    message: Message,
    onReResponse: (() -> Unit)? = null,
    style: MessageBubbleStyle = MessageBubbleStyle.Classic,
    onLongPress: (() -> Unit)? = null,
    ttsVoiceProfile: TtsVoiceProfile = TtsVoiceProfile.CLASSIC_LEGACY
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val clipboardManager = LocalClipboardManager.current
    var showCopiedSnackbar by remember { mutableStateOf(false) }
    var showStatsDialog by remember { mutableStateOf(false) }
    var isSpeaking by remember { mutableStateOf(false) }

    val ttsReady = remember { mutableStateOf(false) }
    val tts = remember(appContext) {
        TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady.value = true
            }
        }
    }

    LaunchedEffect(ttsReady.value, ttsVoiceProfile) {
        if (ttsReady.value) {
            TtsVoiceConfigurator.apply(tts, ttsVoiceProfile)
        }
    }

    DisposableEffect(tts) {
        onDispose {
            tts.stop()
            tts.shutdown()
        }
    }

    LaunchedEffect(tts) {
        tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                isSpeaking = false
            }
            override fun onError(utteranceId: String?) {
                isSpeaking = false
            }
        })
    }
    
    // Show stats dialog when clicked (only for AI messages with stats)
    if (showStatsDialog && message.hasGenerationStats) {
        GenerationStatsDialog(
            stats = message.generationStats!!,
            onDismiss = { showStatsDialog = false }
        )
    }
    
    val bubbleShape = when (style) {
        MessageBubbleStyle.Classic -> RoundedCornerShape(16.dp)
        MessageBubbleStyle.Modern -> RoundedCornerShape(16.dp)
    }

    val bubbleColor = when {
        message.isUser && style == MessageBubbleStyle.Modern ->
            MaterialTheme.colorScheme.primaryContainer
        message.isUser ->
            MaterialTheme.colorScheme.primaryContainer
        style == MessageBubbleStyle.Modern ->
            MaterialTheme.colorScheme.surfaceVariant
        else ->
            MaterialTheme.colorScheme.tertiaryContainer
    }

    val contentColor = when {
        message.isUser && style == MessageBubbleStyle.Modern ->
            MaterialTheme.colorScheme.onPrimaryContainer
        message.isUser ->
            MaterialTheme.colorScheme.onPrimaryContainer
        style == MessageBubbleStyle.Modern ->
            MaterialTheme.colorScheme.onSurface
        else ->
            MaterialTheme.colorScheme.onTertiaryContainer
    }

    val tonalElev = if (style == MessageBubbleStyle.Modern) 0.dp else 1.dp

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxBubbleWidth = when (style) {
            MessageBubbleStyle.Classic -> 280.dp
            MessageBubbleStyle.Modern -> maxWidth * 0.78f
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
        ) {
            Column(
                horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start
            ) {
            Surface(
                shape = bubbleShape,
                color = bubbleColor,
                tonalElevation = tonalElev,
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .then(
                        if (onLongPress != null) {
                            Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = onLongPress
                            )
                        } else Modifier
                    )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    // Show attached images if present (supports multiple)
                    if (message.hasImages) {
                        MessageImageGrid(
                            imagePaths = message.imagePaths,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Show attached audio clips if present (Audio Scribe)
                    if (message.audioPaths.isNotEmpty()) {
                        message.audioPaths.forEach { _ ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Default.AudioFile,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = contentColor
                                )
                                Text(
                                    text = "Audio clip",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = contentColor
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    val textColor = contentColor
                    // Phase 1A: memoize against message content. Every streaming
                    // token emit triggers a recomposition of the surrounding
                    // LazyColumn; without remember, every prior assistant bubble
                    // re-runs its Regex code-fence scan on every emit. With a
                    // handful of long replies in the history that quickly turns
                    // into the main-thread freeze that fires "Davey!" warnings.
                    val segments = remember(message.content) { parseMessageContent(message.content) }
                    MessageSegmentsContent(
                        segments = segments,
                        textColor = textColor,
                        clipboardManager = clipboardManager
                    )

                    // Web Search: collapsible list of the sources used to
                    // ground this answer. Only assistant messages produced with
                    // the toggle on carry sources.
                    if (!message.isUser && message.sources.isNotEmpty()) {
                        MessageSourcesSection(
                            sources = message.sources,
                            contentColor = contentColor,
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    
                    // Timestamp row with generation time for AI messages
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = if (message.hasGenerationStats) {
                            Modifier.clickable { showStatsDialog = true }
                        } else Modifier
                    ) {
                        Text(
                            text = formatTimestamp(message.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        // Show generation time for AI messages
                        if (message.hasGenerationStats) {
                            Text(
                                text = " • ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = message.generationStats!!.formatGenerationTime(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "View details",
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
            
            // Copy and TTS buttons for both user and AI messages
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start,
                modifier = Modifier.fillMaxWidth()
            ) {
                val buttonModifier = Modifier
                    .size(32.dp)
                    .padding(
                        top = 2.dp,
                        start = if (message.isUser) 0.dp else 4.dp,
                        end = if (message.isUser) 4.dp else 0.dp
                    )
                
                // Copy button
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(message.content))
                        showCopiedSnackbar = true
                    },
                    modifier = buttonModifier
                ) {
                    val iconTint = if (showCopiedSnackbar) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        modifier = Modifier.size(14.dp),
                        tint = iconTint
                    )
                }
                
                // Speaker (TTS) button
                IconButton(
                    onClick = {
                        if (isSpeaking) {
                            tts.stop()
                            isSpeaking = false
                        } else {
                            val params = android.os.Bundle()
                            tts.speak(
                                message.content,
                                TextToSpeech.QUEUE_FLUSH,
                                params,
                                "message_${message.id}"
                            )
                            isSpeaking = true
                        }
                    },
                    modifier = buttonModifier
                ) {
                    val iconTint = if (isSpeaking) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Icon(
                        if (isSpeaking) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = if (isSpeaking) "Stop speaking" else "Speak",
                        modifier = Modifier.size(14.dp),
                        tint = iconTint
                    )
                }
                
                // Re-response button (only for user messages)
                if (message.isUser && onReResponse != null) {
                    IconButton(
                        onClick = onReResponse,
                        modifier = buttonModifier
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Regenerate response",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Reset "Copied!" text after 2 seconds
            LaunchedEffect(showCopiedSnackbar) {
                if (showCopiedSnackbar) {
                    delay(2000)
                    showCopiedSnackbar = false
                }
            }
            }
        }
    }
}

@Composable
private fun MessageSegmentsContent(
    segments: List<MessageSegment>,
    textColor: Color,
    clipboardManager: ClipboardManager
) {
    val linkColor = MaterialTheme.colorScheme.primary
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        segments.forEach { segment ->
            when (segment) {
                is MessageSegment.Text -> {
                    val normalized = segment.text.trim('\n')
                    if (normalized.isNotBlank()) {
                        FormattedTextBlock(
                            text = normalized,
                            textColor = textColor,
                            linkColor = linkColor
                        )
                    }
                }
                is MessageSegment.Code -> {
                    CodeBlockSegment(
                        code = segment.code.trim('\n'),
                        language = segment.language,
                        clipboardManager = clipboardManager
                    )
                }
            }
        }
    }
}

@Composable
private fun FormattedTextBlock(
    text: String,
    textColor: Color,
    linkColor: Color
) {
    val blocks = remember(text) { parseTextBlocks(text) }
    val uriHandler = LocalUriHandler.current
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is TextBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        3 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.bodyMedium
                    }
                    val annotated = remember(block.content, block.level, textColor, linkColor) {
                        buildFormattedAnnotatedString(block.content, style, textColor, linkColor)
                    }
                    ClickableText(
                        text = annotated,
                        style = style,
                        onClick = { offset ->
                            handleAnnotationClick(annotated, offset, uriHandler)
                        }
                    )
                }
                is TextBlock.Paragraph -> {
                    val style = MaterialTheme.typography.bodyMedium
                    val annotated = remember(block.content, textColor, linkColor) {
                        buildFormattedAnnotatedString(block.content, style, textColor, linkColor)
                    }
                    ClickableText(
                        text = annotated,
                        style = style,
                        onClick = { offset ->
                            handleAnnotationClick(annotated, offset, uriHandler)
                        }
                    )
                }
                is TextBlock.BulletList -> {
                    val itemStyle = MaterialTheme.typography.bodyMedium
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        block.items.forEach { item ->
                            Row(
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    text = "\u2022",
                                    style = itemStyle,
                                    color = textColor
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                val annotated = remember(item, textColor, linkColor) {
                                    buildFormattedAnnotatedString(item, itemStyle, textColor, linkColor)
                                }
                                ClickableText(
                                    modifier = Modifier.weight(1f),
                                    text = annotated,
                                    style = itemStyle,
                                    onClick = { offset ->
                                        handleAnnotationClick(annotated, offset, uriHandler)
                                    }
                                )
                            }
                        }
                    }
                }
                is TextBlock.OrderedList -> {
                    val itemStyle = MaterialTheme.typography.bodyMedium
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        block.items.forEachIndexed { index, item ->
                            val number = block.startIndex + index
                            Row(
                                verticalAlignment = Alignment.Top
                            ) {
                                Text(
                                    text = "$number.",
                                    style = itemStyle,
                                    color = textColor
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                val annotated = remember(item, number, textColor, linkColor) {
                                    buildFormattedAnnotatedString(item, itemStyle, textColor, linkColor)
                                }
                                ClickableText(
                                    modifier = Modifier.weight(1f),
                                    text = annotated,
                                    style = itemStyle,
                                    onClick = { offset ->
                                        handleAnnotationClick(annotated, offset, uriHandler)
                                    }
                                )
                            }
                        }
                    }
                }
                is TextBlock.BlockQuote -> {
                    val style = MaterialTheme.typography.bodyMedium
                    Row(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Vertical bar for block quote
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .background(textColor.copy(alpha = 0.4f))
                                .padding(vertical = 4.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            val annotated = remember(block.content, textColor, linkColor) {
                                buildFormattedAnnotatedString(block.content, style, textColor, linkColor)
                            }
                            ClickableText(
                                text = annotated,
                                style = style.copy(fontStyle = FontStyle.Italic),
                                onClick = { offset ->
                                    handleAnnotationClick(annotated, offset, uriHandler)
                                }
                            )
                        }
                    }
                }
                is TextBlock.HorizontalRule -> {
                    Divider(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        thickness = 1.dp,
                        color = textColor.copy(alpha = 0.3f)
                    )
                }
            }
        }
    }
}

private fun handleAnnotationClick(
    annotatedString: AnnotatedString,
    offset: Int,
    uriHandler: UriHandler
) {
    annotatedString.getStringAnnotations(tag = LINK_TAG, start = offset, end = offset)
        .firstOrNull()
        ?.let { annotation ->
            uriHandler.openUri(annotation.item)
            return
        }

    annotatedString.getStringAnnotations(tag = EMAIL_TAG, start = offset, end = offset)
        .firstOrNull()
        ?.let { annotation ->
            uriHandler.openUri("mailto:${annotation.item}")
        }
}

private fun buildFormattedAnnotatedString(
    text: String,
    baseStyle: TextStyle,
    textColor: Color,
    linkColor: Color
): AnnotatedString {
    return buildAnnotatedString {
        pushStyle(
            SpanStyle(
                color = textColor,
                fontFamily = baseStyle.fontFamily,
                fontWeight = baseStyle.fontWeight,
                fontStyle = baseStyle.fontStyle,
                fontSize = baseStyle.fontSize,
                letterSpacing = baseStyle.letterSpacing
            )
        )
        val plainText = StringBuilder()
        val codeRanges = mutableListOf<IntRange>()
        val urlRanges = mutableListOf<IntRange>()

        fun appendText(value: String) {
            append(value)
            plainText.append(value)
        }

        fun appendChar(value: Char) {
            append(value)
            plainText.append(value)
        }

        var index = 0
        while (index < text.length) {
            when {
                // Bold+Italic: ***text*** or ___text___
                text.startsWith("***", index) -> {
                    val end = text.indexOf("***", index + 3)
                    if (end != -1) {
                        pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic))
                        appendText(text.substring(index + 3, end))
                        pop()
                        index = end + 3
                        continue
                    }
                }
                text.startsWith("___", index) -> {
                    val end = text.indexOf("___", index + 3)
                    if (end != -1) {
                        pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic))
                        appendText(text.substring(index + 3, end))
                        pop()
                        index = end + 3
                        continue
                    }
                }
                // Bold: **text** or __text__
                text.startsWith("**", index) -> {
                    val end = text.indexOf("**", index + 2)
                    if (end != -1) {
                        pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                        appendText(text.substring(index + 2, end))
                        pop()
                        index = end + 2
                        continue
                    }
                }
                text.startsWith("__", index) -> {
                    val end = text.indexOf("__", index + 2)
                    if (end != -1) {
                        pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                        appendText(text.substring(index + 2, end))
                        pop()
                        index = end + 2
                        continue
                    }
                }
                // Strikethrough: ~~text~~
                text.startsWith("~~", index) -> {
                    val end = text.indexOf("~~", index + 2)
                    if (end != -1) {
                        pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                        appendText(text.substring(index + 2, end))
                        pop()
                        index = end + 2
                        continue
                    }
                }
                // Italic: *text* (single asterisk, but not part of **)
                text.startsWith("*", index) && !text.startsWith("**", index) -> {
                    val end = findClosingMarker(text, index + 1, '*')
                    if (end != -1) {
                        pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                        appendText(text.substring(index + 1, end))
                        pop()
                        index = end + 1
                        continue
                    }
                }
                // Italic: _text_ (single underscore, but not part of __)
                text.startsWith("_", index) && !text.startsWith("__", index) -> {
                    val end = findClosingMarker(text, index + 1, '_')
                    if (end != -1) {
                        pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                        appendText(text.substring(index + 1, end))
                        pop()
                        index = end + 1
                        continue
                    }
                }
                // Inline code: `code`
                text.startsWith("`", index) -> {
                    val end = text.indexOf('`', index + 1)
                    if (end != -1) {
                        pushStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = textColor.copy(alpha = 0.1f)
                            )
                        )
                        val codeStart = plainText.length
                        val codeContent = text.substring(index + 1, end)
                        appendText(codeContent)
                        val codeEnd = plainText.length
                        if (codeEnd > codeStart) {
                            codeRanges.add(codeStart until codeEnd)
                        }
                        pop()
                        index = end + 1
                        continue
                    }
                }
                // Markdown links: [text](url)
                text.startsWith("[", index) -> {
                    val closeBracket = text.indexOf(']', index + 1)
                    if (closeBracket != -1 && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                        val closeParen = text.indexOf(')', closeBracket + 2)
                        if (closeParen != -1) {
                            val linkText = text.substring(index + 1, closeBracket)
                            val linkUrl = text.substring(closeBracket + 2, closeParen)
                            if (linkText.isNotEmpty() && linkUrl.isNotEmpty()) {
                                val linkStart = plainText.length
                                pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                                appendText(linkText)
                                pop()
                                val linkEnd = plainText.length
                                addStringAnnotation(
                                    tag = LINK_TAG,
                                    annotation = if (linkUrl.startsWith("http://") || linkUrl.startsWith("https://")) {
                                        linkUrl
                                    } else {
                                        "https://$linkUrl"
                                    },
                                    start = linkStart,
                                    end = linkEnd
                                )
                                urlRanges.add(linkStart until linkEnd)
                                index = closeParen + 1
                                continue
                            }
                        }
                    }
                }
            }
            appendChar(text[index])
            index++
        }
        pop()

        val content = plainText.toString()

        urlRegex.findAll(content).forEach { match ->
            val range = match.range
            if (range.isEmpty()) return@forEach
            if (codeRanges.any { it.overlaps(range) }) return@forEach

            val start = range.first
            val end = range.last + 1
            val normalized = if (match.value.startsWith("www.", ignoreCase = true)) {
                "https://${match.value}"
            } else {
                match.value
            }
            addStyle(
                style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                start = start,
                end = end
            )
            addStringAnnotation(
                tag = LINK_TAG,
                annotation = normalized,
                start = start,
                end = end
            )
            urlRanges.add(range)
        }

        emailRegex.findAll(content).forEach { match ->
            val range = match.range
            if (range.isEmpty()) return@forEach
            if (codeRanges.any { it.overlaps(range) }) return@forEach
            if (urlRanges.any { it.overlaps(range) }) return@forEach

            val start = range.first
            val end = range.last + 1
            addStyle(
                style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                start = start,
                end = end
            )
            addStringAnnotation(
                tag = EMAIL_TAG,
                annotation = match.value,
                start = start,
                end = end
            )
        }
    }
}

@Composable
private fun CodeBlockSegment(
    code: String,
    language: String?,
    clipboardManager: ClipboardManager
) {
    var copied by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (!language.isNullOrBlank()) {
                Text(
                    text = language.uppercase(Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(code))
                    copied = true
                }
            ) {
                val iconTint = if (copied) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copy code",
                    modifier = Modifier.size(16.dp),
                    tint = iconTint
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = code,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }
}

@Composable
fun StreamingMessageBubble(
    text: String,
    style: MessageBubbleStyle = MessageBubbleStyle.Classic
) {
    val clipboardManager = LocalClipboardManager.current
    var showCopiedSnackbar by remember { mutableStateOf(false) }

    val bubbleShape = when (style) {
        MessageBubbleStyle.Classic -> RoundedCornerShape(16.dp)
        MessageBubbleStyle.Modern -> RoundedCornerShape(16.dp)
    }
    val bubbleColor = when (style) {
        MessageBubbleStyle.Classic -> MaterialTheme.colorScheme.tertiaryContainer
        MessageBubbleStyle.Modern -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (style) {
        MessageBubbleStyle.Classic -> MaterialTheme.colorScheme.onTertiaryContainer
        MessageBubbleStyle.Modern -> MaterialTheme.colorScheme.onSurface
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxBubbleWidth = when (style) {
            MessageBubbleStyle.Classic -> 280.dp
            MessageBubbleStyle.Modern -> maxWidth * 0.78f
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            Column(
                horizontalAlignment = Alignment.Start
            ) {
            Surface(
                shape = bubbleShape,
                color = bubbleColor,
                modifier = Modifier.widthIn(max = maxBubbleWidth)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = text,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = contentColor
                        )
                        if (style == MessageBubbleStyle.Modern) {
                            ModernStreamingCursor(color = contentColor)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (style == MessageBubbleStyle.Classic) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            
            // Copy button for streaming response
            Spacer(modifier = Modifier.height(2.dp))
            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(text))
                    showCopiedSnackbar = true
                },
                modifier = Modifier
                    .size(32.dp)
                    .padding(top = 2.dp, start = 4.dp)
            ) {
                val iconTint = if (showCopiedSnackbar) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copy",
                    modifier = Modifier.size(14.dp),
                    tint = iconTint
                )
            }
            
            // Reset "Copied!" text after 2 seconds
            LaunchedEffect(showCopiedSnackbar) {
                if (showCopiedSnackbar) {
                    delay(2000)
                    showCopiedSnackbar = false
                }
            }
            }
        }
    }
}

@Composable
private fun ModernStreamingCursor(color: Color) {
    val transition = rememberInfiniteTransition(label = "stream_cursor")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(450),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursor_alpha"
    )
    Box(
        modifier = Modifier
            .padding(start = 4.dp, bottom = 3.dp)
            .width(2.dp)
            .height(16.dp)
            .background(color.copy(alpha = alpha))
    )
}

/**
 * Web Search status bubble: shown while the Web Search feature discovers and
 * scrapes pages. Carries an explicit "this may take a few seconds" message so
 * the user knows the wait is expected.
 */
@Composable
fun ModelSwitchSeparator(modelName: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        SuggestionChip(
            onClick = {},
            label = {
                Text(
                    text = "↕  Context reset · $modelName",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            },
            colors = SuggestionChipDefaults.suggestionChipColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            border = SuggestionChipDefaults.suggestionChipBorder(
                enabled = true,
                borderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
fun WebSearchIndicator(
    message: String,
    style: MessageBubbleStyle = MessageBubbleStyle.Classic,
) {
    PrefillPhaseIndicator(message = message, icons = listOf(Icons.Default.TravelExplore), style = style)
}

@Composable
fun AudioScribeIndicator(
    message: String,
    style: MessageBubbleStyle = MessageBubbleStyle.Classic,
    withReasoning: Boolean = false,
) {
    PrefillPhaseIndicator(
        message = message,
        icons = if (withReasoning) {
            listOf(Icons.Default.Mic, Icons.Default.Psychology)
        } else {
            listOf(Icons.Default.Mic)
        },
        style = style,
    )
}

@Composable
fun VisionIndicator(
    message: String,
    style: MessageBubbleStyle = MessageBubbleStyle.Classic,
    withReasoning: Boolean = false,
) {
    PrefillPhaseIndicator(
        message = message,
        icons = if (withReasoning) {
            listOf(Icons.Default.Visibility, Icons.Default.Psychology)
        } else {
            listOf(Icons.Default.Visibility)
        },
        style = style,
    )
}

@Composable
fun ReasoningIndicator(
    message: String,
    style: MessageBubbleStyle = MessageBubbleStyle.Classic,
) {
    PrefillPhaseIndicator(message = message, icons = listOf(Icons.Default.Psychology), style = style)
}

/**
 * Shared visual shell for the pre-token phase indicators (web search,
 * audio scribe, vision, reasoning prelude, and combinations of media +
 * reasoning). Identical bubble + spinner + message layout — only the
 * leading icon set and the message vary. When [icons] has more than one
 * entry the icons render in a tight row before the message, signaling
 * combined phases (e.g. mic + brain for audio-with-reasoning).
 */
@Composable
private fun PrefillPhaseIndicator(
    message: String,
    icons: List<androidx.compose.ui.graphics.vector.ImageVector>,
    style: MessageBubbleStyle,
) {
    val bubbleColor = when (style) {
        MessageBubbleStyle.Classic -> MaterialTheme.colorScheme.tertiaryContainer
        MessageBubbleStyle.Modern -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (style) {
        MessageBubbleStyle.Classic -> MaterialTheme.colorScheme.onTertiaryContainer
        MessageBubbleStyle.Modern -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = bubbleColor,
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = contentColor,
                )
                // Icons are packed tight against each other so the user reads
                // them as a compound "audio + reasoning" rather than two
                // separate concerns separated by message gap.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    icons.forEach { icon ->
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = contentColor,
                        )
                    }
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                )
            }
        }
    }
}

@Composable
fun ThinkingIndicator(
    isVisionQuery: Boolean = false,
    style: MessageBubbleStyle = MessageBubbleStyle.Classic
) {
    val bubbleShape = when (style) {
        MessageBubbleStyle.Classic -> RoundedCornerShape(16.dp)
        MessageBubbleStyle.Modern -> RoundedCornerShape(16.dp)
    }
    val bubbleColor = when (style) {
        MessageBubbleStyle.Classic -> MaterialTheme.colorScheme.tertiaryContainer
        MessageBubbleStyle.Modern -> MaterialTheme.colorScheme.surfaceVariant
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxBubbleWidth = when (style) {
            MessageBubbleStyle.Classic -> 280.dp
            MessageBubbleStyle.Modern -> maxWidth * 0.78f
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            Surface(
                shape = bubbleShape,
                color = bubbleColor,
                modifier = Modifier.widthIn(max = maxBubbleWidth)
            ) {
            if (style == MessageBubbleStyle.Modern) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
                    repeat(3) { index ->
                        val t = rememberInfiniteTransition(label = "typing$index")
                        val scale by t.animateFloat(
                            initialValue = 0.5f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(420, delayMillis = index * 140),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "typingDot"
                        )
                        Box(
                            modifier = Modifier
                                .size((8 * scale).dp)
                                .background(dotColor.copy(alpha = 0.85f), CircleShape)
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }
        }
    }
}

@Composable
fun ErrorMessageBubble(errorMessage: String) {
    val bubbleShape = RoundedCornerShape(16.dp)
    val bubbleColor = MaterialTheme.colorScheme.errorContainer
    val contentColor = MaterialTheme.colorScheme.onErrorContainer
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = bubbleColor,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor
                )
            }
        }
    }
}

@Composable
fun CancelledMessageBubble() {
    val bubbleShape = RoundedCornerShape(16.dp)
    val bubbleColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = bubbleColor,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text = "Response cancelled by user",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontStyle = FontStyle.Italic
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

@Composable
fun ChatInputArea(
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isGenerating: Boolean,
    isLoading: Boolean,
    isEnabled: Boolean,
    selectedImages: List<String> = emptyList(),
    onImageSelect: () -> Unit = {},
    onImageRemove: (String) -> Unit = {},
    onImagesClear: () -> Unit = {},
    selectedAudio: List<String> = emptyList(),
    onAudioRemove: (String) -> Unit = {},
    isVisionSupported: Boolean = false,
    maxImages: Int = 4,
    onTemplateSelect: (PromptTemplate) -> Unit = {},
    selectedTemplate: PromptTemplate? = null,
    onClearTemplate: () -> Unit = {},
    // Audio input parameters
    audioInputState: AudioInputState = AudioInputState.Idle,
    onMicClick: () -> Unit = {},
    onMicStop: () -> Unit = {},
    useModernChrome: Boolean = false,
    // Phase 2 (§3.4 / §4.1): capability toggles surfaced inside the `+` menu.
    loadedModelCapabilities: Set<com.quantlm.yaser.domain.model.ModelCapability> = emptySet(),
    reasoningEnabled: Boolean = false,
    speculativeDecodingEnabled: Boolean = false,
    agentSkillsEnabled: Boolean = false,
    // Web Search: model-agnostic toggle, never gated by model capability.
    webSearchEnabled: Boolean = false,
    onReasoningToggle: (Boolean) -> Unit = {},
    onSpeculativeDecodingToggle: (Boolean) -> Unit = {},
    onAgentSkillsToggle: (Boolean) -> Unit = {},
    onWebSearchToggle: (Boolean) -> Unit = {},
    onAudioScribe: () -> Unit = {},
    onShowCapabilityChooser: (com.quantlm.yaser.domain.model.ModelCapability) -> Unit = {},
    onOpenSkillManager: () -> Unit = {},
    enabledSkillNames: List<String> = emptyList(),
) {
    val hasImages = selectedImages.isNotEmpty()
    val hasAttachments = hasImages || selectedAudio.isNotEmpty()
    var showTemplateSheet by remember { mutableStateOf(false) }
    var showToolsMenu by remember { mutableStateOf(false) }
    val isListening = audioInputState is AudioInputState.Listening
    
    // Template selection bottom sheet
    if (showTemplateSheet) {
        PromptTemplateBottomSheet(
            onDismiss = { showTemplateSheet = false },
            onTemplateSelect = { template ->
                onTemplateSelect(template)
                showTemplateSheet = false
            }
        )
    }
    
    Surface(
        shape = if (useModernChrome) RoundedCornerShape(30.dp) else RoundedCornerShape(0.dp),
        tonalElevation = if (useModernChrome) 3.dp else 3.dp,
        shadowElevation = if (useModernChrome) 4.dp else 0.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = if (useModernChrome) {
            Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .navigationBarsPadding()
        } else {
            Modifier
        }
    ) {
        Column {
            // Show thin progress bar at top when loading or generating
            if (isLoading || isGenerating) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            
            // Show selected template indicator
            if (selectedTemplate != null) {
                SelectedTemplateIndicator(
                    template = selectedTemplate,
                    onClear = onClearTemplate
                )
            }
            
            // Show selected images preview (supports multiple)
            if (hasImages) {
                SelectedImagesPreview(
                    imagePaths = selectedImages,
                    onRemoveImage = onImageRemove,
                    onClearAll = onImagesClear,
                    canAddMore = selectedImages.size < maxImages,
                    onAddMore = onImageSelect,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // Show selected audio attachments (Audio Scribe)
            if (selectedAudio.isNotEmpty()) {
                SelectedAudioPreview(
                    audioPaths = selectedAudio,
                    onRemoveAudio = onAudioRemove,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // Phase 2 (§3.9 / §4.2): enabled-skill chip strip surfaces only when
            // agent skills is enabled AND at least one skill is selected. Tap
            // opens the skill manager bottom sheet.
            if (agentSkillsEnabled && enabledSkillNames.isNotEmpty()) {
                AgentSkillsChipStrip(
                    skillNames = enabledSkillNames,
                    onOpenManager = onOpenSkillManager,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = if (useModernChrome) 10.dp else 16.dp,
                        vertical = if (useModernChrome) 8.dp else 16.dp
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Plus button opens a tools menu (prompt library and related actions).
                IconButton(
                    onClick = { showToolsMenu = true },
                    enabled = isEnabled && !isGenerating && !isLoading && !isListening
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Open tools",
                        tint = if (selectedTemplate != null) {
                            MaterialTheme.colorScheme.primary
                        } else if (isEnabled && !isGenerating && !isLoading) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        }
                    )
                }
                DropdownMenu(
                    expanded = showToolsMenu,
                    onDismissRequest = { showToolsMenu = false }
                ) {
                    // Phase 2 (§3.4 / §4.1): capability-aware composer menu.
                    // Items render as enabled / greyed-but-tappable per the
                    // active model's [ModelCapability] set. Tapping a greyed
                    // capability opens the capability-restricted chooser.
                    val capVision = com.quantlm.yaser.domain.model.ModelCapability.VISION
                    val capAudio = com.quantlm.yaser.domain.model.ModelCapability.AUDIO
                    val capReasoning = com.quantlm.yaser.domain.model.ModelCapability.REASONING
                    val capSpecDec = com.quantlm.yaser.domain.model.ModelCapability.SPECULATIVE_DECODING
                    val capAgent = com.quantlm.yaser.domain.model.ModelCapability.AGENT_SKILLS

                    CapabilityMenuItem(
                        label = "Add image",
                        icon = Icons.Default.Image,
                        active = capVision in loadedModelCapabilities || isVisionSupported,
                        onActiveClick = {
                            showToolsMenu = false
                            onImageSelect()
                        },
                        onInactiveClick = {
                            showToolsMenu = false
                            onShowCapabilityChooser(capVision)
                        },
                    )
                    CapabilityMenuItem(
                        label = "Audio Scribe",
                        icon = Icons.Default.Mic,
                        active = capAudio in loadedModelCapabilities,
                        onActiveClick = {
                            showToolsMenu = false
                            onAudioScribe()
                        },
                        onInactiveClick = {
                            showToolsMenu = false
                            onShowCapabilityChooser(capAudio)
                        },
                    )
                    HorizontalDivider()
                    CapabilityToggleItem(
                        label = "Reasoning",
                        icon = Icons.Default.AutoAwesome,
                        active = capReasoning in loadedModelCapabilities,
                        checked = reasoningEnabled && capReasoning in loadedModelCapabilities,
                        onCheckedChange = { onReasoningToggle(it) },
                        onInactiveClick = {
                            showToolsMenu = false
                            onShowCapabilityChooser(capReasoning)
                        },
                    )
                    CapabilityToggleItem(
                        label = "Speculative decoding",
                        icon = Icons.Default.Speed,
                        active = capSpecDec in loadedModelCapabilities,
                        checked = speculativeDecodingEnabled && capSpecDec in loadedModelCapabilities,
                        onCheckedChange = { onSpeculativeDecodingToggle(it) },
                        onInactiveClick = {
                            showToolsMenu = false
                            onShowCapabilityChooser(capSpecDec)
                        },
                    )
                    CapabilityToggleItem(
                        label = "Agent skills",
                        icon = Icons.Default.Build,
                        active = capAgent in loadedModelCapabilities,
                        checked = agentSkillsEnabled && capAgent in loadedModelCapabilities,
                        onCheckedChange = { isOn ->
                            onAgentSkillsToggle(isOn)
                            if (isOn) {
                                showToolsMenu = false
                                onOpenSkillManager()
                            }
                        },
                        onInactiveClick = {
                            showToolsMenu = false
                            onShowCapabilityChooser(capAgent)
                        },
                    )
                    // Web Search: always active — it augments the prompt and so
                    // works with every model, current or future.
                    CapabilityToggleItem(
                        label = "Web search",
                        icon = Icons.Default.TravelExplore,
                        active = true,
                        checked = webSearchEnabled,
                        onCheckedChange = { onWebSearchToggle(it) },
                        onInactiveClick = {},
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Prompt Library") },
                        leadingIcon = {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        },
                        onClick = {
                            showToolsMenu = false
                            showTemplateSheet = true
                        }
                    )
                    if (selectedTemplate != null) {
                        DropdownMenuItem(
                            text = { Text("Clear Prompt Template") },
                            onClick = {
                                showToolsMenu = false
                                onClearTemplate()
                            }
                        )
                    }
                }
                
                OutlinedTextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_input"),
                    placeholder = {
                        Text(
                            when {
                                isLoading -> "Processing..."
                                isGenerating -> "Generating response..."
                                selectedTemplate != null -> "Enter your text for ${selectedTemplate.name}..."
                                selectedImages.size > 1 -> "Ask about these ${selectedImages.size} images..."
                                hasImages -> "Ask about the image..."
                                useModernChrome -> "Ask anything"
                                else -> "Type a message..."
                            }
                        ) 
                    },
                    enabled = isEnabled && !isGenerating && !isLoading,
                    maxLines = if (useModernChrome) 5 else 4,
                    singleLine = useModernChrome,
                    colors = if (useModernChrome) {
                        OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            disabledBorderColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent
                        )
                    } else {
                        OutlinedTextFieldDefaults.colors()
                    }
                )

                // Phase 2 (§3.4 / §4.1): right-side row is now `[mic] [send|stop]`.
                // The image button moved into the `+` menu's "Add image" entry.
                IconButton(
                    onClick = {
                        if (isListening) onMicStop() else onMicClick()
                    },
                    enabled = isEnabled && !isGenerating && !isLoading
                ) {
                    Icon(
                        if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = if (isListening) "Stop listening" else "Voice input",
                        tint = when {
                            isListening -> MaterialTheme.colorScheme.error
                            audioInputState is AudioInputState.Processing -> MaterialTheme.colorScheme.primary
                            isEnabled && !isGenerating && !isLoading -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        }
                    )
                }

                if (isGenerating || isLoading) {
                    IconButton(onClick = onStop) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = "Stop",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    IconButton(
                        onClick = onSend,
                        enabled = isEnabled && (inputText.isNotBlank() || hasAttachments),
                        modifier = Modifier.testTag("chat_send")
                    ) {
                        Icon(
                            Icons.Default.Send,
                            contentDescription = "Send",
                            tint = if (isEnabled && (inputText.isNotBlank() || hasAttachments)) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Phase 2 (§3.4 / §4.1): capability-aware row inside the composer `+` menu.
 *
 * Rendered "greyed-but-tappable" when [active] is false: the [DropdownMenuItem]
 * stays enabled (Material 3 swallows the click otherwise) but icon + label use
 * reduced alpha and a trailing "ⓘ" hint indicates that tapping opens a chooser
 * listing models that *do* support the capability.
 */
@Composable
private fun CapabilityMenuItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onActiveClick: () -> Unit,
    onInactiveClick: () -> Unit,
) {
    val alpha by animateFloatAsState(
        targetValue = if (active) 1f else 0.5f,
        animationSpec = tween(durationMillis = 300),
        label = "capability_alpha",
    )
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
            )
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
            )
        },
        trailingIcon = if (!active) {
            {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "No loaded model supports this — tap to choose one",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp)
                )
            }
        } else null,
        onClick = { if (active) onActiveClick() else onInactiveClick() },
    )
}

@Composable
private fun CapabilityToggleItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onInactiveClick: () -> Unit,
) {
    val alpha by animateFloatAsState(
        targetValue = if (active) 1f else 0.5f,
        animationSpec = tween(durationMillis = 300),
        label = "capability_toggle_alpha",
    )
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
            )
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
            )
        },
        trailingIcon = {
            if (active) {
                Checkbox(checked = checked, onCheckedChange = null)
            } else {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "No loaded model supports this — tap to choose one",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp)
                )
            }
        },
        onClick = {
            if (active) onCheckedChange(!checked) else onInactiveClick()
        },
    )
}

@Composable
private fun AgentSkillsChipStrip(
    skillNames: List<String>,
    onOpenManager: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val maxVisible = 3
    val visible = skillNames.take(maxVisible)
    val overflow = (skillNames.size - maxVisible).coerceAtLeast(0)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onOpenManager() },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Build,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = buildString {
                    append(visible.joinToString(separator = " · "))
                    if (overflow > 0) append(" · +$overflow more")
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Dialog showing detailed generation statistics for an AI response
 */
@Composable
fun GenerationStatsDialog(
    stats: GenerationStats,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Generation Details",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Performance section
                Text(
                    text = "Performance",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                StatsRow(label = "Generation Time", value = stats.formatGenerationTime())
                if (stats.timeToFirstTokenMs > 0) {
                    StatsRow(label = "Time to First Token", value = stats.formatTimeToFirstToken())
                }
                StatsRow(label = "Speed", value = stats.formatTokensPerSecond())
                
                Divider(modifier = Modifier.padding(vertical = 4.dp))
                
                // Tokens section
                Text(
                    text = "Tokens",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                StatsRow(label = "Prompt Tokens", value = "~${stats.promptTokens}")
                StatsRow(label = "Generated Tokens", value = "~${stats.tokensGenerated}")
                StatsRow(label = "Total Tokens", value = "~${stats.totalTokens}")
                
                Divider(modifier = Modifier.padding(vertical = 4.dp))
                
                // Model section
                Text(
                    text = "Model Configuration",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                if (stats.modelName.isNotEmpty()) {
                    StatsRow(label = "Model", value = stats.modelName)
                }
                if (stats.modelFormat.isNotEmpty()) {
                    StatsRow(label = "Model Format", value = stats.modelFormat)
                }
                if (stats.backend.isNotEmpty()) {
                    StatsRow(label = "Backend Used", value = stats.backend)
                }
                StatsRow(label = "Temperature", value = String.format("%.2f", stats.temperature))
                StatsRow(label = "Top-P", value = String.format("%.2f", stats.topP))
                StatsRow(label = "Top-K", value = stats.topK.toString())
                StatsRow(label = "Max Tokens", value = stats.maxTokens.toString())
                
                // Vision info if applicable
                if (stats.wasVisionRequest) {
                    Divider(modifier = Modifier.padding(vertical = 4.dp))
                    Text(
                        text = "Vision Request",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    StatsRow(label = "Images Processed", value = stats.imageCount.toString())
                }
                
                Divider(modifier = Modifier.padding(vertical = 4.dp))
                
                // Memory section
                Text(
                    text = "Resources",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                StatsRow(label = "Memory Used", value = stats.formatMemoryUsage())
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun StatsRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Dialog shown when user tries to attach an image but vision model is not active.
 * Shows a scrollable list of all available vision models with their download status.
 */
@Composable
fun VisionModelDialog(
    visionModelState: VisionModelState,
    downloadStates: Map<String, DownloadState> = emptyMap(),
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onSwitchToVisionModel: (DownloadableModel) -> Unit = {},
    onDownloadVisionModel: (DownloadableModel) -> Unit = {}
) {
    if (visionModelState is VisionModelState.Ready) return
    
    // Get all vision models - downloaded and available
    val allDownloaded = when (visionModelState) {
        is VisionModelState.Downloaded -> visionModelState.allDownloaded
        else -> emptyList()
    }
    val allDownloadedIds = remember(allDownloaded) { allDownloaded.map { it.id }.toSet() }
    
    // Combine all vision models for display
    val allVisionModels = remember(visionModelState, downloadStates, allDownloadedIds) {
        val all = com.quantlm.yaser.domain.model.AvailableModels.getAllModels().filter { it.isVisionModel }
        all.sortedWith(
            compareByDescending<DownloadableModel> { it.id in allDownloadedIds }
                .thenByDescending {
                    val state = downloadStates[it.id]
                    state is DownloadState.Downloading || state is DownloadState.Queued
                }
                .thenBy { it.name }
        )
    }
    
    // Track selected model for action
    var selectedModel by remember(allVisionModels, allDownloadedIds) { mutableStateOf<DownloadableModel?>(
        allDownloaded.firstOrNull() ?: allVisionModels.firstOrNull()
    ) }

    LaunchedEffect(allVisionModels) {
        if (selectedModel !in allVisionModels) {
            selectedModel = allDownloaded.firstOrNull() ?: allVisionModels.firstOrNull()
        }
    }
    
    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { 
            Text(
                text = "📷 Select Vision Model",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Choose a vision-capable model to analyze images. One tap downloads all required files.",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                // Scrollable list of vision models
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(allVisionModels) { model ->
                        val downloadState = downloadStates[model.id]
                        val isDownloaded = model.id in allDownloadedIds || downloadState is DownloadState.Success
                        val isSelected = model == selectedModel
                        
                        VisionModelCard(
                            model = model,
                            isDownloaded = isDownloaded,
                            downloadState = downloadState,
                            isSelected = isSelected,
                            onClick = { selectedModel = model }
                        )
                    }
                }

                selectedModel?.let { model ->
                    when (val selectedState = downloadStates[model.id]) {
                        is DownloadState.Downloading -> {
                            val progressPercent = (selectedState.progress * 100f).toInt().coerceIn(0, 100)
                            Text(
                                text = "Downloading $progressPercent%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        is DownloadState.Queued -> {
                            Text(
                                text = "Queued. Download starts automatically.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        is DownloadState.Error -> {
                            Text(
                                text = selectedState.message.ifBlank { "Download failed. Tap Retry Download." },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        else -> Unit
                    }
                }
                
                if (isLoading) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Loading vision model...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        },
        confirmButton = {
            selectedModel?.let { model ->
                val selectedDownloadState = downloadStates[model.id]
                val isDownloaded = model.id in allDownloadedIds || selectedDownloadState is DownloadState.Success
                val isDownloadInProgress = selectedDownloadState is DownloadState.Downloading ||
                    selectedDownloadState is DownloadState.Queued
                if (isDownloaded) {
                    Button(
                        onClick = { onSwitchToVisionModel(model) },
                        enabled = !isLoading
                    ) {
                        Text("Use ${model.name.take(15)}...")
                    }
                } else {
                    Button(
                        onClick = { onDownloadVisionModel(model) },
                        enabled = !isLoading && !isDownloadInProgress
                    ) {
                        if (!isDownloadInProgress) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        val actionText = when (selectedDownloadState) {
                            is DownloadState.Downloading -> {
                                val progressPercent = (selectedDownloadState.progress * 100f).toInt().coerceIn(0, 100)
                                "Downloading $progressPercent%"
                            }
                            is DownloadState.Queued -> "Queued..."
                            is DownloadState.Error,
                            is DownloadState.Cancelled -> "Retry Download"
                            else -> "Download & Use"
                        }
                        Text(actionText)
                    }
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading
            ) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Card component for displaying a single vision model in the selection list
 */
@Composable
private fun VisionModelCard(
    model: DownloadableModel,
    isDownloaded: Boolean,
    downloadState: DownloadState?,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val statusLabel = when {
        isDownloaded -> "Ready"
        downloadState is DownloadState.Downloading -> "Downloading"
        downloadState is DownloadState.Queued -> "Queued"
        downloadState is DownloadState.Error -> "Failed"
        downloadState is DownloadState.Cancelled -> "Cancelled"
        else -> null
    }
    val statusColor = when {
        isDownloaded -> MaterialTheme.colorScheme.primary
        downloadState is DownloadState.Error || downloadState is DownloadState.Cancelled -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.tertiary
    }

    Surface(
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else null
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Selection indicator
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (statusLabel != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            color = statusColor.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(2.dp))
                
                Text(
                    text = model.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )

                if (downloadState is DownloadState.Downloading) {
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { downloadState.progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(999.dp))
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${(downloadState.progress * 100f).toInt().coerceIn(0, 100)}% • ${formatByteSize(downloadState.downloadedBytes)} / ${formatByteSize(downloadState.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                // Size info
                val totalSize = model.getTotalSize()
                val sizeText = if (totalSize > 0) {
                    formatByteSize(totalSize)
                } else {
                    "Size TBD"
                }
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = sizeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = model.quantization,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Copy an image from a content URI to internal storage.
 * Returns the file path or null if copy failed.
 */
private fun copyImageToInternalStorage(context: Context, uri: Uri): String? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val imagesDir = File(context.filesDir, "chat_images")
        if (!imagesDir.exists()) {
            imagesDir.mkdirs()
        }
        val imageFile = File(imagesDir, "img_${System.currentTimeMillis()}.jpg")
        FileOutputStream(imageFile).use { outputStream ->
            inputStream.copyTo(outputStream)
        }
        inputStream.close()
        imageFile.absolutePath
    } catch (e: Exception) {
        Log.e(TAG, "Failed to copy image to internal storage", e)
        null
    }
}

/**
 * Create a URI for camera capture
 */
private fun createImageUri(context: Context): Uri? {
    return try {
        val imagesDir = File(context.filesDir, "chat_images")
        if (!imagesDir.exists()) {
            imagesDir.mkdirs()
        }
        val imageFile = File(imagesDir, "camera_${System.currentTimeMillis()}.jpg")
        androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            imageFile
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to create image URI for camera capture", e)
        null
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatByteSize(bytes: Long): String {
    return when {
        bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
        bytes > 0L -> "$bytes B"
        else -> "0 B"
    }
}

/**
 * Dialog to choose image source (gallery or camera)
 */
@Composable
fun ImageSourceDialog(
    onDismiss: () -> Unit,
    onPhotosSelect: () -> Unit,
    onCameraCapture: () -> Unit,
    canAddMore: Boolean
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Image") },
        text = {
            Column {
                if (!canAddMore) {
                    Text(
                        text = "Maximum images reached",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        text = "Choose image source",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onCameraCapture,
                    enabled = canAddMore
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Camera")
                }
                Button(
                    onClick = onPhotosSelect,
                    enabled = canAddMore
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Photos")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Preview of selected images with remove buttons
 */
@Composable
fun SelectedImagesPreview(
    imagePaths: List<String>,
    onRemoveImage: (String) -> Unit,
    onClearAll: () -> Unit,
    canAddMore: Boolean,
    onAddMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${imagePaths.size} image${if (imagePaths.size > 1) "s" else ""} selected",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = onClearAll,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("Clear all", style = MaterialTheme.typography.labelSmall)
            }
        }
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            imagePaths.forEach { imagePath ->
                Box {
                    Image(
                        painter = rememberAsyncImagePainter(
                            ImageRequest.Builder(LocalContext.current)
                                .data(File(imagePath))
                                .build()
                        ),
                        contentDescription = "Selected image",
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    IconButton(
                        onClick = { onRemoveImage(imagePath) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(20.dp)
                            .background(
                                MaterialTheme.colorScheme.errorContainer,
                                CircleShape
                            )
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove",
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
            
            // Add more button
            if (canAddMore) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onAddMore() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add more",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Preview strip for audio attachments queued via Audio Scribe.
 */
@Composable
fun SelectedAudioPreview(
    audioPaths: List<String>,
    onRemoveAudio: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        audioPaths.forEach { audioPath ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.AudioFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = File(audioPath).name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { onRemoveAudio(audioPath) },
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Remove audio",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Grid display for images in message bubbles
 */
@Composable
fun MessageImageGrid(
    imagePaths: List<String>,
    modifier: Modifier = Modifier
) {
    when (imagePaths.size) {
        0 -> { /* Nothing to display */ }
        1 -> {
            Image(
                painter = rememberAsyncImagePainter(
                    ImageRequest.Builder(LocalContext.current)
                        .data(File(imagePaths[0]))
                        .build()
                ),
                contentDescription = "Attached image",
                modifier = modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Fit
            )
        }
        2 -> {
            Row(
                modifier = modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                imagePaths.forEach { path ->
                    Image(
                        painter = rememberAsyncImagePainter(
                            ImageRequest.Builder(LocalContext.current)
                                .data(File(path))
                                .build()
                        ),
                        contentDescription = "Attached image",
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(max = 150.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
        else -> {
            // 3 or 4 images - 2x2 grid
            Column(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (rowIndex in 0 until 2) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for (colIndex in 0 until 2) {
                            val imageIndex = rowIndex * 2 + colIndex
                            if (imageIndex < imagePaths.size) {
                                Image(
                                    painter = rememberAsyncImagePainter(
                                        ImageRequest.Builder(LocalContext.current)
                                            .data(File(imagePaths[imageIndex]))
                                            .build()
                                    ),
                                    contentDescription = "Attached image ${imageIndex + 1}",
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(max = 100.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

// Phase 2 (§3.4): ProductivityTemplateQuickBar removed per spec. The TODO_LIST
// and MEETING_NOTES templates are still selectable from the prompt library
// inside the `+` menu via PromptTemplates registry.

/**
 * Quick action buttons for common vision tasks
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisionQuickActionsBar(
    activeAction: VisionQuickAction?,
    onActionSelected: (VisionQuickAction?) -> Unit
) {
    Surface(
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            VisionQuickAction.entries.forEach { action ->
                val isActive = activeAction == action
                FilterChip(
                    selected = isActive,
                    onClick = { 
                        onActionSelected(if (isActive) null else action)
                    },
                    label = { Text(action.displayName) },
                    leadingIcon = {
                        Icon(
                            imageVector = action.icon,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
        }
    }
}

/**
 * Indicator showing the currently selected prompt template
 */
@Composable
fun SelectedTemplateIndicator(
    template: PromptTemplate,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = template.icon,
                style = MaterialTheme.typography.titleMedium
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = template.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = template.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
            IconButton(
                onClick = onClear,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Clear template",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Bottom sheet for selecting prompt templates
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptTemplateBottomSheet(
    onDismiss: () -> Unit,
    onTemplateSelect: (PromptTemplate) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedCategory by remember { mutableStateOf<PromptCategory?>(null) }
    val templatesByCategory = remember { PromptTemplates.getTemplatesByCategory() }
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxHeight(0.85f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            // Header
            Text(
                text = "Prompt Templates",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "Select a template to enhance your prompt",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            // Category chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = { selectedCategory = null },
                    label = { Text("All") }
                )
                PromptCategory.entries.forEach { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                        label = { Text("${category.icon} ${category.displayName}") }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Templates list
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                val templates = if (selectedCategory != null) {
                    templatesByCategory[selectedCategory] ?: emptyList()
                } else {
                    PromptTemplates.getAllTemplates()
                }
                
                items(templates) { template ->
                    PromptTemplateCard(
                        template = template,
                        onClick = { onTemplateSelect(template) }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Card displaying a single prompt template
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptTemplateCard(
    template: PromptTemplate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Icon
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                modifier = Modifier.size(48.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text(
                        text = template.icon,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }
            
            // Content
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = template.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = template.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                // Category tag
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text(
                        text = "${template.category.icon} ${template.category.displayName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * Bubble showing a tool call detected in the LLM response
 */
@Composable
fun ToolCallBubble(
    toolCall: ToolCall,
    result: ToolResult? = null,
    isPending: Boolean = false,
    onApprove: () -> Unit = {},
    onReject: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val tool = MobileTools.getToolByName(toolCall.name)
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Build,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "Tool Call: ${tool?.name ?: toolCall.name}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            
            // Tool description
            tool?.let {
                Text(
                    text = it.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            
            // Parameters
            if (toolCall.arguments.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Text(
                            text = "Parameters:",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        toolCall.arguments.forEach { (key, value) ->
                            Text(
                                text = "• $key: $value",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
            
            // Result (if executed)
            result?.let { res ->
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = if (res.success) {
                        Color(0xFF4CAF50).copy(alpha = 0.2f)
                    } else {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                    },
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            if (res.success) Icons.Default.Check else Icons.Default.Close,
                            contentDescription = null,
                            tint = if (res.success) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (res.success) res.result?.toString() ?: "Success" else res.error ?: "Failed",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (res.success) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            
            // Action buttons (if pending approval)
            if (isPending && result == null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    OutlinedButton(
                        onClick = onReject,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reject")
                    }
                    Button(
                        onClick = onApprove,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Execute")
                    }
                }
            }
        }
    }
}

/**
 * Settings toggle for enabling/disabling tool calling
 */
@Composable
fun ToolCallingToggle(
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Build,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Tool Calling",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = "Allow the AI to perform actions like calls, messages, alarms, and more",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle
            )
        }
    }
}

