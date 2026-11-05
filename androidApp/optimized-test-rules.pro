# Used only by -PandroidTestTarget=optimized. Instrumentation links against the
# app APK's members, and R8 inlines, merges or removes what only tests call.
# Keep exactly that test-facing surface; everything else still goes through R8.
-keep class androidx.tracing.Trace { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class com.universalmusic.player.platform.PlatformCoremodel_androidKt { *; }
-keep class com.universalmusic.player.platform.PlatformProviderlocal_androidKt { *; }
-keep class com.universalmusic.player.platform.PlatformProvideryoutube_androidKt { *; }
-keep class com.universalmusic.player.platform.SpotifyPlaybackController { *; }
-keep class com.universalmusic.player.platform.AndroidPlaybackEngine { *; }
-keep class com.universalmusic.player.platform.AndroidYouTubeStreamResolver { *; }
-keep interface com.universalmusic.player.platform.YouTubeStreamResolver { *; }
-keep class com.universalmusic.player.platform.ResolvedYouTubeAudio { *; }
-keep interface com.universalmusic.player.platform.DeviceIdentity { *; }
-keep class com.universalmusic.player.platform.AndroidDeviceIdentity { *; }
-keep class com.universalmusic.player.data.sync.AndroidHomeLanVaultStore { *; }
-keep class com.universalmusic.player.data.sync.VaultIndexDocument { *; }
-keep class com.universalmusic.player.data.sync.VaultFileEntry { *; }
-keep class com.universalmusic.player.data.sync.CertFingerprint { *; }
-keep interface com.universalmusic.player.data.local.LocalTrackSource { *; }
-keep class com.universalmusic.player.data.local.LocalTrack { *; }
-keep class com.universalmusic.player.data.local.LocalLibraryScanConfig { *; }
-keep interface com.universalmusic.player.domain.model.PlaybackHandle { *; }
-keep class com.universalmusic.player.domain.model.PlaybackHandle$* { *; }
-keep enum com.universalmusic.player.domain.model.ProviderId { *; }
-keep interface com.universalmusic.player.domain.playback.PlaybackEngine { *; }
-keep class com.universalmusic.player.domain.playback.EngineState { *; }
-keep enum com.universalmusic.player.domain.playback.EngineStatus { *; }
