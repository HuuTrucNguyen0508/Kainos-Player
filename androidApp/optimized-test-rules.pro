# App-module instrumentation shares dependencies with the tested APK. R8 can
# remove static facades after inlining production calls, while precompiled test
# dependencies still link them. This file is used only by androidTestTarget.
-keep class androidx.tracing.Trace { *; }
-keep class kotlin.**Kt* { *; }
-keep class kotlinx.coroutines.** { *; }
# The playback test constructs a fake controller with default callbacks. Its
# default-argument constructor is not called by production code.
-keep class com.universalmusic.player.platform.SpotifyPlaybackController { public <init>(...); }
# The direct resolver test calls this KMP factory; production callers inline it.
-keep class com.universalmusic.player.platform.PlatformProvideryoutube_androidKt { *; }
# Direct tests call this provider contract and inspect its result. Keep that
# test boundary stable without retaining provider implementation internals.
-keep interface com.universalmusic.player.platform.YouTubeStreamResolver { *; }
-keep class com.universalmusic.player.platform.ResolvedYouTubeAudio { *; }
# The direct playback test observes this contract. Normal builds can erase it.
-keep interface com.universalmusic.player.domain.playback.PlaybackEngine { *; }
-keep class com.universalmusic.player.domain.playback.EngineState { *; }
-keep enum com.universalmusic.player.domain.playback.EngineStatus { *; }
