# Media3/ExoPlayer: DefaultMediaSourceFactory грузит модули рефлексией
# (Class.forName: DashMediaSource$Factory и т.п.) — R8 их вырезает,
# на устройстве падает ClassNotFoundException. Держим классы.
-keep class androidx.media3.exoplayer.dash.** { *; }
-keep class androidx.media3.exoplayer.hls.** { *; }
-keep class androidx.media3.exoplayer.smoothstreaming.** { *; }
-keep class androidx.media3.extractor.** { *; }
-dontwarn androidx.media3.**
