package com.quantlm.yaser.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.quantlm.yaser.data.diagnostics.AppEventLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "model_preferences")

class ModelPreferences(private val context: Context) {
    
    companion object {
        private const val TAG = "ModelPreferences"

        private val MODEL_NAME = stringPreferencesKey("loaded_model_name")
        private val MODEL_PATH = stringPreferencesKey("loaded_model_path")
        private val MODEL_SIZE = longPreferencesKey("loaded_model_size")
        private val IS_VISION_MODEL = booleanPreferencesKey("is_vision_model")
        private val MMPROJ_PATH = stringPreferencesKey("mmproj_path")
    }
    
    data class LoadedModelInfo(
        val name: String,
        val filePath: String,
        val size: Long,
        val isVisionModel: Boolean = false,
        val mmprojPath: String? = null
    )
    
    suspend fun saveLoadedModel(
        name: String, 
        filePath: String, 
        size: Long,
        isVisionModel: Boolean = false,
        mmprojPath: String? = null
    ) {
        context.dataStore.edit { preferences ->
            preferences[MODEL_NAME] = name
            preferences[MODEL_PATH] = filePath
            preferences[MODEL_SIZE] = size
            preferences[IS_VISION_MODEL] = isVisionModel
            if (mmprojPath != null) {
                preferences[MMPROJ_PATH] = mmprojPath
            } else {
                preferences.remove(MMPROJ_PATH)
            }
        }
        AppEventLogger.info(
            component = TAG,
            action = "save_loaded_model",
            details = "name=$name, size=$size, vision=$isVisionModel, hasMmproj=${mmprojPath != null}"
        )
    }
    
    fun getLoadedModel(): Flow<LoadedModelInfo?> {
        return context.dataStore.data.map { preferences ->
            val name = preferences[MODEL_NAME]
            val path = preferences[MODEL_PATH]
            val size = preferences[MODEL_SIZE]
            
            if (name != null && path != null && size != null) {
                LoadedModelInfo(
                    name = name, 
                    filePath = path, 
                    size = size,
                    isVisionModel = preferences[IS_VISION_MODEL] ?: false,
                    mmprojPath = preferences[MMPROJ_PATH]
                )
            } else {
                null
            }
        }
    }
    
    suspend fun clearLoadedModel() {
        context.dataStore.edit { preferences ->
            preferences.remove(MODEL_NAME)
            preferences.remove(MODEL_PATH)
            preferences.remove(MODEL_SIZE)
            preferences.remove(IS_VISION_MODEL)
            preferences.remove(MMPROJ_PATH)
        }
        AppEventLogger.info(component = TAG, action = "clear_loaded_model")
    }
}
