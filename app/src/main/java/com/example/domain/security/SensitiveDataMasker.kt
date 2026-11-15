package com.example.domain.security

import java.util.regex.Matcher
import java.util.regex.Pattern

data class MaskResult(
    val maskedText: String,
    val redactionsCount: Int,
    val redactedCategories: List<String>
)

/**
 * Deterministic, multi-layer sensitive data masker applied before writing any
 * agent execution or script activity record into encrypted local or cloud logs.
 */
object SensitiveDataMasker {

    private data class MaskRule(
        val category: String,
        val pattern: Pattern,
        val replacer: (MatchResultWrapper) -> String
    )

    private class MatchResultWrapper(val matcher: Matcher) {
        fun full(): String = matcher.group(0).orEmpty()
        fun group(idx: Int): String = if (idx <= matcher.groupCount()) matcher.group(idx).orEmpty() else ""
    }

    private val RULES: List<MaskRule> = listOf(
        // 1. Private Key PEM blocks (multi-line)
        MaskRule(
            category = "PRIVATE_KEY",
            pattern = Pattern.compile("-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----"),
            replacer = { "[MASKED_PRIVATE_KEY]" }
        ),
        // 2. Google API Keys
        MaskRule(
            category = "GOOGLE_API_KEY",
            pattern = Pattern.compile("AIza[0-9A-Za-z\\-_]{35}"),
            replacer = { "[MASKED_GOOGLE_API_KEY]" }
        ),
        // 3. GitHub PATs (classic & fine-grained)
        MaskRule(
            category = "GITHUB_TOKEN",
            pattern = Pattern.compile("\\b(gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{30,})\\b"),
            replacer = { "[MASKED_GITHUB_TOKEN]" }
        ),
        // 4. AWS Access Key IDs
        MaskRule(
            category = "AWS_KEY",
            pattern = Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            replacer = { "[MASKED_AWS_KEY]" }
        ),
        // 5. Slack Tokens
        MaskRule(
            category = "SLACK_TOKEN",
            pattern = Pattern.compile("\\bxox[baprs]-[0-9A-Za-z\\-]{10,}\\b"),
            replacer = { "[MASKED_SLACK_TOKEN]" }
        ),
        // 6. OpenAI / Anthropic / Stripe sk- / rk- keys
        MaskRule(
            category = "API_KEY",
            pattern = Pattern.compile("\\b(sk|rk)_(live|test)_[A-Za-z0-9]{16,}|\\bsk-[A-Za-z0-9_\\-]{20,}"),
            replacer = { "[MASKED_API_KEY]" }
        ),
        // 7. JSON Web Tokens (JWT)
        MaskRule(
            category = "JWT",
            pattern = Pattern.compile("\\beyJ[A-Za-z0-9_\\-]{8,}\\.eyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}\\b"),
            replacer = { "[MASKED_JWT]" }
        ),
        // 8. Authorization / Bearer headers
        MaskRule(
            category = "BEARER_TOKEN",
            pattern = Pattern.compile("(?i)(\\bBearer\\s+)([A-Za-z0-9\\-._~+/]+=*)"),
            replacer = { m -> "${m.group(1)}[MASKED_BEARER_TOKEN]" }
        ),
        // 9. URL embedded basic auth credentials: https://user:pass@host
        MaskRule(
            category = "URL_CREDENTIALS",
            pattern = Pattern.compile("(https?://)([^/\\s:@]+):([^/\\s@]+)@"),
            replacer = { m -> "${m.group(1)}[MASKED_USER]:[MASKED_PASS]@" }
        ),
        // 10. URL query parameters carrying secrets
        MaskRule(
            category = "URL_SECRET_PARAM",
            pattern = Pattern.compile("(?i)([?&](?:api_?key|key|token|access_token|secret|password|auth|signature)=)([^&\\s\"']+)"),
            replacer = { m -> "${m.group(1)}[MASKED_PARAM]" }
        ),
        // 11. Inline key/secret/password assignments in code, env, or JSON
        MaskRule(
            category = "CREDENTIAL_ASSIGNMENT",
            pattern = Pattern.compile(
                "(?i)(\"?\\b(?:password|passwd|pwd|secret|client_secret|api_?key|access_?token|auth_?token|private_?key|x-goog-api-key)\\b\"?\\s*[:=]\\s*[\"']?)([^\"'\\s,;}\\]]+)"
            ),
            replacer = { m ->
                val value = m.group(2)
                if (value.startsWith("[MASKED_")) m.full() else "${m.group(1)}[MASKED_CREDENTIAL]"
            }
        ),
        // 12. Email addresses (preserve domain & first 2 chars for diagnostics)
        MaskRule(
            category = "EMAIL",
            pattern = Pattern.compile("\\b([a-zA-Z0-9._%+\\-]+)@([a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,})\\b"),
            replacer = { m ->
                val local = m.group(1)
                val domain = m.group(2)
                val prefix = if (local.length > 2) local.take(2) + "***" else "***"
                "$prefix@$domain"
            }
        ),
        // 13. SSN-like patterns
        MaskRule(
            category = "SSN",
            pattern = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"),
            replacer = { "***-**-****" }
        ),
        // 14. Credit card / 16-digit PAN sequences
        MaskRule(
            category = "PAYMENT_CARD",
            pattern = Pattern.compile("\\b(?:\\d{4}[\\s-]){3}(\\d{4})\\b"),
            replacer = { m -> "****-****-****-${m.group(1)}" }
        )
    )

    /**
     * Masks sensitive secrets, credentials, tokens, and PII in the provided text.
     */
    fun mask(raw: String): MaskResult {
        if (raw.isEmpty()) {
            return MaskResult(maskedText = "", redactionsCount = 0, redactedCategories = emptyList())
        }

        var current = raw
        var totalRedactions = 0
        val categories = linkedSetOf<String>()

        for (rule in RULES) {
            val matcher = rule.pattern.matcher(current)
            if (matcher.find()) {
                val sb = StringBuffer()
                matcher.reset()
                var matchedInRule = false
                while (matcher.find()) {
                    val replacement = rule.replacer(MatchResultWrapper(matcher))
                    if (replacement != matcher.group(0)) {
                        totalRedactions++
                        matchedInRule = true
                    }
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement))
                }
                matcher.appendTail(sb)
                current = sb.toString()
                if (matchedInRule) {
                    categories.add(rule.category)
                }
            }
        }

        return MaskResult(
            maskedText = current,
            redactionsCount = totalRedactions,
            redactedCategories = categories.toList()
        )
    }

    /**
     * Convenience helper returning masked text only.
     */
    fun maskText(raw: String): String = mask(raw).maskedText
}
