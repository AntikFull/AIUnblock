package ru.ecubz.aiunblock

import java.util.Locale

enum class GatewayGroup {
    GOOGLE_AI,
    NOTEBOOK_LM,
    CHATGPT,
    CLAUDE,
    GEOHIDE,
}

object RoutingRules {
    private val googleAiDomains = setOf(
        "ai.google.dev",
        "aistudio.google.com",
        "aisandbox-pa.googleapis.com",
        "alkalicore-pa.clients6.google.com",
        "alkalimakersuite-pa.clients6.google.com",
        "antigravity.google",
        "assistant-s3-pa.googleapis.com",
        "bard.google.com",
        "gemini.google",
        "gemini.google.com",
        "generativelanguage.googleapis.com",
        "proactivebackend-pa.googleapis.com",
        "robinfrontend-pa.googleapis.com",
        "signaler-pa.googleapis.com",
        "webchannel-alkalimakersuite-pa.clients6.google.com",
    )

    private val notebookDomains = setOf(
        "notebooklm-pa.googleapis.com",
    )

    private val openAiDomains = setOf(
        "chatgpt.com",
        "oaistatic.com",
        "oaiusercontent.com",
        "openai.com",
    )

    private val claudeDomains = setOf(
        "anthropic.com",
        "claude.ai",
        "claude.com",
    )

    fun gatewayFor(host: String?): GatewayGroup? {
        val normalized = host
            ?.trim()
            ?.trimEnd('.')
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        return when {
            normalized in notebookDomains ->
                GatewayGroup.NOTEBOOK_LM

            // Google AI endpoints are deliberately exact-only. This keeps Search,
            // Discover, Lens and unrelated Google App traffic on the direct path.
            normalized in googleAiDomains ->
                GatewayGroup.GOOGLE_AI

            openAiDomains.any { normalized == it || normalized.endsWith(".$it") } ->
                GatewayGroup.CHATGPT

            claudeDomains.any { normalized == it || normalized.endsWith(".$it") } ->
                GatewayGroup.CLAUDE

            else -> null
        }
    }
}
