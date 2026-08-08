package com.hillelsht.smart.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Resolves a Wikipedia article title to an image URL.
 *
 * Exposed as a composition local so that any card, anywhere in the tree, can show a picture
 * without every intermediate screen having to thread a repository through its parameters.
 */
fun interface ImageResolver {
    suspend fun resolve(wikiTitle: String): String?
}

val LocalImageResolver = staticCompositionLocalOf<ImageResolver> {
    // Previews and tests get a resolver that simply never finds an image, so composables fall
    // back to their gradient placeholders instead of crashing.
    ImageResolver { null }
}
