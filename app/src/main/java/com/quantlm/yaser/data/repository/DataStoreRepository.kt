package com.quantlm.yaser.data.repository

import com.quantlm.yaser.data.local.AppPreferences
import com.quantlm.yaser.data.local.GenerationPreferences
import com.quantlm.yaser.data.local.ModelPreferences
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single injectable facade over all DataStore preference classes.
 *
 * Replaces direct construction of AppPreferences / GenerationPreferences /
 * ModelPreferences with a Hilt-managed singleton so call-sites no longer
 * need a raw Context.  All reads are exposed as Flow<T> and all writes are
 * suspend functions — no blocking IO.
 *
 * Fix [2.1]: callers that need a one-shot value should use `flow.first()` in
 * a coroutine (e.g. `viewModelScope.launch { dataStore.getX().first() }`), not `runBlocking`.
 */
@Singleton
class DataStoreRepository @Inject constructor(
    private val appPreferences: AppPreferences,
    private val generationPreferences: GenerationPreferences,
    private val modelPreferences: ModelPreferences
) {

    // ─────────────────────────── App Settings ─────────────────────────────

    fun getAppSettings(): Flow<AppPreferences.AppSettings> =
        appPreferences.getSettings()

    suspend fun setAppLockEnabled(enabled: Boolean) =
        appPreferences.setAppLockEnabled(enabled)

    suspend fun setAppLockType(type: AppPreferences.AppLockType) =
        appPreferences.setAppLockType(type)

    suspend fun setPin(pin: String) = appPreferences.setPin(pin)

    suspend fun setPassword(password: String) = appPreferences.setPassword(password)

    suspend fun setPattern(pattern: String) = appPreferences.setPattern(pattern)

    suspend fun setBiometricEnabled(enabled: Boolean) =
        appPreferences.setBiometricEnabled(enabled)

    suspend fun setLockTimeout(seconds: Int) = appPreferences.setLockTimeout(seconds)

    suspend fun verifyPin(pin: String): Boolean = appPreferences.verifyPin(pin)

    suspend fun verifyPassword(password: String): Boolean =
        appPreferences.verifyPassword(password)

    suspend fun verifyPattern(pattern: String): Boolean =
        appPreferences.verifyPattern(pattern)

    fun getStoredPin(): Flow<String?> = appPreferences.getStoredPin()
    fun getStoredPassword(): Flow<String?> = appPreferences.getStoredPassword()
    fun getStoredPattern(): Flow<String?> = appPreferences.getStoredPattern()

    suspend fun setOnboardingCompleted(completed: Boolean) =
        appPreferences.setOnboardingCompleted(completed)

    fun isOnboardingCompleted(): Flow<Boolean> = appPreferences.isOnboardingCompleted()

    suspend fun clearAppLock() = appPreferences.clearAppLock()

    suspend fun resetAppPreferences() = appPreferences.resetAll()

    suspend fun setUseModernUi(enabled: Boolean) = appPreferences.setUseModernUi(enabled)

    // ──────────────────────── Generation Settings ─────────────────────────

    fun getGenerationSettings(): Flow<GenerationPreferences.GenerationSettings> =
        generationPreferences.getSettings()

    suspend fun saveGenerationSettings(settings: GenerationPreferences.GenerationSettings) =
        generationPreferences.saveSettings(settings)

    suspend fun setTemperature(temperature: Float) =
        generationPreferences.saveTemperature(temperature)

    suspend fun setMaxTokens(maxTokens: Int) =
        generationPreferences.saveMaxTokens(maxTokens)

    suspend fun setTopP(topP: Float) = generationPreferences.saveTopP(topP)

    suspend fun setTopK(topK: Int) = generationPreferences.saveTopK(topK)

    suspend fun setSystemPrompt(prompt: String) =
        generationPreferences.saveSystemPrompt(prompt)

    suspend fun setRepeatPenalty(penalty: Float) =
        generationPreferences.saveRepeatPenalty(penalty)

    // ─────────────────────────── Model Settings ───────────────────────────

    fun getLoadedModel(): Flow<ModelPreferences.LoadedModelInfo?> =
        modelPreferences.getLoadedModel()

    suspend fun saveLoadedModel(
        name: String,
        filePath: String,
        size: Long,
        isVisionModel: Boolean = false,
        mmprojPath: String? = null
    ) = modelPreferences.saveLoadedModel(name, filePath, size, isVisionModel, mmprojPath)

    suspend fun clearLoadedModel() = modelPreferences.clearLoadedModel()
}
