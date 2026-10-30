package com.torrenttv.app.ui

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize

/**
 * Единая телеметрия для всех трёх плееров (торрент/онлайн/ТВ): раньше каждый
 * плеер имел СВОЙ дублирующий набор Listener-методов с разной степенью
 * подробности — баг диагностики чинился в одном плеере и не доходил в другие.
 *
 * attach() вешает ПОЛНЫЙ набор событий (состояния, дорожки, кадры, ошибки)
 * поверх основного listener'а экрана: основной отвечает за поведение,
 * PlayerCore — за развёрнутый лог. Полезен и для единого формата строк:
 * одинаковые метки в app_log.txt у всех экранов.
 */
object PlayerCore {

    fun attach(player: androidx.media3.exoplayer.ExoPlayer, tag: String) {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val name = when (state) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "?$state"
                }
                com.torrenttv.app.data.AppLog.log(
                    tag,
                    "состояние: $name pos=${player.currentPosition / 1000}с буфер=${player.bufferedPosition / 1000}с",
                )
            }

            // «Как раскрашивается картинка»: разрешение/формат/кодек дорожки
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val f = player.videoFormat
                com.torrenttv.app.data.AppLog.log(
                    tag,
                    "видеодорожка: ${videoSize.width}x${videoSize.height}" +
                        (f?.sampleMimeType?.let { " формат=$it" } ?: "") +
                        (f?.codecs?.let { " кодек=$it" } ?: ""),
                )
            }

            override fun onRenderedFirstFrame() {
                com.torrenttv.app.data.AppLog.log(tag, "первый кадр отрисован pos=${player.currentPosition / 1000}с")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                com.torrenttv.app.data.AppLog.log(
                    tag,
                    if (isPlaying) "воспроизведение: ИДЁТ (${player.videoSize.width}x${player.videoSize.height})"
                    else "воспроизведение: пауза/стоп pos=${player.currentPosition / 1000}с",
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                com.torrenttv.app.data.AppLog.log(
                    tag,
                    "playback error: ${error.errorCodeName} pos=${player.currentPosition / 1000}с",
                )
            }
        })
    }
}
