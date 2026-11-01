package com.torrenttv.app.ui

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException

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

        // События загрузки: ExoPlayer сам рассказывает, что и откуда грузит
        // и ЧТО ПЛАНИРУЕТ дальше — по ним видно, где конвейер замолчал
        // («контент не показывает»: после чтения хвоста Cues транспортный
        // поток больше не читал и не сообщал READY — лог ТВ 0.9.65)
        // onLoad* живут в AnalyticsListener, не в Player.Listener
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onLoadStarted(
                eventTime: EventTime, loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
            ) {
                com.torrenttv.app.data.AppLog.log(
                    tag,
                    "load start: ${loadEventInfo.dataSpec.position}+${loadEventInfo.dataSpec.length}",
                )
            }

            override fun onLoadCompleted(
                eventTime: EventTime, loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
            ) {
                com.torrenttv.app.data.AppLog.log(
                    tag,
                    "load ok: ${loadEventInfo.dataSpec.position}+${loadEventInfo.dataSpec.length} " +
                        "байт=${loadEventInfo.bytesLoaded} за ${loadEventInfo.loadDurationMs}мс",
                )
            }

            override fun onLoadError(
                eventTime: EventTime, loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData, error: IOException,
                wasCanceled: Boolean,
            ) {
                com.torrenttv.app.data.AppLog.log(
                    tag,
                    "load FAIL: ${error.javaClass.simpleName}: ${error.message} (отменена=$wasCanceled)",
                )
            }
        })
    }
}
