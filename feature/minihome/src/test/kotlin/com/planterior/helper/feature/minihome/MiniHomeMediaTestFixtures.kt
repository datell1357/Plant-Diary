package com.planterior.helper.feature.minihome

import com.planterior.helper.core.model.CatalogMediaIdentity
import com.planterior.helper.core.model.ItemCategory
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.Revision
import java.security.MessageDigest

internal fun testMiniHomeMediaIdentity(itemId: String, source: String = itemId) =
    MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray())
        .joinToString("") {
            "%02x".format(it)
        }
        .let { digest ->
            CatalogMediaIdentity(
                "catalog-assets/$itemId/$digest.webp",
                digest,
                4,
                "image/webp",
                1,
                1,
                Revision(1),
            )
        }

@Suppress("FunctionName")
internal fun MiniHomeDecorationChoice(
    id: ItemId,
    displayName: String,
    category: ItemCategory?,
    legacyAssetPath: String,
    availableForApplication: Boolean = true,
) =
    MiniHomeDecorationChoice(
        id,
        displayName,
        category,
        testMiniHomeMediaIdentity(id.value, legacyAssetPath),
        availableForApplication,
    )
