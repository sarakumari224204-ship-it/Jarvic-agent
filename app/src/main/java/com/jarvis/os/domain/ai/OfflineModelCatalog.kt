package com.jarvis.os.domain.ai

/**
 * Curated offline GGUF models for on-device llama.cpp.
 * Tuned for ~6 GB RAM phones: Lite is the safest default recommendation.
 */
object OfflineModelCatalog {

    val models: List<OfflineModelInfo> = listOf(
        OfflineModelInfo(
            id = "lite",
            displayName = "Lite",
            description = "Best for 6 GB RAM · fastest",
            family = "Qwen2.5 0.5B Instruct",
            approxSizeBytes = 450L * 1024 * 1024,
            approxSizeLabel = "~450 MB",
            recommended = true, // safest on 6GB
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            licenseNote = "Apache-2.0 (Qwen)"
        ),
        OfflineModelInfo(
            id = "balanced",
            displayName = "Balanced",
            description = "Good quality · needs free RAM",
            family = "Llama 3.2 1B Instruct",
            approxSizeBytes = 700L * 1024 * 1024,
            approxSizeLabel = "~700 MB",
            recommended = false,
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            licenseNote = "Llama 3.2 Community License"
        ),
        OfflineModelInfo(
            id = "quality",
            displayName = "Quality",
            description = "Best text · 6GB par risky",
            family = "Gemma 3 1B Instruct",
            approxSizeBytes = 850L * 1024 * 1024,
            approxSizeLabel = "~850 MB",
            recommended = false,
            downloadUrl = "https://huggingface.co/bartowski/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf",
            fileName = "gemma-3-1b-it-Q4_K_M.gguf",
            licenseNote = "Gemma Terms of Use"
        )
    )

    fun byId(id: String): OfflineModelInfo? = models.find { it.id == id }
}

data class OfflineModelInfo(
    val id: String,
    val displayName: String,
    val description: String,
    val family: String,
    val approxSizeBytes: Long,
    val approxSizeLabel: String,
    val recommended: Boolean,
    val downloadUrl: String,
    val fileName: String,
    val licenseNote: String
)
