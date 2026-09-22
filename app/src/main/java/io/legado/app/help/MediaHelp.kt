package io.legado.app.help

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import io.legado.app.R
import splitties.systemservices.audioManager

object MediaHelp {

    const val MEDIA_SESSION_ACTIONS = (PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            or PlaybackStateCompat.ACTION_REWIND
            or PlaybackStateCompat.ACTION_PLAY
            or PlaybackStateCompat.ACTION_PLAY_PAUSE
            or PlaybackStateCompat.ACTION_PAUSE
            or PlaybackStateCompat.ACTION_STOP
            or PlaybackStateCompat.ACTION_FAST_FORWARD
            or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
            or PlaybackStateCompat.ACTION_SEEK_TO
            or PlaybackStateCompat.ACTION_SET_RATING
            or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
            or PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
            or PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM
            or PlaybackStateCompat.ACTION_PLAY_FROM_URI
            or PlaybackStateCompat.ACTION_PREPARE
            or PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID
            or PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH
            or PlaybackStateCompat.ACTION_PREPARE_FROM_URI
            or PlaybackStateCompat.ACTION_SET_REPEAT_MODE
            or PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE
            or PlaybackStateCompat.ACTION_SET_CAPTIONING_ENABLED)

    /**
     * 朗读/听书专用 action 集合: 比 [MEDIA_SESSION_ACTIONS] **只少一个 ACTION_SEEK_TO**。
     *
     * 朗读的「进度」单位是**段落序号**(nowSpeak), 不是时间, 系统拿到这个位置后会按时间轴
     * 去理解, 于是在通知/媒体控件里画出一条毫无意义的进度条(即用户反馈的「中间那条横条」)。
     * 朗读本来也无法用时间定位(服务的 MediaSession 回调里没有实现 onSeekTo), 去掉 SEEK_TO
     * 后系统不会渲染 seek 进度条。
     *
     * 除 SEEK_TO 外的能力全部保留: REWIND/FAST_FORWARD 关系到耳机媒体键, PREPARE* 与
     * 播放器 prepare 流程相关 —— 都不该因为要去掉一条进度条而被牵连。
     *
     * 注意: 这里不能直接改用/修改 [MEDIA_SESSION_ACTIONS] —— 那个集合是给音频播放
     * (AudioPlayService, 位置就是真实时间)和视频用的, 去掉 SEEK_TO 会丢拖进度能力。
     */
    const val READ_ALOUD_MEDIA_SESSION_ACTIONS = (PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            or PlaybackStateCompat.ACTION_REWIND
            or PlaybackStateCompat.ACTION_PLAY
            or PlaybackStateCompat.ACTION_PLAY_PAUSE
            or PlaybackStateCompat.ACTION_PAUSE
            or PlaybackStateCompat.ACTION_STOP
            or PlaybackStateCompat.ACTION_FAST_FORWARD
            or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
            or PlaybackStateCompat.ACTION_SET_RATING
            or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
            or PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
            or PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM
            or PlaybackStateCompat.ACTION_PLAY_FROM_URI
            or PlaybackStateCompat.ACTION_PREPARE
            or PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID
            or PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH
            or PlaybackStateCompat.ACTION_PREPARE_FROM_URI
            or PlaybackStateCompat.ACTION_SET_REPEAT_MODE
            or PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE
            or PlaybackStateCompat.ACTION_SET_CAPTIONING_ENABLED)

    fun buildAudioFocusRequestCompat(
        audioFocusChangeListener: AudioManager.OnAudioFocusChangeListener
    ): AudioFocusRequestCompat {
        val mPlaybackAttributes = AudioAttributesCompat.Builder()
            .setUsage(AudioAttributesCompat.USAGE_MEDIA)
            .setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
            .build()
        return AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
            .setAudioAttributes(mPlaybackAttributes)
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .build()
    }


    /**
     * @return 音频焦点
     */
    fun requestFocus(focusRequest: AudioFocusRequestCompat): Boolean {
        val request = AudioManagerCompat.requestAudioFocus(audioManager, focusRequest)
        return request == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /**
     * 播放静音音频,用来获取音频焦点
     */
    fun playSilentSound(mContext: Context) {
        kotlin.runCatching {
            // Stupid Android 8 "Oreo" hack to make media buttons work
            val mMediaPlayer = MediaPlayer.create(mContext, R.raw.silent_sound)
            mMediaPlayer.setOnCompletionListener { mMediaPlayer.release() }
            mMediaPlayer.start()
        }
    }
}