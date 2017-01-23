package org.mariotaku.twidere.task.twitter

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.net.Uri
import android.os.Build
import android.support.annotation.UiThread
import android.support.annotation.WorkerThread
import android.text.TextUtils
import android.webkit.MimeTypeMap
import com.nostra13.universalimageloader.core.DisplayImageOptions
import com.nostra13.universalimageloader.core.assist.ImageSize
import edu.tsinghua.hotmobi.HotMobiLogger
import edu.tsinghua.hotmobi.model.MediaUploadEvent
import net.ypresto.androidtranscoder.MediaTranscoder
import net.ypresto.androidtranscoder.format.MediaFormatStrategyPresets
import org.apache.commons.lang3.ArrayUtils
import org.apache.commons.lang3.math.NumberUtils
import org.mariotaku.abstask.library.AbstractTask
import org.mariotaku.ktextension.addAllTo
import org.mariotaku.microblog.library.MicroBlog
import org.mariotaku.microblog.library.MicroBlogException
import org.mariotaku.microblog.library.fanfou.model.PhotoStatusUpdate
import org.mariotaku.microblog.library.twitter.TwitterUpload
import org.mariotaku.microblog.library.twitter.model.*
import org.mariotaku.pickncrop.library.PNCUtils
import org.mariotaku.restfu.http.ContentType
import org.mariotaku.restfu.http.mime.Body
import org.mariotaku.restfu.http.mime.FileBody
import org.mariotaku.restfu.http.mime.SimpleBody
import org.mariotaku.sqliteqb.library.Expression
import org.mariotaku.twidere.R
import org.mariotaku.twidere.TwidereConstants.*
import org.mariotaku.twidere.annotation.AccountType
import org.mariotaku.twidere.app.TwidereApplication
import org.mariotaku.twidere.extension.model.newMicroBlogInstance
import org.mariotaku.twidere.model.*
import org.mariotaku.twidere.model.analyzer.UpdateStatus
import org.mariotaku.twidere.model.draft.UpdateStatusActionExtras
import org.mariotaku.twidere.model.util.ParcelableLocationUtils
import org.mariotaku.twidere.model.util.ParcelableStatusUtils
import org.mariotaku.twidere.preference.ServicePickerPreference
import org.mariotaku.twidere.provider.TwidereDataStore.Drafts
import org.mariotaku.twidere.util.*
import org.mariotaku.twidere.util.dagger.GeneralComponentHelper
import org.mariotaku.twidere.util.io.ContentLengthInputStream
import org.mariotaku.twidere.util.io.DirectByteArrayOutputStream
import java.io.*
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Created by mariotaku on 16/5/22.
 */
class UpdateStatusTask(
        internal val context: Context,
        internal val stateCallback: UpdateStatusTask.StateCallback
) : AbstractTask<Pair<String, ParcelableStatusUpdate>, UpdateStatusTask.UpdateStatusResult, Any?>() {

    @Inject
    lateinit var twitterWrapper: AsyncTwitterWrapper
    @Inject
    lateinit var preferences: SharedPreferencesWrapper
    @Inject
    lateinit var mediaLoader: MediaLoaderWrapper

    init {
        GeneralComponentHelper.build(context).inject(this)
    }

    override fun doLongOperation(params: Pair<String, ParcelableStatusUpdate>): UpdateStatusResult {
        val draftId = saveDraft(params.first, params.second)
        twitterWrapper.addSendingDraftId(draftId)
        try {
            val result = doUpdateStatus(params.second, draftId)
            deleteOrUpdateDraft(params.second, result, draftId)
            return result
        } catch (e: UpdateStatusException) {
            return UpdateStatusResult(e, draftId)
        } finally {
            twitterWrapper.removeSendingDraftId(draftId)
        }
    }

    override fun beforeExecute() {
        stateCallback.beforeExecute()
    }

    override fun afterExecute(handler: Any?, result: UpdateStatusResult) {
        stateCallback.afterExecute(result)
        if (params != null) {
            logUpdateStatus(params.first, params.second, result)
        }
    }

    private fun logUpdateStatus(actionType: String, statusUpdate: ParcelableStatusUpdate, result: UpdateStatusResult) {
        val mediaType = statusUpdate.media?.firstOrNull()?.type ?: ParcelableMedia.Type.UNKNOWN
        val hasLocation = statusUpdate.location != null
        val preciseLocation = statusUpdate.display_coordinates
        Analyzer.log(UpdateStatus(result.accountTypes.firstOrNull(), actionType, mediaType,
                hasLocation, preciseLocation, result.succeed))
    }

    @Throws(UpdateStatusException::class)
    private fun doUpdateStatus(update: ParcelableStatusUpdate, draftId: Long): UpdateStatusResult {
        val app = TwidereApplication.getInstance(context)
        val uploader = getMediaUploader(app)
        val shortener = getStatusShortener(app)

        val pendingUpdate = PendingStatusUpdate(update)

        uploadMedia(uploader, update, pendingUpdate)
        shortenStatus(shortener, update, pendingUpdate)

        val result: UpdateStatusResult
        try {
            result = requestUpdateStatus(update, pendingUpdate, draftId)
        } catch (e: IOException) {
            return UpdateStatusResult(UpdateStatusException(e), draftId)
        }

        mediaUploadCallback(uploader, pendingUpdate, result)
        statusShortenCallback(shortener, pendingUpdate, result)

        // Cleanup
        pendingUpdate.deleteOnSuccess.forEach { delete ->
            delete.delete(context)
        }
        return result
    }

    private fun deleteOrUpdateDraft(update: ParcelableStatusUpdate, result: UpdateStatusResult, draftId: Long) {
        val where = Expression.equalsArgs(Drafts._ID).sql
        val whereArgs = arrayOf(draftId.toString())
        var hasError = false
        val failedAccounts = ArrayList<UserKey>()
        for (i in update.accounts.indices) {
            val exception = result.exceptions[i]
            if (exception != null && !isDuplicate(exception)) {
                hasError = true
                failedAccounts.add(update.accounts[i].key)
            }
        }
        val cr = context.contentResolver
        if (hasError) {
            val values = ContentValues()
            values.put(Drafts.ACCOUNT_KEYS, failedAccounts.joinToString(","))
            cr.update(Drafts.CONTENT_URI, values, where, whereArgs)
            // TODO show error message
        } else {
            cr.delete(Drafts.CONTENT_URI, where, whereArgs)
        }
    }

    @Throws(UploadException::class)
    private fun uploadMedia(uploader: MediaUploaderInterface?,
                            update: ParcelableStatusUpdate,
                            pendingUpdate: PendingStatusUpdate) {
        stateCallback.onStartUploadingMedia()
        if (uploader == null) {
            uploadMediaWithDefaultProvider(update, pendingUpdate)
        } else {
            uploadMediaWithExtension(uploader, update, pendingUpdate)
        }
    }

    @Throws(UploadException::class)
    private fun uploadMediaWithExtension(uploader: MediaUploaderInterface,
                                         update: ParcelableStatusUpdate,
                                         pending: PendingStatusUpdate) {
        uploader.waitForService()
        val media: Array<UploaderMediaItem>
        try {
            media = UploaderMediaItem.getFromStatusUpdate(context, update)
        } catch (e: FileNotFoundException) {
            throw UploadException(e)
        }

        val sharedMedia = HashMap<UserKey, MediaUploadResult>()
        for (i in 0..pending.length - 1) {
            val account = update.accounts[i]
            // Skip upload if shared media found
            val accountKey = account.key
            var uploadResult: MediaUploadResult? = sharedMedia[accountKey]
            if (uploadResult == null) {
                uploadResult = uploader.upload(update, accountKey, media) ?: run {
                    throw UploadException()
                }
                if (uploadResult.media_uris == null) {
                    throw UploadException(uploadResult.error_message ?: "Unknown error")
                }
                pending.mediaUploadResults[i] = uploadResult
                if (uploadResult.shared_owners != null) {
                    for (sharedOwner in uploadResult.shared_owners) {
                        sharedMedia.put(sharedOwner, uploadResult)
                    }
                }
            }
            // Override status text
            pending.overrideTexts[i] = Utils.getMediaUploadStatus(context,
                    uploadResult.media_uris, pending.overrideTexts[i])
        }
    }

    @Throws(UpdateStatusException::class)
    private fun shortenStatus(shortener: StatusShortenerInterface?,
                              update: ParcelableStatusUpdate,
                              pending: PendingStatusUpdate) {
        if (shortener == null) return
        stateCallback.onShorteningStatus()
        val sharedShortened = HashMap<UserKey, StatusShortenResult>()
        for (i in 0 until pending.length) {
            val account = update.accounts[i]
            val text = pending.overrideTexts[i]
            val textLimit = TwidereValidator.getTextLimit(account)
            if (textLimit >= 0 && text.length <= textLimit) {
                continue
            }
            shortener.waitForService()
            // Skip upload if this shared media found
            val accountKey = account.key
            var shortenResult: StatusShortenResult? = sharedShortened[accountKey]
            if (shortenResult == null) {
                shortenResult = shortener.shorten(update, accountKey, text) ?: run {
                    throw ShortenException()
                }
                if (shortenResult.shortened == null) {
                    throw ShortenException(shortenResult.error_message ?: "Unknown error")
                }
                pending.statusShortenResults[i] = shortenResult
                if (shortenResult.shared_owners != null) {
                    for (sharedOwner in shortenResult.shared_owners) {
                        sharedShortened.put(sharedOwner, shortenResult)
                    }
                }
            }
            // Override status text
            pending.overrideTexts[i] = shortenResult.shortened
        }
    }

    @Throws(IOException::class)
    private fun requestUpdateStatus(statusUpdate: ParcelableStatusUpdate,
                                    pendingUpdate: PendingStatusUpdate,
                                    draftId: Long): UpdateStatusResult {

        stateCallback.onUpdatingStatus()

        val result = UpdateStatusResult(pendingUpdate.length, draftId)

        for (i in 0 until pendingUpdate.length) {
            val account = statusUpdate.accounts[i]
            result.accountTypes[i] = account.type
            val microBlog = MicroBlogAPIFactory.getInstance(context, account.key)
            var mediaBody: MediaStreamBody? = null
            try {
                when (account.type) {
                    AccountType.FANFOU -> {
                        // Call uploadPhoto if media present
                        if (!ArrayUtils.isEmpty(statusUpdate.media)) {
                            // Fanfou only allow one photo
                            if (statusUpdate.media.size > 1) {
                                result.exceptions[i] = MicroBlogException(
                                        context.getString(R.string.error_too_many_photos_fanfou))
                            } else {
                                val sizeLimit = SizeLimit(width = 2048, height = 1536)
                                mediaBody = getBodyFromMedia(context, mediaLoader,
                                        Uri.parse(statusUpdate.media[0].uri),
                                        sizeLimit, statusUpdate.media[0].type,
                                        ContentLengthInputStream.ReadListener { length, position ->
                                            stateCallback.onUploadingProgressChanged(-1, position, length)
                                        })
                                val photoUpdate = PhotoStatusUpdate(mediaBody.body,
                                        pendingUpdate.overrideTexts[i])
                                val requestResult = microBlog.uploadPhoto(photoUpdate)

                                result.statuses[i] = ParcelableStatusUtils.fromStatus(requestResult,
                                        account.key, false)
                            }
                        } else {
                            val requestResult = twitterUpdateStatus(microBlog, statusUpdate,
                                    pendingUpdate, pendingUpdate.overrideTexts[i], i)

                            result.statuses[i] = ParcelableStatusUtils.fromStatus(requestResult,
                                    account.key, false)
                        }
                    }
                    else -> {
                        val requestResult = twitterUpdateStatus(microBlog, statusUpdate,
                                pendingUpdate, pendingUpdate.overrideTexts[i], i)

                        result.statuses[i] = ParcelableStatusUtils.fromStatus(requestResult,
                                account.key, false)
                    }
                }
            } catch (e: MicroBlogException) {
                result.exceptions[i] = e
            } finally {
                Utils.closeSilently(mediaBody)
            }
        }
        return result
    }

    /**
     * Calling Twitter's upload method. This method sets multiple owner for bandwidth saving
     */
    @Throws(UploadException::class)
    private fun uploadMediaWithDefaultProvider(update: ParcelableStatusUpdate, pendingUpdate: PendingStatusUpdate) {
        // Return empty array if no media attached
        if (ArrayUtils.isEmpty(update.media)) return
        val ownersList = update.accounts.filter {
            AccountType.TWITTER == it.type
        }.map(AccountDetails::key)
        val ownerIds = ownersList.map {
            it.id
        }.toTypedArray()
        for (i in 0..pendingUpdate.length - 1) {
            val account = update.accounts[i]
            val mediaIds: Array<String>?
            when (account.type) {
                AccountType.TWITTER -> {
                    val upload = account.newMicroBlogInstance(context, cls = TwitterUpload::class.java)
                    if (pendingUpdate.sharedMediaIds != null) {
                        mediaIds = pendingUpdate.sharedMediaIds
                    } else {
                        val (ids, deleteOnSuccess) = uploadAllMediaShared(upload, update, ownerIds, true)
                        mediaIds = ids
                        deleteOnSuccess?.addAllTo(pendingUpdate.deleteOnSuccess)
                        pendingUpdate.sharedMediaIds = mediaIds
                    }
                }
                AccountType.FANFOU -> {
                    // Nope, fanfou uses photo uploading API
                    mediaIds = null
                }
                AccountType.STATUSNET -> {
                    // TODO use their native API
                    val upload = account.newMicroBlogInstance(context, cls = TwitterUpload::class.java)
                    val (ids, deleteOnSuccess) = uploadAllMediaShared(upload, update, ownerIds, false)
                    mediaIds = ids
                    deleteOnSuccess?.addAllTo(pendingUpdate.deleteOnSuccess)
                }
                else -> {
                    mediaIds = null
                }
            }
            pendingUpdate.mediaIds[i] = mediaIds
        }
        pendingUpdate.sharedMediaOwners = ownersList.toTypedArray()
    }

    @Throws(MicroBlogException::class)
    private fun twitterUpdateStatus(microBlog: MicroBlog, statusUpdate: ParcelableStatusUpdate,
                                    pendingUpdate: PendingStatusUpdate, overrideText: String,
                                    index: Int): Status {
        val status = StatusUpdate(overrideText)
        if (statusUpdate.in_reply_to_status != null) {
            status.inReplyToStatusId(statusUpdate.in_reply_to_status.id)
        }
        if (statusUpdate.repost_status_id != null) {
            status.setRepostStatusId(statusUpdate.repost_status_id)
        }
        if (statusUpdate.attachment_url != null) {
            status.setAttachmentUrl(statusUpdate.attachment_url)
        }
        if (statusUpdate.location != null) {
            status.location(ParcelableLocationUtils.toGeoLocation(statusUpdate.location))
            status.displayCoordinates(statusUpdate.display_coordinates)
        }
        val mediaIds = pendingUpdate.mediaIds[index]
        if (mediaIds != null) {
            status.mediaIds(*mediaIds)
        }
        if (statusUpdate.is_possibly_sensitive) {
            status.possiblySensitive(statusUpdate.is_possibly_sensitive)
        }
        return microBlog.updateStatus(status)
    }

    private fun statusShortenCallback(shortener: StatusShortenerInterface?, pendingUpdate: PendingStatusUpdate, updateResult: UpdateStatusResult) {
        if (shortener == null || !shortener.waitForService()) return
        for (i in 0..pendingUpdate.length - 1) {
            val shortenResult = pendingUpdate.statusShortenResults[i]
            val status = updateResult.statuses[i]
            if (shortenResult == null || status == null) continue
            shortener.callback(shortenResult, status)
        }
    }

    private fun mediaUploadCallback(uploader: MediaUploaderInterface?, pendingUpdate: PendingStatusUpdate, updateResult: UpdateStatusResult) {
        if (uploader == null || !uploader.waitForService()) return
        for (i in 0..pendingUpdate.length - 1) {
            val uploadResult = pendingUpdate.mediaUploadResults[i]
            val status = updateResult.statuses[i]
            if (uploadResult == null || status == null) continue
            uploader.callback(uploadResult, status)
        }
    }

    @Throws(UploaderNotFoundException::class, UploadException::class, ShortenerNotFoundException::class, ShortenException::class)
    private fun getStatusShortener(app: TwidereApplication): StatusShortenerInterface? {
        val shortenerComponent = preferences.getString(KEY_STATUS_SHORTENER, null)
        if (ServicePickerPreference.isNoneValue(shortenerComponent)) return null

        val shortener = StatusShortenerInterface.getInstance(app, shortenerComponent) ?: throw ShortenerNotFoundException()
        try {
            shortener.checkService { metaData ->
                if (metaData == null) throw ExtensionVersionMismatchException()
                val extensionVersion = metaData.getString(METADATA_KEY_EXTENSION_VERSION_STATUS_SHORTENER)
                if (!TextUtils.equals(extensionVersion, context.getString(R.string.status_shortener_service_interface_version))) {
                    throw ExtensionVersionMismatchException()
                }
            }
        } catch (e: ExtensionVersionMismatchException) {
            throw ShortenException(context.getString(R.string.shortener_version_incompatible))
        } catch (e: AbsServiceInterface.CheckServiceException) {
            throw ShortenException(e)
        }
        return shortener
    }

    @Throws(UploaderNotFoundException::class, UploadException::class)
    private fun getMediaUploader(app: TwidereApplication): MediaUploaderInterface? {
        val uploaderComponent = preferences.getString(KEY_MEDIA_UPLOADER, null)
        if (ServicePickerPreference.isNoneValue(uploaderComponent)) return null
        val uploader = MediaUploaderInterface.getInstance(app, uploaderComponent) ?: throw UploaderNotFoundException(context.getString(R.string.error_message_media_uploader_not_found))
        try {
            uploader.checkService { metaData ->
                if (metaData == null) throw ExtensionVersionMismatchException()
                val extensionVersion = metaData.getString(METADATA_KEY_EXTENSION_VERSION_MEDIA_UPLOADER)
                if (!TextUtils.equals(extensionVersion, context.getString(R.string.media_uploader_service_interface_version))) {
                    throw ExtensionVersionMismatchException()
                }
            }
        } catch (e: AbsServiceInterface.CheckServiceException) {
            if (e is ExtensionVersionMismatchException) {
                throw UploadException(context.getString(R.string.uploader_version_incompatible))
            }
            throw UploadException(e)
        }

        return uploader
    }

    @Throws(UploadException::class)
    private fun uploadAllMediaShared(
            upload: TwitterUpload,
            update: ParcelableStatusUpdate,
            ownerIds: Array<String>,
            chucked: Boolean
    ): Pair<Array<String>, List<MediaDeletionItem>?> {
        val deleteOnSuccess = ArrayList<MediaDeletionItem>()
        val mediaIds = update.media.mapIndexed { index, media ->
            val resp: MediaUploadResponse
            //noinspection TryWithIdenticalCatches
            var body: MediaStreamBody? = null
            try {
                val sizeLimit = SizeLimit(width = 2048, height = 1536)
                body = getBodyFromMedia(context, mediaLoader, Uri.parse(media.uri), sizeLimit,
                        media.type, ContentLengthInputStream.ReadListener { length, position ->
                    stateCallback.onUploadingProgressChanged(index, position, length)
                })
                val mediaUploadEvent = MediaUploadEvent.create(context, media)
                mediaUploadEvent.setFileSize(body.body.length())
                body.geometry?.let { geometry ->
                    mediaUploadEvent.setGeometry(geometry.x, geometry.y)
                }
                if (chucked) {
                    resp = uploadMediaChucked(upload, body.body, ownerIds)
                } else {
                    resp = upload.uploadMedia(body.body, ownerIds)
                }
                mediaUploadEvent.markEnd()
                HotMobiLogger.getInstance(context).log(mediaUploadEvent)
            } catch (e: IOException) {
                throw UploadException(e)
            } catch (e: MicroBlogException) {
                throw UploadException(e)
            } finally {
                Utils.closeSilently(body)
            }
            body?.deleteOnSuccess?.addAllTo(deleteOnSuccess)
            if (media.alt_text?.isNotEmpty() ?: false) {
                try {
                    upload.createMetadata(NewMediaMetadata(resp.id, media.alt_text))
                } catch (e: MicroBlogException) {
                    // Ignore
                }
            }
            return@mapIndexed resp.id
        }
        return Pair(mediaIds.toTypedArray(), deleteOnSuccess)
    }


    @Throws(IOException::class, MicroBlogException::class)
    private fun uploadMediaChucked(upload: TwitterUpload, body: Body,
                                   ownerIds: Array<String>): MediaUploadResponse {
        val mediaType = body.contentType().contentType
        val length = body.length()
        val stream = body.stream()
        var response = upload.initUploadMedia(mediaType, length, ownerIds)
        val segments = if (length == 0L) 0 else (length / BULK_SIZE + 1).toInt()
        for (segmentIndex in 0..segments - 1) {
            val currentBulkSize = Math.min(BULK_SIZE.toLong(), length - segmentIndex * BULK_SIZE).toInt()
            val bulk = SimpleBody(ContentType.OCTET_STREAM, null, currentBulkSize.toLong(),
                    stream)
            upload.appendUploadMedia(response.id, segmentIndex, bulk)
        }
        response = upload.finalizeUploadMedia(response.id)
        var info: MediaUploadResponse.ProcessingInfo? = response.processingInfo
        while (info != null && shouldWaitForProcess(info)) {
            val checkAfterSecs = info.checkAfterSecs
            if (checkAfterSecs <= 0) {
                break
            }
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(checkAfterSecs))
            } catch (e: InterruptedException) {
                break
            }

            response = upload.getUploadMediaStatus(response.id)
            info = response.processingInfo
        }
        if (info != null && MediaUploadResponse.ProcessingInfo.State.FAILED == info.state) {
            val exception = MicroBlogException()
            val errorInfo = info.error
            if (errorInfo != null) {
                exception.errors = arrayOf(errorInfo)
            }
            throw exception
        }
        return response
    }

    private fun isDuplicate(exception: Exception): Boolean {
        return exception is MicroBlogException && exception.errorCode == ErrorInfo.STATUS_IS_DUPLICATE
    }

    private fun shouldWaitForProcess(info: MediaUploadResponse.ProcessingInfo): Boolean {
        when (info.state) {
            MediaUploadResponse.ProcessingInfo.State.PENDING, MediaUploadResponse.ProcessingInfo.State.IN_PROGRESS -> return true
            else -> return false
        }
    }


    private fun saveDraft(@Draft.Action draftAction: String?, statusUpdate: ParcelableStatusUpdate): Long {
        val draft = Draft()
        draft.unique_id = statusUpdate.draft_unique_id ?: UUID.randomUUID().toString()
        draft.account_keys = statusUpdate.accounts.map { it.key }.toTypedArray()
        draft.action_type = draftAction ?: Draft.Action.UPDATE_STATUS
        draft.text = statusUpdate.text
        draft.location = statusUpdate.location
        draft.media = statusUpdate.media
        draft.timestamp = System.currentTimeMillis()
        draft.action_extras = UpdateStatusActionExtras().apply {
            inReplyToStatus = statusUpdate.in_reply_to_status
            isPossiblySensitive = statusUpdate.is_possibly_sensitive
            isRepostStatusId = statusUpdate.repost_status_id
            displayCoordinates = statusUpdate.display_coordinates
            attachmentUrl = statusUpdate.attachment_url
        }
        val resolver = context.contentResolver
        val draftUri = resolver.insert(Drafts.CONTENT_URI, DraftValuesCreator.create(draft)) ?: return -1
        return NumberUtils.toLong(draftUri.lastPathSegment, -1)
    }

    internal class PendingStatusUpdate(val length: Int, defaultText: String) {

        constructor(statusUpdate: ParcelableStatusUpdate) : this(statusUpdate.accounts.size,
                statusUpdate.text)

        var sharedMediaIds: Array<String>? = null
        var sharedMediaOwners: Array<UserKey>? = null

        val overrideTexts: Array<String> = Array(length) { idx ->
            defaultText
        }
        val mediaIds: Array<Array<String>?> = arrayOfNulls(length)

        val mediaUploadResults: Array<MediaUploadResult?> = arrayOfNulls(length)
        val statusShortenResults: Array<StatusShortenResult?> = arrayOfNulls(length)

        val deleteOnSuccess: ArrayList<MediaDeletionItem> = arrayListOf()

    }

    class UpdateStatusResult {
        val statuses: Array<ParcelableStatus?>
        val exceptions: Array<MicroBlogException?>
        val accountTypes: Array<String?>

        val exception: UpdateStatusException?
        val draftId: Long

        val succeed: Boolean get() = !statuses.contains(null)

        constructor(count: Int, draftId: Long) {
            this.statuses = arrayOfNulls(count)
            this.exceptions = arrayOfNulls(count)
            this.accountTypes = arrayOfNulls(count)
            this.exception = null
            this.draftId = draftId
        }

        constructor(exception: UpdateStatusException, draftId: Long) {
            this.exception = exception
            this.statuses = arrayOfNulls(0)
            this.exceptions = arrayOfNulls(0)
            this.accountTypes = arrayOfNulls(0)
            this.draftId = draftId
        }
    }


    open class UpdateStatusException : Exception {
        constructor() : super()

        constructor(detailMessage: String, throwable: Throwable) : super(detailMessage, throwable)

        constructor(throwable: Throwable) : super(throwable)

        constructor(message: String) : super(message)
    }

    class UploaderNotFoundException : UpdateStatusException {

        constructor() : super()

        constructor(detailMessage: String, throwable: Throwable) : super(detailMessage, throwable)

        constructor(throwable: Throwable) : super(throwable)

        constructor(message: String) : super(message)
    }

    class UploadException : UpdateStatusException {

        constructor() : super() {
        }

        constructor(detailMessage: String, throwable: Throwable) : super(detailMessage, throwable) {
        }

        constructor(throwable: Throwable) : super(throwable) {
        }

        constructor(message: String) : super(message) {
        }
    }

    class ExtensionVersionMismatchException : AbsServiceInterface.CheckServiceException()

    class ShortenerNotFoundException : UpdateStatusException()

    class ShortenException : UpdateStatusException {

        constructor() : super() {
        }

        constructor(detailMessage: String, throwable: Throwable) : super(detailMessage, throwable) {
        }

        constructor(throwable: Throwable) : super(throwable) {
        }

        constructor(message: String) : super(message) {
        }
    }

    interface StateCallback {
        @WorkerThread
        fun onStartUploadingMedia()

        @WorkerThread
        fun onUploadingProgressChanged(index: Int, current: Long, total: Long)

        @WorkerThread
        fun onShorteningStatus()

        @WorkerThread
        fun onUpdatingStatus()

        @UiThread
        fun afterExecute(result: UpdateStatusResult)

        @UiThread
        fun beforeExecute()
    }

    data class SizeLimit(
            val width: Int,
            val height: Int
    )

    data class MediaStreamBody(
            val body: Body,
            val geometry: Point?,
            val deleteOnSuccess: List<MediaDeletionItem>?
    ) : Closeable {
        override fun close() {
            body.close()
        }
    }

    interface MediaDeletionItem {
        fun delete(context: Context): Boolean
    }

    data class UriMediaDeletionItem(val uri: Uri) : MediaDeletionItem {
        override fun delete(context: Context): Boolean {
            return PNCUtils.deleteMedia(context, uri)
        }
    }

    data class FileMediaDeletionItem(val file: File) : MediaDeletionItem {
        override fun delete(context: Context): Boolean {
            return file.delete()
        }

    }

    companion object {

        private val BULK_SIZE = 256 * 1024// 128 Kib

        @Throws(IOException::class)
        fun getBodyFromMedia(
                context: Context,
                mediaLoader: MediaLoaderWrapper,
                mediaUri: Uri,
                sizeLimit: SizeLimit? = null,
                @ParcelableMedia.Type type: Int,
                readListener: ContentLengthInputStream.ReadListener
        ): MediaStreamBody {
            val resolver = context.contentResolver
            val mediaType = resolver.getType(mediaUri) ?: run {
                if (mediaUri.scheme == ContentResolver.SCHEME_FILE) {
                    mediaUri.lastPathSegment?.substringAfterLast(".")?.let { ext ->
                        return@run MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                    }
                }
                return@run null
            }
            val data = run {
                if (sizeLimit != null) {
                    when (type) {
                        ParcelableMedia.Type.IMAGE -> {
                            return@run imageStream(context, resolver, mediaLoader, mediaUri,
                                    mediaType, sizeLimit)
                        }
                        ParcelableMedia.Type.VIDEO -> {
                            return@run videoStream(context, resolver, mediaUri, mediaType)
                        }
                    }
                }
                return@run null
            }
            val cis = data?.stream ?: run {
                val st = resolver.openInputStream(mediaUri) ?: throw FileNotFoundException(mediaUri.toString())
                val length = st.available().toLong()
                return@run ContentLengthInputStream(st, length)
            }
            cis.setReadListener(readListener)
            val mimeType = data?.type ?: mediaType ?: "application/octet-stream"
            val body = FileBody(cis, "attachment", cis.length(), ContentType.parse(mimeType))
            val deletionList: MutableList<MediaDeletionItem> = mutableListOf(UriMediaDeletionItem(mediaUri))
            data?.deleteOnSuccess?.addAllTo(deletionList)
            return MediaStreamBody(body, data?.geometry, deletionList)
        }


        private fun imageStream(
                context: Context,
                resolver: ContentResolver,
                mediaLoader: MediaLoaderWrapper,
                mediaUri: Uri,
                defaultType: String?,
                sizeLimit: SizeLimit
        ): MediaStreamData? {
            var mediaType = defaultType
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactoryUtils.decodeUri(resolver, mediaUri, null, o)
            if (o.outMimeType != null) {
                mediaType = o.outMimeType
            }
            val size = Point(o.outWidth, o.outHeight)
            o.inSampleSize = Utils.calculateInSampleSize(o.outWidth, o.outHeight,
                    sizeLimit.width, sizeLimit.height)
            o.inJustDecodeBounds = false
            if (o.outWidth > 0 && o.outHeight > 0 && mediaType != "image/gif") {
                val displayOptions = DisplayImageOptions.Builder()
                        .considerExifParams(true)
                        .build()
                val bitmap = mediaLoader.loadImageSync(mediaUri.toString(),
                        ImageSize(o.outWidth, o.outHeight).scaleDown(o.inSampleSize),
                        displayOptions)

                if (bitmap != null) {
                    size.set(bitmap.width, bitmap.height)
                    val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mediaType)
                    val tempFile = File.createTempFile("twidere__scaled_image_", ".$ext", context.cacheDir)
                    tempFile.outputStream().use { os ->
                        when (mediaType) {
                            "image/png", "image/x-png", "image/webp", "image-x-webp" -> {
                                bitmap.compress(Bitmap.CompressFormat.PNG, 0, os)
                            }
                            else -> {
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, os)
                            }
                        }
                    }
                    return MediaStreamData(ContentLengthInputStream(tempFile), mediaType, size,
                            listOf(FileMediaDeletionItem(tempFile)))
                }
            }
            return null
        }

        private fun videoStream(
                context: Context,
                resolver: ContentResolver,
                mediaUri: Uri,
                defaultType: String?
        ): MediaStreamData? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
                return null
            }
            val ext = mediaUri.lastPathSegment.substringAfterLast(".")
            val pfd = resolver.openFileDescriptor(mediaUri, "r")
            val strategy = MediaFormatStrategyPresets.createAndroid720pStrategy()
            val listener = object : MediaTranscoder.Listener {
                override fun onTranscodeFailed(exception: Exception?) {
                }

                override fun onTranscodeCompleted() {
                }

                override fun onTranscodeProgress(progress: Double) {
                }

                override fun onTranscodeCanceled() {
                }

            }
            val tempFile = File.createTempFile("twidere__encoded_video_", ".$ext", context.cacheDir)
            val future = MediaTranscoder.getInstance().transcodeVideo(pfd.fileDescriptor,
                    tempFile.absolutePath, strategy, listener)
            try {
                future.get()
            } catch (e: Exception) {
                tempFile.delete()
                return null
            }
            return MediaStreamData(ContentLengthInputStream(tempFile.inputStream(),
                    tempFile.length()), defaultType, null, listOf(FileMediaDeletionItem(tempFile)))
        }

        internal class MediaStreamData(
                val stream: ContentLengthInputStream?,
                val type: String?,
                val geometry: Point?,
                val deleteOnSuccess: List<MediaDeletionItem>?
        )


    }

}
