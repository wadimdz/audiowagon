/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media.session.MediaButtonReceiver
import de.moleman1024.audiowagon.NOTIFICATION_ID
import de.moleman1024.audiowagon.R
import de.moleman1024.audiowagon.broadcast.*
import de.moleman1024.audiowagon.exceptions.MissingNotifChannelException
import de.moleman1024.audiowagon.log.CrashReporting
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

private const val TAG = "AudioSessionNotif"
private val logger = Logger
private const val AUDIO_SESS_NOTIF_CHANNEL: String = "AudioSessNotifChan"

/**
 * See https://developer.android.com/training/notify-user/channels
 */
class AudioSessionNotifications(
    private val context: Context,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    audioPlayer: AudioPlayer,
    crashReporting: CrashReporting
) {
    var currentQueueItem: MediaSessionCompat.QueueItem? = null
    private var mediaSession: MediaSessionCompat? = null
    private var notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private lateinit var isPlayingNotificationBuilder: NotificationCompat.Builder
    private lateinit var isPausedNotificationBuilder: NotificationCompat.Builder
    private var isShowingNotification: Boolean = false
    private val broadcastMsgRecv: BroadcastMessageReceiver = BroadcastMessageReceiver(
        audioPlayer, scope, dispatcher, crashReporting
    )
    private var isChannelCreated: Boolean = false

    fun init(session: MediaSessionCompat) {
        isChannelCreated = false
        mediaSession = session
        deleteNotificationChannel()
        createNotificationChannel()
        prepareMediaNotifications()
    }

    private fun createNotificationChannel() {
        if (notificationManager.getNotificationChannel(AUDIO_SESS_NOTIF_CHANNEL) != null) {
            isChannelCreated = true
            logger.debug(TAG, "Notification channel already exists")
            notificationManager.cancelAll()
            return
        }
        logger.debug(TAG, "Creating notification channel")
        val importance = NotificationManager.IMPORTANCE_LOW
        val notifChannelName = context.getString(R.string.notif_channel_name)
        val notifChannelDesc = context.getString(R.string.notif_channel_desc)
        val channel = NotificationChannel(AUDIO_SESS_NOTIF_CHANNEL, notifChannelName, importance).apply {
            description = notifChannelDesc
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
        isChannelCreated = true
    }

    private fun deleteNotificationChannel() {
        if (notificationManager.getNotificationChannel(AUDIO_SESS_NOTIF_CHANNEL) == null) {
            isChannelCreated = false
            return
        }
        logger.debug(TAG, "Deleting notification channel")
        try {
            isChannelCreated = false
            notificationManager.deleteNotificationChannel(AUDIO_SESS_NOTIF_CHANNEL)
        } catch (exc: RuntimeException) {
            // SecurityException could happen when service is still in foreground
            logger.exception(TAG, exc.message.toString(), exc)
        }
    }

    private fun prepareMediaNotifications() {
        // TODO: this seems to have no effect in Android Automotive
        val playIcon = IconCompat.createWithResource(context, R.drawable.baseline_play_arrow_24)
        val playAction = createNotificationAction(ACTION_PLAY, playIcon, R.string.notif_action_play)
        val pauseIcon = IconCompat.createWithResource(context, R.drawable.baseline_pause_24)
        val pauseAction = createNotificationAction(ACTION_PAUSE, pauseIcon, R.string.notif_action_pause)
        val nextIcon = IconCompat.createWithResource(context, R.drawable.baseline_skip_next_24)
        val nextAction = createNotificationAction(ACTION_NEXT, nextIcon, R.string.notif_action_next)
        val prevIcon = IconCompat.createWithResource(context, R.drawable.baseline_skip_previous_24)
        val prevAction = createNotificationAction(ACTION_PREV, prevIcon, R.string.notif_action_prev)
        isPlayingNotificationBuilder = NotificationCompat.Builder(context, AUDIO_SESS_NOTIF_CHANNEL).apply {
            addAction(prevAction)
            addAction(pauseAction)
            addAction(nextAction)
        }
        isPausedNotificationBuilder = NotificationCompat.Builder(context, AUDIO_SESS_NOTIF_CHANNEL).apply {
            addAction(prevAction)
            addAction(playAction)
            addAction(nextAction)
        }
    }

    private fun createNotificationAction(action: String, icon: IconCompat, stringID: Int): NotificationCompat.Action {
        val intent =
            PendingIntent.getBroadcast(context, REQUEST_CODE, Intent(action), PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Action.Builder(icon, context.getString(stringID), intent).build()
    }

    /**
     * A notification to appear at the top of the screen (Android) or at the bottom of the screen (Android
     * Automotive in Polestar 2) showing player action icons, timestamp, seekbar etc.
     *
     * See https://developers.google.com/cars/design/automotive-os/apps/media/interaction-model/playing-media
     */
    private fun sendNotification(notifBuilder: NotificationCompat.Builder) {
        val notification = prepareNotification(notifBuilder)
        if (!isChannelCreated) {
            logger.warning(TAG, "Cannot send notification, no channel available")
            return
        }
        notificationManager.notify(NOTIFICATION_ID, notification)
        if (!isShowingNotification) {
            registerNotifRecv()
        }
        isShowingNotification = true
    }

    fun sendIsPlayingNotification() {
        logger.debug(TAG, "sendIsPlayingNotification()")
        sendNotification(isPlayingNotificationBuilder)
    }

    fun sendIsPausedNotification() {
        logger.debug(TAG, "sendIsPausedNotification()")
        sendNotification(isPausedNotificationBuilder)
    }

    fun sendEmptyNotification() {
        logger.debug(TAG, "sendEmptyNotification()")
        val notifBuilder = NotificationCompat.Builder(context, AUDIO_SESS_NOTIF_CHANNEL)
        sendNotification(notifBuilder)
    }

    private fun prepareNotification(notifBuilder: NotificationCompat.Builder): Notification {
        val style = androidx.media.app.NotificationCompat.MediaStyle()
        style.setMediaSession(mediaSession?.sessionToken)
        notifBuilder.apply {
            setStyle(style)
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            setContentTitle(currentQueueItem?.description?.title)
            setContentText(currentQueueItem?.description?.subtitle)
            setSubText(currentQueueItem?.description?.description)
            setSmallIcon(R.drawable.ic_notif)
            setContentIntent(mediaSession?.controller?.sessionActivity)
            // TODO: can "clear all" even be used for media notification in AAOS?
            setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_STOP))
        }
        return notifBuilder.build()
    }

    fun getNotification(): Notification {
        if (!isChannelCreated) {
            throw MissingNotifChannelException()
        }
        return prepareNotification(isPlayingNotificationBuilder)
    }

    private fun registerNotifRecv() {
        logger.debug(TAG, "registerNotifRecv()")
        val filter = IntentFilter()
        filter.addAction(ACTION_PREV)
        filter.addAction(ACTION_PLAY)
        filter.addAction(ACTION_PAUSE)
        filter.addAction(ACTION_NEXT)
        context.registerReceiver(broadcastMsgRecv, filter)
    }

    private fun unregisterNotifRecv() {
        logger.debug(TAG, "unregisterNotifRecv()")
        context.unregisterReceiver(broadcastMsgRecv)
    }

    // TODO: check why called multiple times
    fun removeNotification() {
        logger.debug(TAG, "removeNotification()")
        if (!isShowingNotification) {
            logger.debug("TAG", "No notification is currently shown")
        } else {
            notificationManager.cancel(NOTIFICATION_ID)
        }
        // TODO: to actually remove the notification, the mediaSession needs to be released. However when that is
        //  done it should not be used again unless the media browser client is restarted
        unregisterNotifRecv()
        isShowingNotification = false
    }

    fun shutdown() {
        deleteNotificationChannel()
    }

}
