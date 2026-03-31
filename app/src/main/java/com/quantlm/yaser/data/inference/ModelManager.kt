package com.quantlm.yaser.data.inference

import android.content.Context
import android.util.Log
import com.quantlm.yaser.domain.model.AvailableModels
import com.quantlm.yaser.domain.model.ModelInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "ModelManager"
        private const val MODELS_DIR = "models"
    }

    private val modelsDirectory: File by lazy {
        File(context.filesDir, MODELS_DIR).apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    // Map of model filenames to their downloadable model info (for vision support detection)
    private val downloadableModelsMap by lazy {
        AvailableModels.getAllModels().associateBy { it.fileName }
    }
    // Supported model file extensions (across all formats)
    private val supportedExtensions = setOf("gguf", "tflite", "literlm", "litertlm", "task")

    suspend fun getAvailableModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Scanning models directory: ${modelsDirectory.absolutePath}")
            Log.d(TAG, "Directory exists: ${modelsDirectory.exists()}, isDirectory: ${modelsDirectory.isDirectory}")
            
            val allFiles = modelsDirectory.listFiles() ?: emptyArray()
            Log.d(TAG, "Total files in directory: ${allFiles.size}")
            allFiles.forEach { file ->
                Log.d(TAG, "  - ${file.name} (isFile: ${file.isFile}, ext: ${file.extension}, size: ${file.length()})")
            }
            
            val modelFiles = allFiles.filter { file ->
                val extension = file.extension.lowercase()
                val isValidModel = file.isFile && 
                    // Support all model formats (GGUF, TFLite/LiteRT, etc.)
                    supportedExtensions.contains(extension) &&
                    // Exclude mmproj files from main model list
                    !file.name.contains("mmproj", ignoreCase = true) &&
                    // Exclude temp files
                    !file.name.endsWith(".tmp", ignoreCase = true)
                Log.d(TAG, "Checking ${file.name}: ext=$extension, isValidModel=$isValidModel")
                isValidModel
            }.toTypedArray()

            Log.d(TAG, "Found ${modelFiles.size} valid model files after filtering")

            modelFiles.map { file ->
                // Check if this is a vision model by looking up in downloadable models
                val downloadableModel = downloadableModelsMap[file.name]
                val isVisionCapable = downloadableModel?.isVisionModel == true
                val mmprojFileName = downloadableModel?.mmprojFileName
                val mmprojExpectedSize = downloadableModel?.mmprojFileSize ?: 0L
                val requiresMmproj = isVisionCapable && mmprojFileName != null

                Log.d(TAG, "Model: ${file.name}, found in registry: ${downloadableModel != null}, isVisionCapable: $isVisionCapable")

                // For vision models, check if mmproj file exists (not just temp file)
                val mmprojPath = if (requiresMmproj && mmprojFileName != null) {
                    val mmprojFile = File(modelsDirectory, mmprojFileName)
                    Log.d(TAG, "Checking mmproj: $mmprojFileName, exists: ${mmprojFile.exists()}, size: ${mmprojFile.length()}, expected: $mmprojExpectedSize")
                    // Check the file exists AND has reasonable size (at least 90%)
                    if (mmprojFile.exists() && mmprojFile.length() >= mmprojExpectedSize * 0.90) {
                        Log.d(TAG, "mmproj file valid!")
                        mmprojFile.absolutePath
                    } else {
                        Log.d(TAG, "mmproj file missing or truncated")
                        null
                    }
                } else null

                val hasRequiredVisionAssets = if (!isVisionCapable) {
                    false
                } else {
                    !requiresMmproj || mmprojPath != null
                }

                // Vision is incomplete if model supports vision but mmproj is missing
                val isVisionIncomplete = isVisionCapable && !hasRequiredVisionAssets

                val result = ModelInfo(
                    name = file.nameWithoutExtension,
                    filePath = file.absolutePath,
                    size = file.length(),
                    isLoaded = false,
                    isVisionModel = hasRequiredVisionAssets,
                    mmprojPath = mmprojPath,
                    isVisionIncomplete = isVisionIncomplete
                )
                Log.i(TAG, "Detected local model: ${result.name}, size: ${result.size}, isVision=${result.isVisionModel}, isVisionIncomplete=${result.isVisionIncomplete}")
                result
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting available models", e)
            emptyList()
        }
    }

    suspend fun deleteModel(filePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (file.exists() && file.delete()) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to delete model file"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting model", e)
            Result.failure(e)
        }
    }

    suspend fun getModelInfo(filePath: String): ModelInfo? = withContext(Dispatchers.IO) {
        try {
            val file = File(filePath)
            if (file.exists()) {
                ModelInfo(
                    name = file.nameWithoutExtension,
                    filePath = file.absolutePath,
                    size = file.length()
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting model info", e)
            null
        }
    }
}
