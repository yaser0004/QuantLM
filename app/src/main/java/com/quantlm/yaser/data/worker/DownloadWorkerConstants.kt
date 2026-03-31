package com.quantlm.yaser.data.worker

/**
 * Constants for WorkManager download operations.
 * Shared between DownloadWorker and WorkManagerDownloadRepository.
 */
object DownloadWorkerConstants {
    // Input data keys
    const val KEY_MODEL_ID = "model_id"
    const val KEY_MODEL_NAME = "model_name"
    const val KEY_MODEL_URL = "model_url"
    const val KEY_MODEL_FILE_NAME = "model_file_name"
    const val KEY_MODEL_ACCESS_TOKEN = "model_access_token"
    const val KEY_MODEL_TOTAL_BYTES = "model_total_bytes"
    const val KEY_MODEL_VERSION = "model_version"
    
    // Extra data files (comma-separated)
    const val KEY_EXTRA_DATA_URLS = "extra_data_urls"
    const val KEY_EXTRA_DATA_FILE_NAMES = "extra_data_file_names"
    const val KEY_EXTRA_DATA_SIZES = "extra_data_sizes"
    
    // Progress data keys
    const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
    const val KEY_TOTAL_BYTES = "total_bytes"
    const val KEY_DOWNLOAD_RATE = "download_rate"
    const val KEY_REMAINING_MS = "remaining_ms"
    const val KEY_CURRENT_FILE_INDEX = "current_file_index"
    const val KEY_TOTAL_FILE_COUNT = "total_file_count"
    
    // Output data keys
    const val KEY_ERROR_MESSAGE = "error_message"
    
    // Work tags
    const val TAG_MODEL_NAME_PREFIX = "modelName:"
    const val TAG_MODEL_ID_PREFIX = "modelId:"
    
    // Temp file extension
    const val TMP_FILE_EXT = "agdownload"
    
    // Notification
    const val NOTIFICATION_CHANNEL_ID = "quantlm_download_channel"
    const val NOTIFICATION_CHANNEL_NAME = "Model Downloads"
}
