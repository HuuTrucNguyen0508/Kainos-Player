package com.universalmusic.player.ui.components

import coil3.SingletonImageLoader
import coil3.PlatformContext
import coil3.request.ImageRequest
import com.universalmusic.player.domain.model.Artwork

/** Share the display loader so neighbours populate its fetch/decode caches. */
internal suspend fun warmQueueArtwork(context: PlatformContext, artwork: Artwork) {
    SingletonImageLoader.get(context).execute(
        ImageRequest.Builder(context).data(artwork.url).size(512).build(),
    )
}
