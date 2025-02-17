/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later


AudioWagon - Android Automotive OS USB media player
Copyright (C) 2021 by MoleMan1024 <moleman1024dev@gmail.com>

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.


Any product names, brands, and other trademarks (e.g. Polestar™, 
Google™, Android™) referred to are the property of their respective 
trademark holders. AudioWagon is not affiliated with, endorsed by, 
or sponsored by any trademark holders mentioned in the source code.
*/

package de.moleman1024.audiowagon

import android.app.Notification
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.IBinder
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.session.PlaybackStateCompat
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.media.MediaBrowserServiceCompat
import androidx.media.utils.MediaConstants
import de.moleman1024.audiowagon.authorization.PackageValidation
import de.moleman1024.audiowagon.authorization.USBDevicePermissions
import de.moleman1024.audiowagon.broadcast.PowerEventReceiver
import de.moleman1024.audiowagon.exceptions.CannotRecoverUSBException
import de.moleman1024.audiowagon.exceptions.MissingNotifChannelException
import de.moleman1024.audiowagon.filestorage.*
import de.moleman1024.audiowagon.log.CrashReporting
import de.moleman1024.audiowagon.log.Logger
import de.moleman1024.audiowagon.medialibrary.*
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.ContentHierarchyElement
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.ContentHierarchyID
import de.moleman1024.audiowagon.medialibrary.contenthierarchy.ContentHierarchyType
import de.moleman1024.audiowagon.persistence.PersistentPlaybackState
import de.moleman1024.audiowagon.persistence.PersistentStorage
import de.moleman1024.audiowagon.player.*
import de.moleman1024.audiowagon.repository.AudioItemRepository
import kotlinx.coroutines.*
import java.io.FileNotFoundException
import java.io.IOException
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.system.exitProcess

private const val TAG = "AudioBrowserService"
const val NOTIFICATION_ID: Int = 25575
private const val ALBUM_ART_MIN_NUM_PIXELS = 128
const val NUM_LOG_LINES_CRASH_REPORT = 100
const val ACTION_RESTART_SERVICE: String = "de.moleman1024.audiowagon.ACTION_RESTART_SERVICE"

// This PLAY_USB action seems to be essential for getting Google Assistant to accept voice commands such as
// "play <artist | album | track>"
const val ACTION_PLAY_USB: String = "android.car.intent.action.PLAY_USB"
const val ACTION_MEDIA_BUTTON: String = "android.intent.action.MEDIA_BUTTON"
const val CMD_ENABLE_LOG_TO_USB = "de.moleman1024.audiowagon.CMD_ENABLE_LOG_TO_USB"
const val CMD_DISABLE_LOG_TO_USB = "de.moleman1024.audiowagon.CMD_DISABLE_LOG_TO_USB"
const val CMD_ENABLE_EQUALIZER = "de.moleman1024.audiowagon.CMD_ENABLE_EQUALIZER"
const val CMD_DISABLE_EQUALIZER = "de.moleman1024.audiowagon.CMD_DISABLE_EQUALIZER"
const val CMD_ENABLE_REPLAYGAIN = "de.moleman1024.audiowagon.CMD_ENABLE_REPLAYGAIN"
const val CMD_DISABLE_REPLAYGAIN = "de.moleman1024.audiowagon.CMD_DISABLE_REPLAYGAIN"
const val CMD_SET_EQUALIZER_PRESET = "de.moleman1024.audiowagon.CMD_SET_EQUALIZER_PRESET"
const val EQUALIZER_PRESET_KEY = "preset"
const val CMD_SET_METADATAREAD_SETTING = "de.moleman1024.audiowagon.CMD_SET_METADATAREAD_SETTING"
const val METADATAREAD_SETTING_KEY = "metadata_read_setting"
const val CMD_READ_METADATA_NOW = "de.moleman1024.audiowagon.CMD_READ_METADATA_NOW"
const val CMD_EJECT = "de.moleman1024.audiowagon.CMD_EJECT"
const val AUDIOFOCUS_SETTING_KEY = "audiofocus_setting"
const val CMD_SET_AUDIOFOCUS_SETTING = "de.moleman1024.audiowagon.CMD_SET_AUDIOFOCUS_SETTING"
const val CMD_ENABLE_CRASH_REPORTING = "de.moleman1024.audiowagon.CMD_ENABLE_CRASH_REPORTING"
const val CMD_DISABLE_CRASH_REPORTING = "de.moleman1024.audiowagon.CMD_DISABLE_CRASH_REPORTING"


@ExperimentalCoroutinesApi
/**
 * This is the main entry point of the app.
 *
 * See
 * https://developer.android.com/training/cars/media
 * https://developer.android.com/training/cars/media/automotive-os
 * https://developers.google.com/cars/design/automotive-os/apps/media/interaction-model/playing-media
 *
 */
// We need to use MediaBrowserServiceCompat instead of MediaBrowserService because the latter does not support
// searching
// TODO: class getting too large
class AudioBrowserService : MediaBrowserServiceCompat(), LifecycleOwner {
    private var lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)
    private lateinit var audioItemLibrary: AudioItemLibrary
    private lateinit var audioFileStorage: AudioFileStorage
    private lateinit var usbDevicePermissions: USBDevicePermissions
    private lateinit var packageValidation: PackageValidation
    private lateinit var audioSession: AudioSession
    private lateinit var gui: GUI
    private lateinit var persistentStorage: PersistentStorage
    private lateinit var powerEventReceiver: PowerEventReceiver
    private lateinit var crashReporting: CrashReporting
    private var dispatcher: CoroutineDispatcher = Dispatchers.IO
    private var libraryCreationJob: Job? = null
    private var restoreFromPersistentJob: Job? = null
    private var cleanPersistentJob: Job? = null
    private var loadChildrenJobs: ConcurrentHashMap<String, Job> = ConcurrentHashMap<String, Job>()
    private var searchJob: Job? = null
    @Volatile
    private var isServiceStarted: Boolean = false
    @Volatile
    private var servicePriority: ServicePriority = ServicePriority.BACKGROUND
    @Volatile
    private var lastServiceStartReason: ServiceStartStopReason = ServiceStartStopReason.UNKNOWN
    private var deferredUntilServiceInForeground: CompletableDeferred<Unit> = CompletableDeferred()
    private var audioSessionNotification: Notification? = null
    private var latestContentHierarchyIDRequested: String = ""
    private var lastAudioPlayerState: AudioPlayerState = AudioPlayerState.IDLE
    private var isShuttingDown: Boolean = false
    private var isSuspended: Boolean = false
    private var isLibraryCreationCancelled: Boolean = false
    private var metadataReadNowRequested: Boolean = false
    private val contentHierarchyFilesRoot =
        ContentHierarchyElement.serialize(ContentHierarchyID(ContentHierarchyType.ROOT_FILES))
    private val contentHierarchyTracksRoot =
        ContentHierarchyElement.serialize(ContentHierarchyID(ContentHierarchyType.ROOT_TRACKS))
    private val contentHierarchyAlbumsRoot =
        ContentHierarchyElement.serialize(ContentHierarchyID(ContentHierarchyType.ROOT_ALBUMS))
    private val contentHierarchyArtistsRoot =
        ContentHierarchyElement.serialize(ContentHierarchyID(ContentHierarchyType.ROOT_ARTISTS))
    private val contentHierarchyRoot =
        ContentHierarchyElement.serialize(ContentHierarchyID(ContentHierarchyType.ROOT))
    // needs to be public for accessing logging from testcases
    val logger = Logger

    init {
        isShuttingDown = false
        lifecycleRegistry.currentState = Lifecycle.State.INITIALIZED
        instance = this
        setUncaughtExceptionHandler()
    }

    override fun onCreate() {
        logger.debug(TAG, "onCreate()")
        isShuttingDown = false
        super.onCreate()
        crashReporting = CrashReporting(applicationContext, lifecycleScope, dispatcher)
        powerEventReceiver = PowerEventReceiver()
        powerEventReceiver.audioBrowserService = this
        val shutdownFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SHUTDOWN)
        }
        registerReceiver(powerEventReceiver, shutdownFilter)
        startup()
    }

    @ExperimentalCoroutinesApi
    fun startup() {
        logger.debug(TAG, "startup()")
        persistentStorage = PersistentStorage(this, dispatcher)
        gui = GUI(lifecycleScope, applicationContext)
        usbDevicePermissions = USBDevicePermissions(this)
        audioFileStorage = AudioFileStorage(this, lifecycleScope, dispatcher, usbDevicePermissions, gui)
        audioItemLibrary = AudioItemLibrary(this, audioFileStorage, lifecycleScope, dispatcher, gui)
        audioItemLibrary.libraryExceptionObservers.add { exc ->
            when (exc) {
                is CannotRecoverUSBException -> {
                    cancelAllJobs()
                    notifyLibraryCreationFailure()
                    crashReporting.logMessages(logger.getLastLogLines(NUM_LOG_LINES_CRASH_REPORT))
                    crashReporting.recordException(exc)
                }
            }
        }
        if (sessionToken == null) {
            audioSession = AudioSession(
                this, audioItemLibrary, audioFileStorage, lifecycleScope, dispatcher, gui,
                persistentStorage, crashReporting
            )
            sessionToken = audioSession.sessionToken
            logger.debug(TAG, "New media session token: $sessionToken")
            // Try to avoid "RemoteServiceException: Context.startForegroundService() did not then call
            // Service.startForeground" when previously started foreground service is restarted
            // Can't reproduce it though...
            // If the service was not started previously, startForeground() should do nothing
            audioSessionNotification = audioSession.getNotification()
            startForeground(NOTIFICATION_ID, audioSessionNotification)
            servicePriority = ServicePriority.FOREGROUND
        }
        packageValidation = PackageValidation(this, R.xml.allowed_media_browser_callers)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        observeAudioFileStorage()
        observeAudioSessionStateChanges()
        updateConnectedDevices()
    }

    @ExperimentalCoroutinesApi
    private fun observeAudioFileStorage() {
        audioFileStorage.storageObservers.add { storageChange ->
            val allStorageIDs = audioItemLibrary.getAllStorageIDs()
            logger.debug(TAG, "Storage IDs in library before change: $allStorageIDs")
            if (storageChange.error.isNotBlank()) {
                logger.warning(TAG, "Audio file storage notified an error")
                if (!isSuspended) {
                    gui.showErrorToastMsg(getString(R.string.toast_error_USB, storageChange.error))
                }
                audioSession.stopPlayer()
                // TODO: this needs to change to properly support multiple storages
                allStorageIDs.forEach { onStorageLocationRemoved(it) }
                return@add
            }
            when (storageChange.action) {
                StorageAction.ADD -> onStorageLocationAdded(storageChange.id)
                StorageAction.REMOVE -> onStorageLocationRemoved(storageChange.id)
            }
            val allStorageIDsAfter = audioItemLibrary.getAllStorageIDs()
            logger.debug(TAG, "Storage IDs in library after change: $allStorageIDsAfter")
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun updateConnectedDevices() {
        logger.debug(TAG, "updateConnectedDevices()")
        try {
            audioFileStorage.updateConnectedDevices()
        } catch (exc: IOException) {
            logger.exception(TAG, "I/O Error during update of connected USB devices", exc)
            gui.showErrorToastMsg(this.getString(R.string.toast_error_USB_init))
            crashReporting.logMessages(logger.getLastLogLines(NUM_LOG_LINES_CRASH_REPORT))
            crashReporting.recordException(exc)
        } catch (exc: RuntimeException) {
            logger.exception(TAG, "Runtime error during update of connected USB devices", exc)
            gui.showErrorToastMsg(this.getString(R.string.toast_error_USB_init))
            crashReporting.logMessages(logger.getLastLogLines(NUM_LOG_LINES_CRASH_REPORT))
            crashReporting.recordException(exc)
        }
    }

    private fun observeAudioSessionStateChanges() {
        // TODO: clean up the notification handling
        // https://developer.android.com/guide/topics/media-apps/audio-app/building-a-mediabrowserservice#service-lifecycle
        audioSession.observe { event ->
            logger.debug(TAG, "stateChange=$event")
            when (event) {
                is AudioPlayerEvent -> {
                    handleAudioPlayerEvent(event)
                }
                is SettingChangeEvent -> {
                    handleSettingChangeEvent(event)
                }
                is CustomActionEvent -> {
                    handleCustomActionEvent(event)
                }
            }
        }
    }

    private fun handleAudioPlayerEvent(event: AudioPlayerEvent) {
        lastAudioPlayerState = event.state
        when (event.state) {
            AudioPlayerState.STARTED -> {
                restoreFromPersistentJob?.let {
                    runBlocking(dispatcher) {
                        cancelRestoreFromPersistent()
                    }
                }
                cleanPersistentJob?.let {
                    runBlocking(dispatcher) {
                        cancelCleanPersistent()
                    }
                }
                startServiceInForeground(ServiceStartStopReason.MEDIA_SESSION_CALLBACK)
            }
            AudioPlayerState.PAUSED -> {
                delayedMoveServiceToBackground()
            }
            AudioPlayerState.STOPPED -> {
                // to be nice we should call stopSelf() when we have nothing left to do, see
                // https://developer.android.com/guide/topics/media-apps/audio-app/building-a-mediabrowserservice#service-lifecycle
                stopService(ServiceStartStopReason.MEDIA_SESSION_CALLBACK)
            }
            AudioPlayerState.PLAYBACK_COMPLETED -> {
                launchCleanPersistentJob()
            }
            AudioPlayerState.ERROR -> {
                if (event.errorCode == PlaybackStateCompat.ERROR_CODE_END_OF_QUEUE) {
                    // Android Automotive media browser client will show a notification to user by itself in
                    // case of skipping beyond end of queue, no need for us to send a toast message
                    return
                }
                logger.warning(TAG, "Player encountered an error")
                gui.showErrorToastMsg(event.errorMsg)
            }
            else -> {
                // ignore
            }
        }
    }

    private fun handleSettingChangeEvent(event: SettingChangeEvent) {
        when (event.key) {
            SettingKey.READ_METADATA_SETTING -> {
                audioFileStorage.cancelIndexing()
                audioItemLibrary.cancelBuildLibrary()
                cancelLibraryCreation()
                when (event.value) {
                    MetadataReadSetting.WHEN_USB_CONNECTED.name -> {
                        updateConnectedDevices()
                    }
                }
            }
            SettingKey.READ_METADATA_NOW -> {
                metadataReadNowRequested = true
                audioFileStorage.cancelIndexing()
                audioItemLibrary.cancelBuildLibrary()
                cancelLibraryCreation()
                updateConnectedDevices()
            }
        }
    }

    private fun handleCustomActionEvent(event: CustomActionEvent) {
        when (event.action) {
            CustomAction.EJECT -> {
                cancelAllJobs()
            }
        }
    }

    private fun startServiceInForeground(reason: ServiceStartStopReason) {
        if (isServiceStarted) {
            if (servicePriority == ServicePriority.BACKGROUND) {
                logger.debug(TAG, "Moving already started service to foreground")
                try {
                    audioSessionNotification = audioSession.getNotification()
                    startForeground(NOTIFICATION_ID, audioSessionNotification)
                    servicePriority = ServicePriority.FOREGROUND
                } catch (exc: MissingNotifChannelException) {
                    logger.exception(TAG, exc.message.toString(), exc)
                    crashReporting.logMessages(logger.getLastLogLines(NUM_LOG_LINES_CRASH_REPORT))
                    crashReporting.recordException(exc)
                }
            }
            return
        }
        logger.debug(TAG, "startServiceInForeground(reason=$reason)")
        servicePriority = ServicePriority.FOREGROUND_REQUESTED
        if (deferredUntilServiceInForeground.isCompleted) {
            deferredUntilServiceInForeground = CompletableDeferred()
        }
        // this page says to start music player as foreground service
        // https://developer.android.com/guide/topics/media-apps/audio-app/building-a-mediabrowserservice#mediastyle-notifications
        // however this FAQ says foreground services are not allowed?
        // https://developer.android.com/training/cars/media/automotive-os#can_i_use_a_foreground_service
        // "foreground" is in terms of memory/priority, not in terms of a GUI window
        try {
            audioSessionNotification = audioSession.getNotification()
            if (lastServiceStartReason <= reason) {
                lastServiceStartReason = reason
            }
            startForegroundService(Intent(this, AudioBrowserService::class.java))
            logger.debug(TAG, "startForegroundService() called")
            crashReporting.logMessages(logger.getLastLogLines(NUM_LOG_LINES_CRASH_REPORT))
        } catch (exc: MissingNotifChannelException) {
            logger.exception(TAG, exc.message.toString(), exc)
            servicePriority = ServicePriority.FOREGROUND
            crashReporting.logMessages(logger.getLastLogLines(NUM_LOG_LINES_CRASH_REPORT))
            crashReporting.recordException(exc)
        }
    }

    private fun moveServiceToBackground() {
        logger.debug(TAG, "Moving service to background")
        // when the service is in (memory) background and no activity is using it (e.g. other media app is shown) AAOS
        // will usually stop the service (and thus also destroy) it after one minute of idle time
        stopForeground(false)
        servicePriority = ServicePriority.BACKGROUND
    }

    @ExperimentalCoroutinesApi
    private fun onStorageLocationAdded(storageID: String) {
        logger.info(TAG, "onStorageLocationAdded(storageID=$storageID)")
        cancelAllJobs()
        if (!SharedPrefs.isLegalDisclaimerAgreed(this)) {
            logger.info(TAG, "User did not agree to legal disclaimer yet")
            notifyBrowserChildrenChangedAllLevels()
            return
        }
        val metadataReadSetting = getMetadataReadSettingEnum()
        if (metadataReadSetting == MetadataReadSetting.OFF) {
            logger.info(TAG, "Metadata extraction is disabled in settings")
            launchRestoreFromPersistentJob()
            notifyBrowserChildrenChangedAllLevels()
            return
        }
        createLibraryForStorage(storageID)
    }

    private fun createLibraryForStorage(storageID: String) {
        if (isLibraryCreationCancelled) {
            if (libraryCreationJob?.isActive == true) {
                runBlocking(dispatcher) {
                    logger.debug(TAG, "Waiting for previous library creation job to end")
                    libraryCreationJob?.join()
                    logger.debug(TAG, "Previous library creation job has ended")
                }
            }
        }
        gui.showIndexingNotification()
        // We start the service in foreground while indexing the USB device, a notification is shown to the user.
        // This is done so the user can use other apps while the indexing keeps running in the service
        // TODO: might need to remove this for Android 12
        //  https://developer.android.com/guide/components/foreground-services#background-start-restrictions
        startServiceInForeground(ServiceStartStopReason.INDEXING)
        isLibraryCreationCancelled = false
        launchLibraryCreationJobForStorage(storageID)
    }

    private fun launchLibraryCreationJobForStorage(storageID: String) {
        // https://kotlinlang.org/docs/exception-handling.html#coroutineexceptionhandler
        // https://www.lukaslechner.com/why-exception-handling-with-kotlin-coroutines-is-so-hard-and-how-to-successfully-master-it/
        // https://medium.com/android-news/coroutine-in-android-working-with-lifecycle-fc9c1a31e5f3
        // TODO: the error handling is all over the place, need more structure
        val libraryCreationExceptionHandler = CoroutineExceptionHandler { _, exc ->
            notifyLibraryCreationFailure()
            crashReporting.logMessages(logger.getLastLogLines(NUM_LOG_LINES_CRASH_REPORT))
            crashReporting.recordException(exc)
            when (exc) {
                is IOException -> {
                    logger.exception(TAG, "I/O exception while building library", exc)
                }
                else -> {
                    logger.exception(TAG, "Exception while building library", exc)
                }
            }
        }
        libraryCreationJob = lifecycleScope.launch(libraryCreationExceptionHandler + dispatcher) {
            createLibraryFromStorages(listOf(storageID))
            notifyBrowserChildrenChangedAllLevels()
            gui.showIndexingFinishedNotification()
            when (lastAudioPlayerState) {
                AudioPlayerState.STARTED -> {
                    // do not change service status when indexing finishes while playback is ongoing
                }
                AudioPlayerState.PAUSED -> {
                    delayedMoveServiceToBackground()
                }
                else -> {
                    // player is currently in state STOPPED or ERROR
                    stopService(ServiceStartStopReason.INDEXING)
                }
            }
            if (isLibraryCreationCancelled) {
                isLibraryCreationCancelled = false
                logger.debug(TAG, "libraryCreationJob ended early")
                libraryCreationJob = null
                return@launch
            }
            if (lastAudioPlayerState in listOf(
                    AudioPlayerState.IDLE, AudioPlayerState.PAUSED, AudioPlayerState.STOPPED
                )
            ) {
                launchRestoreFromPersistentJob()
            } else {
                logger.debug(TAG, "Not restoring from persistent state, user has already requested new item")
            }
            libraryCreationJob = null
        }
    }

    @ExperimentalCoroutinesApi
    private suspend fun createLibraryFromStorages(storageIDs: List<String>) {
        logger.debug(TAG, "createLibraryFromStorages(storageIDs=${storageIDs})")
        val storageLocations: List<AudioFileStorageLocation> = storageIDs.map {
            audioFileStorage.getStorageLocationForID(it)
        }
        storageLocations.forEach { audioItemLibrary.initRepository(it.storageID) }
        val metadataReadSetting = getMetadataReadSettingEnum()
        if (metadataReadSetting == MetadataReadSetting.WHEN_USB_CONNECTED
            || (metadataReadSetting == MetadataReadSetting.MANUALLY && metadataReadNowRequested)
        ) {
            metadataReadNowRequested = false
            val audioFileProducerChannel = audioFileStorage.indexStorageLocations(storageIDs)
            audioItemLibrary.buildLibrary(audioFileProducerChannel) { notifyBrowserChildrenChangedAllLevels() }
        }
        storageIDs.forEach {
            audioFileStorage.setIndexingStatus(it, IndexingStatus.COMPLETED)
        }
        logger.info(TAG, "Audio library has been built for storages: $storageIDs")
    }

    private fun launchRestoreFromPersistentJob() {
        restoreFromPersistentJob = launchInScopeSafely {
            val persistentPlaybackState = persistentStorage.retrieve()
            try {
                restoreFromPersistent(persistentPlaybackState)
            } catch (exc: RuntimeException) {
                logger.exception(TAG, "Restoring from persistent failed", exc)
            }
            restoreFromPersistentJob = null
        }
    }

    private suspend fun restoreFromPersistent(state: PersistentPlaybackState) {
        if (state.trackID.isBlank()) {
            logger.debug(TAG, "No recent content hierarchy ID to restore from")
            return
        }
        val contentHierarchyID = ContentHierarchyElement.deserialize(state.trackID)
        when (contentHierarchyID.type) {
            ContentHierarchyType.TRACK -> {
                if (audioItemLibrary.getRepoForContentHierarchyID(contentHierarchyID) == null) {
                    logger.warning(TAG, "Cannot restore recent track, storage repository mismatch")
                    return
                }
            }
            ContentHierarchyType.FILE -> {
                if (audioFileStorage.getPrimaryStorageLocation().storageID != contentHierarchyID.storageID) {
                    logger.warning(TAG, "Cannot restore recent file, storage id mismatch")
                    return
                }
            }
            else -> {
                logger.error(TAG, "Not supported to restore from type: $contentHierarchyID")
                return
            }
        }
        if (state.queueIDs.isEmpty()) {
            logger.warning(TAG, "Found persistent recent track, but no queue items")
            return
        }
        audioSession.prepareFromPersistent(state)
    }

    private fun launchCleanPersistentJob() {
        cleanPersistentJob = launchInScopeSafely {
            try {
                audioSession.cleanPersistent()
            } catch (exc: RuntimeException) {
                logger.exception(TAG, "Cleaning persistent data failed", exc)
            }
            cleanPersistentJob = null
        }
    }

    private fun onStorageLocationRemoved(storageID: String) {
        logger.info(TAG, "onStorageLocationRemoved(storageID=$storageID)")
        if (isShuttingDown) {
            logger.debug(TAG, "Shutdown is in progress")
            return
        }
        cancelAllJobs()
        audioSession.storePlaybackState()
        audioSession.reset()
        if (storageID.isNotBlank()) {
            try {
                val storageLocation = audioFileStorage.getStorageLocationForID(storageID)
                audioItemLibrary.removeRepository(storageLocation.storageID)
            } catch (exc: IllegalArgumentException) {
                logger.warning(TAG, exc.toString())
            }
        }
        notifyBrowserChildrenChangedAllLevels()
        delayedMoveServiceToBackground()
    }

    private fun notifyBrowserChildrenChangedAllLevels() {
        notifyChildrenChanged(contentHierarchyFilesRoot)
        notifyChildrenChanged(contentHierarchyTracksRoot)
        notifyChildrenChanged(contentHierarchyAlbumsRoot)
        notifyChildrenChanged(contentHierarchyArtistsRoot)
        notifyChildrenChanged(contentHierarchyRoot)
        if (latestContentHierarchyIDRequested.isNotBlank()
            && latestContentHierarchyIDRequested !in listOf(
                contentHierarchyFilesRoot,
                contentHierarchyTracksRoot,
                contentHierarchyAlbumsRoot,
                contentHierarchyArtistsRoot,
                contentHierarchyRoot
            )
        ) {
            notifyChildrenChanged(latestContentHierarchyIDRequested)
        }
    }

    /**
     * See https://developer.android.com/guide/components/services#Lifecycle
     * https://developer.android.com/guide/topics/media-apps/audio-app/building-a-mediabrowserservice#service-lifecycle
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logger.debug(TAG, "onStartCommand(intent=$intent, flags=$flags, startid=$startId)")
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        isServiceStarted = true
        if (intent?.action == ACTION_MEDIA_BUTTON) {
            lastServiceStartReason = ServiceStartStopReason.MEDIA_BUTTON
        }
        logger.debug(TAG, "servicePriority=$servicePriority, lastServiceStartReason=$lastServiceStartReason")
        if (intent?.component == ComponentName(this, this.javaClass)
            && intent.action != ACTION_MEDIA_BUTTON
            && servicePriority == ServicePriority.FOREGROUND_REQUESTED
        ) {
            logger.debug(TAG, "Need to move service to foreground")
            if (audioSessionNotification == null) {
                val msg = "Missing audioSessionNotification for foreground service"
                logger.error(TAG, msg)
                crashReporting.logMessages(logger.getLastLogLines(NUM_LOG_LINES_CRASH_REPORT))
                crashReporting.recordException(RuntimeException(msg))
            } else {
                startForeground(NOTIFICATION_ID, audioSessionNotification)
                logger.debug(TAG, "Moved service to foreground")
                servicePriority = ServicePriority.FOREGROUND
                deferredUntilServiceInForeground.complete(Unit)
            }
        }
        when (intent?.action) {
            ACTION_PLAY_USB -> {
                launchInScopeSafely {
                    audioSession.playAnything()
                }
            }
            ACTION_MEDIA_BUTTON -> {
                audioSession.handleMediaButtonIntent(intent)
            }
            else -> {
                // ignore
            }
        }
        return Service.START_STICKY
    }

    @Suppress("RedundantNullableReturnType")
    /**
     * https://developer.android.com/guide/components/bound-services#Lifecycle
     */
    override fun onBind(intent: Intent?): IBinder? {
        logger.debug(TAG, "onBind(intent=$intent)")
        return super.onBind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        logger.debug(TAG, "onUnbind(intent=$intent)")
        return super.onUnbind(intent)
    }

    /**
     * This is called when app is swiped away from "recents". It is only called when service was started previously.
     * The "recents" app function does not seem to exist on Android Automotive
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        logger.debug(TAG, "onTaskRemoved()")
        audioSession.shutdown()
        super.onTaskRemoved(rootIntent)
    }

    private fun stopService(reason: ServiceStartStopReason) {
        logger.debug(TAG, "stopService()")
        if (!isServiceStarted) {
            logger.warning(TAG, "Service is not running")
            return
        }
        if (!shouldStopServiceFor(reason)) {
            return
        }
        if (servicePriority != ServicePriority.FOREGROUND_REQUESTED) {
            moveServiceToBackground()
            stopSelf()
            lastServiceStartReason = ServiceStartStopReason.UNKNOWN
            isServiceStarted = false
        } else {
            // in this case we need to wait until the service priority has changed, otherwise we will see a
            // crash with a RemoteServiceException
            // https://github.com/MoleMan1024/audiowagon/issues/56
            logger.debug(TAG, "Pending foreground service start, will wait before stopping service")
            launchInScopeSafely {
                deferredUntilServiceInForeground.await()
                logger.debug(TAG, "Pending foreground service start has completed")
                if (!shouldStopServiceFor(reason)) {
                    return@launchInScopeSafely
                }
                moveServiceToBackground()
                stopSelf()
                lastServiceStartReason = ServiceStartStopReason.UNKNOWN
                isServiceStarted = false
            }
        }
    }

    private fun shouldStopServiceFor(reason: ServiceStartStopReason): Boolean {
        logger.debug(TAG, "shouldStopServiceFor(reason=$reason), lastServiceStartReason=$lastServiceStartReason")
        if (reason == ServiceStartStopReason.INDEXING) {
            // if a higher priority reason previously started the service, do not stop service when indexing ends
            if (lastServiceStartReason in listOf(
                    ServiceStartStopReason.MEDIA_BUTTON,
                    ServiceStartStopReason.MEDIA_SESSION_CALLBACK,
                    ServiceStartStopReason.SUSPEND_OR_SHUTDOWN
                )
            ) {
                return false
            }
        } else if (reason == ServiceStartStopReason.MEDIA_SESSION_CALLBACK) {
            // do not stop indexing for a media session callback (e.g. onStop() happens when switching to other media
            // app)
            if (lastServiceStartReason == ServiceStartStopReason.INDEXING) {
                return false
            }
        }
        return true
    }

    private fun delayedMoveServiceToBackground() {
        logger.debug(TAG, "delayedMoveServiceToBackground()")
        if (servicePriority != ServicePriority.FOREGROUND_REQUESTED) {
            if (!isServiceStarted) {
                logger.warning(TAG, "Service is not running")
                servicePriority = ServicePriority.BACKGROUND
                return
            }
            moveServiceToBackground()
        } else {
            logger.debug(TAG, "Pending foreground service start, will wait before moving service to background")
            launchInScopeSafely {
                deferredUntilServiceInForeground.await()
                logger.debug(TAG, "Pending foreground service start has completed")
                moveServiceToBackground()
            }
        }
    }

    override fun onDestroy() {
        logger.debug(TAG, "onDestroy()")
        shutdownAndDestroy()
        unregisterReceiver(powerEventReceiver)
        super.onDestroy()
    }

    @Synchronized
    fun shutdown() {
        if (isShuttingDown) {
            logger.warning(TAG, "Already shutting down")
        }
        isShuttingDown = true
        metadataReadNowRequested = false
        logger.info(TAG, "shutdown()")
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) {
            return
        }
        cancelAllJobs()
        audioSession.storePlaybackState()
        gui.shutdown()
        stopService(ServiceStartStopReason.SUSPEND_OR_SHUTDOWN)
        audioSession.shutdown()
        audioFileStorage.shutdown()
        audioItemLibrary.shutdown()
    }

    private fun cancelAllJobs() {
        audioFileStorage.cancelIndexing()
        audioItemLibrary.cancelBuildLibrary()
        cancelRestoreFromPersistent()
        cancelCleanPersistent()
        cancelLibraryCreation()
        cancelLoadChildren()
    }

    private fun shutdownAndDestroy() {
        shutdown()
        destroyLifecycleScope()
        // process should be stopped soon afterwards
    }

    fun suspend() {
        logger.info(TAG, "suspend()")
        isSuspended = true
        cancelAllJobs()
        audioSession.storePlaybackState()
        gui.suspend()
        audioSession.suspend()
        audioFileStorage.suspend()
        audioItemLibrary.suspend()
        stopService(ServiceStartStopReason.SUSPEND_OR_SHUTDOWN)
        logger.info(TAG, "end of suspend() reached")
    }

    fun wakeup() {
        logger.info(TAG, "wakeup()")
        if (!isSuspended) {
            logger.warning(TAG, "System is not suspended, ignoring wakeup()")
            return
        }
        isSuspended = false
        usbDevicePermissions = USBDevicePermissions(this)
        audioFileStorage.wakeup()
        updateConnectedDevices()
    }

    private fun destroyLifecycleScope() {
        // since we use lifecycle scope almost everywhere, this should cancel all pending coroutines
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    private fun cancelLibraryCreation() {
        logger.debug(TAG, "Cancelling audio library creation")
        // TODO: check again in all of these if we should use cancel instead?
        libraryCreationJob?.cancelChildren()
        isLibraryCreationCancelled = true
        logger.debug(TAG, "Cancelled audio library creation")
    }

    private fun cancelRestoreFromPersistent() {
        logger.debug(TAG, "Cancelling restoring from persistent state")
        restoreFromPersistentJob?.cancelChildren()
        logger.debug(TAG, "Cancelled restoring from persistent state")
    }

    private fun cancelCleanPersistent() {
        logger.debug(TAG, "Cancelling cleaning persistent state")
        cleanPersistentJob?.cancelChildren()
        logger.debug(TAG, "Cancelled cleaning persistent state")
    }

    private fun cancelLoadChildren() {
        logger.debug(TAG, "Cancelling handling of onLoadChildren()")
        loadChildrenJobs.forEach { (_, job) ->
            job.cancelChildren()
        }
        loadChildrenJobs.clear()
        logger.debug(TAG, "Cancelled handling of onLoadChildren()")
    }

    /**
     * One of the first functions called by the MediaBrowser client.
     * Returns the root ID of the media browser tree view to show to the user.
     * Returning null will disconnect the client.
     */
    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        logger.debug(TAG, "onGetRoot($rootHints)")
        if (!packageValidation.isClientValid(clientPackageName, clientUid)) {
            logger.warning(TAG, "Client ${clientPackageName}(${clientUid}) could not be validated for browsing")
            return null
        }
        val maximumRootChildLimit = rootHints?.getInt(MediaConstants.BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT, 4)
        logger.debug(TAG, "maximumRootChildLimit=$maximumRootChildLimit")
        val supportedRootChildFlags = rootHints?.getInt(
            MediaConstants.BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_SUPPORTED_FLAGS,
            MediaItem.FLAG_BROWSABLE
        )
        val albumArtNumPixels = rootHints?.getInt(MediaConstants.BROWSER_ROOT_HINTS_KEY_MEDIA_ART_SIZE_PIXELS, -1)
        if (albumArtNumPixels != null && albumArtNumPixels > ALBUM_ART_MIN_NUM_PIXELS) {
            logger.debug(TAG, "Setting album art size: $albumArtNumPixels")
            AlbumArtContentProvider.setAlbumArtSizePixels(albumArtNumPixels)
        }
        // Implementing EXTRA_RECENT here would likely not work as we won't have permission to access USB drive yet
        // when this is called during boot phase
        // ( https://developer.android.com/guide/topics/media/media-controls )
        logger.debug(TAG, "supportedRootChildFlags=$supportedRootChildFlags")
        val hints = Bundle().apply {
            putBoolean(CONTENT_STYLE_SUPPORTED, true)
            putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_LIST_ITEM_HINT_VALUE)
            putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST_ITEM_HINT_VALUE)
            putBoolean(MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED, true)
        }
        return BrowserRoot(contentHierarchyRoot, hints)
    }

    /**
     * Returns list of [MediaItem] depending on the given content hierarchy ID
     * Returning empty list will show a message "no media available here".
     * Returning null will show "something went wrong" error message.
     *
     * The result is sent via Binder RPC to the media browser client process and its GUI, that means it
     * must be limited in size (maximum size of the parcel is 512 kB it seems).
     *
     * We don't use EXTRA_PAGE ( https://developer.android.com/reference/android/media/browse/MediaBrowser#EXTRA_PAGE )
     * (IIRC the AAOS client did not send it) instead we use groups of media items to reduce the number of items on a
     * single screen.
     */
    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        logger.debug(TAG, "onLoadChildren(parentId=$parentId)")
        latestContentHierarchyIDRequested = parentId
        result.detach()
        val jobID = Util.generateUUID()
        val loadChildrenJob = launchInScopeSafely {
            logger.verbose(TAG, "launch loadChildrenJob=$coroutineContext")
            try {
                val contentHierarchyID = ContentHierarchyElement.deserialize(parentId)
                val mediaItems: List<MediaItem> = audioItemLibrary.getMediaItemsStartingFrom(contentHierarchyID)
                logger.debug(TAG, "Got ${mediaItems.size} mediaItems in onLoadChildren(parentId=$parentId)")
                result.sendResult(mediaItems.toMutableList())
            } catch (exc: CancellationException) {
                logger.warning(TAG, exc.message.toString())
                result.sendResult(null)
            } catch (exc: FileNotFoundException) {
                // this can happen when some client is still trying to access the path to a meanwhile deleted file
                logger.warning(TAG, exc.message.toString())
                result.sendResult(null)
            } catch (exc: IOException) {
                crashReporting.logMessages(logger.getLastLogLines(NUM_LOG_LINES_CRASH_REPORT))
                crashReporting.recordException(exc)
                logger.exception(TAG, exc.message.toString(), exc)
                result.sendResult(null)
            } catch (exc: RuntimeException) {
                crashReporting.logMessages(logger.getLastLogLines(NUM_LOG_LINES_CRASH_REPORT))
                crashReporting.recordException(exc)
                logger.exception(TAG, exc.message.toString(), exc)
                if (!isShuttingDown) {
                    gui.showErrorToastMsg(getString(R.string.toast_error_unknown))
                }
                result.sendResult(null)
            } finally {
                loadChildrenJobs.remove(jobID)
            }
        }
        loadChildrenJobs[jobID] = loadChildrenJob
    }

    override fun onSearch(query: String, extras: Bundle?, result: Result<MutableList<MediaItem>>) {
        logger.debug(TAG, "onSearch(query='$query', extras=$extras)")
        result.detach()
        searchJob = launchInScopeSafely {
            val mediaItems: MutableList<MediaItem> = audioItemLibrary.searchMediaItems(query)
            logger.debug(TAG, "Got ${mediaItems.size} mediaItems in onSearch()")
            result.sendResult(mediaItems)
        }
    }

    override fun getLifecycle(): Lifecycle {
        return lifecycleRegistry
    }

    private fun setUncaughtExceptionHandler() {
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // only log uncaught exceptions if we still have a USB device
            if ("(USB_DEVICE_DETACHED|did you unplug)".toRegex().containsMatchIn(throwable.stackTraceToString())) {
                logger.exceptionLogcatOnly(TAG, "Uncaught exception (USB failed)", throwable)
            } else {
                logger.exception(TAG, "Uncaught exception (USB is OK)", throwable)
                logger.flushToUSB()
                try {
                    audioFileStorage.shutdown()
                } catch (exc: Exception) {
                    logger.exceptionLogcatOnly(TAG, exc.message.toString(), exc)
                }
            }
            try {
                stopService(ServiceStartStopReason.SUSPEND_OR_SHUTDOWN)
            } catch (exc: Exception) {
                logger.exceptionLogcatOnly(TAG, exc.message.toString(), exc)
            }
            oldHandler?.uncaughtException(thread, throwable)
            killProcess()
        }
    }

    /**
     * End the process with non zero error code, so that we are restarted hopefully. This is used to "recover" from
     * situations where e.g. handles to USB devices that were unplugged during use still exist, they may hang the app
     * otherwise
     */
    private fun killProcess() {
        exitProcess(1)
    }

    private fun notifyLibraryCreationFailure() {
        gui.removeIndexingNotification()
        if (!isSuspended) {
            gui.showErrorToastMsg(getString(R.string.toast_error_library_creation_fail))
        }
    }

    private fun launchInScopeSafely(func: suspend () -> Unit): Job {
        return Util.launchInScopeSafely(lifecycleScope, dispatcher, logger, TAG, crashReporting, func)
    }

    private fun getMetadataReadSettingEnum(): MetadataReadSetting {
        val metadataReadSettingStr = SharedPrefs.getMetadataReadSetting(this)
        var metadataReadSetting: MetadataReadSetting = MetadataReadSetting.WHEN_USB_CONNECTED
        try {
            metadataReadSetting = MetadataReadSetting.valueOf(metadataReadSettingStr)
        } catch (exc: IllegalArgumentException) {
            logger.exception(TAG, exc.message.toString(), exc)
        }
        return metadataReadSetting
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun getAudioItemLibrary(): AudioItemLibrary {
        return audioItemLibrary
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun getPrimaryRepo(): AudioItemRepository? {
        return audioItemLibrary.getPrimaryRepository()
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    companion object {
        private lateinit var instance: AudioBrowserService

        @JvmStatic
        fun getInstance() = instance
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun getIndexingStatus(): List<IndexingStatus> {
        return audioFileStorage.getIndexingStatus()
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun setMediaDeviceForTest(mediaDevice: MediaDevice) {
        audioFileStorage.mediaDevicesForTest.clear()
        audioFileStorage.mediaDevicesForTest.add(mediaDevice)
    }

}
