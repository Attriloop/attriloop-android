package io.attriloop.sdk

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Captures the application context at process start so [Attriloop.attriloopId]
 * (`getAttriloopId()`) can mint and persist the install id before `configure`.
 * Declared in the library manifest with authority `${applicationId}.attriloop-init`,
 * not exported. It only stores the context: no I/O, no network.
 *
 * A host may remove it (`tools:node="remove"`); then call
 * [Attriloop.getAttriloopId] with a `Context` before `configure`.
 */
class AttriloopInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let(Attriloop::attachContext)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
