package com.planterior.helper.identify

internal fun decodeIdentificationRequestAcknowledgement(
    value: Any?
): IdentificationRequestAcknowledgement {
    val root = value as? Map<*, *> ?: throw malformedIdentificationResponse()
    if (
        root.keys.map { it as? String }.toSet() !=
            setOf(
                "requestId",
                "disclosureVersion",
                "acknowledgedAtMillis",
                "createdAtMillis",
                "hardExpiresAtMillis",
            )
    ) {
        throw malformedIdentificationResponse()
    }
    return IdentificationRequestAcknowledgement(
        requestId = root["requestId"] as? String ?: throw malformedIdentificationResponse(),
        disclosureVersion = exactInt(root["disclosureVersion"]),
        acknowledgedAtMillis = exactTimestampMillis(root["acknowledgedAtMillis"]),
        createdAtMillis = exactTimestampMillis(root["createdAtMillis"]),
        hardExpiresAtMillis = exactTimestampMillis(root["hardExpiresAtMillis"]),
    )
}

private fun exactInt(value: Any?): Int {
    val parsed = exactIntegralLong(value)
    if (parsed !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        throw malformedIdentificationResponse()
    }
    return parsed.toInt()
}

private fun exactTimestampMillis(value: Any?): Long {
    val parsed = exactIntegralLong(value)
    if (parsed !in 0L..MAX_FIREBASE_TIMESTAMP_MILLIS) {
        throw malformedIdentificationResponse()
    }
    return parsed
}

private fun exactIntegralLong(value: Any?): Long =
    when (value) {
        is Long -> value
        is Double -> {
            if (
                !value.isFinite() ||
                    value % 1.0 != 0.0 ||
                    value < -MAX_SAFE_DOUBLE_INTEGER ||
                    value > MAX_SAFE_DOUBLE_INTEGER
            ) {
                throw malformedIdentificationResponse()
            }
            value.toLong().also { parsed ->
                if (parsed.toDouble() != value) throw malformedIdentificationResponse()
            }
        }
        else -> throw malformedIdentificationResponse()
    }

private fun malformedIdentificationResponse() =
    IdentificationHandoffException(IdentificationHandoffFailure.RequestFailed)

private const val MAX_SAFE_DOUBLE_INTEGER = 9_007_199_254_740_991.0
private const val MAX_FIREBASE_TIMESTAMP_MILLIS = 253_402_300_799_999L
