/*
SPDX-FileCopyrightText: 2021 MoleMan1024 <moleman1024dev@gmail.com>
SPDX-License-Identifier: GPL-3.0-or-later
*/

package de.moleman1024.audiowagon.filestorage

import android.media.MediaDataSource
import android.net.Uri
import de.moleman1024.audiowagon.log.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel

interface AudioFileStorageLocation {
    @Suppress("PropertyName")
    val TAG: String
    val logger: Logger
    val device: MediaDevice
    val storageID: String
    var indexingStatus: IndexingStatus
    var isDetached: Boolean
    var isIndexingCancelled: Boolean

    @ExperimentalCoroutinesApi
    fun indexAudioFiles(directory: Directory, scope: CoroutineScope): ReceiveChannel<AudioFile>
    fun getDataSourceForURI(uri: Uri): MediaDataSource
    fun getBufferedDataSourceForURI(uri: Uri): MediaDataSource
    fun getByteArrayForURI(uri: Uri): ByteArray
    fun close()
    fun setDetached()

    /**
     * Returns all content (files/directories) in the given directory. Non-recursive
     */
    fun getDirectoryContents(directory: Directory): List<FileLike>

    /**
     * Returns all directories and audio files in the given directory. Non-recursive
     */
    fun getDirectoryContentsPlayable(directory: Directory): List<FileLike>

    /**
     * Returns the URI of the root directory of the storage location
     */
    fun getRootURI(): Uri

    /**
     * Cancels an ongoing indexing pipeline
     */
    fun cancelIndexAudioFiles() {
        if (indexingStatus == IndexingStatus.INDEXING) {
            logger.debug(TAG, "Cancelling ongoing audio file indexing")
            isIndexingCancelled = true
        }
    }

    /**
     * Check if Android MediaPlayer supports the given content type.
     * See https://source.android.com/compatibility/10/android-10-cdd#5_1_3_audio_codecs_details
     * For example .wma is not supported.
     */
    fun isSupportedContentType(contentType: String): Boolean {
        if (!contentType.startsWith("audio")) {
            return false
        }
        if (isPlaylistFile(contentType)) {
            return false
        }
        if (listOf(
                "3gp", "aac", "amr", "flac", "m4a", "matroska", "mid", "mp3", "mp4", "mpeg", "mpg", "ogg", "opus",
                "vorbis", "wav", "xmf"
            ).any {
                it in contentType
            }
        ) {
            return true
        }
        return false
    }

    /**
     * Checks if the given audio content/MIME type string represents a playlist (e.g. "mpegurl" is for .m3u
     * playlists)
     *
     * @param contentType the content/MIME type string
     */
    private fun isPlaylistFile(contentType: String): Boolean {
        return listOf("mpequrl", "mpegurl").any { it in contentType }
    }

    /**
     * The method guessContentTypeFromName() throws errors when certain characters appear in the name, remove those
     */
    fun makeFileNameSafeForContentTypeGuessing(fileName: String): String {
        return fileName.replace("#", "")
    }

}
