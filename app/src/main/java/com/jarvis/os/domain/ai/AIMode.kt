package com.jarvis.os.domain.ai

/** How Sara chooses between online Gemini and a downloaded offline model. */
enum class AIMode {
    /** Online when network works; fall back to offline model if installed. */
    AUTO,
    /** Always use Gemini / online API. */
    ONLINE_ONLY,
    /** Never call online APIs; require a downloaded offline model. */
    OFFLINE_ONLY
}
