package io.cosgriff.sightline.ui.screens.session

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.cosgriff.sightline.R

/**
 * Hands exported files to the share sheet through the app's FileProvider (`res/xml/file_paths.xml`), which serves
 * the export folder and nothing else. The receiving app gets read access to these files only.
 */
fun shareFiles(context: Context, share: ShareFiles) {
    val authority = "${context.packageName}.exports"
    val uris = share.files.map { FileProvider.getUriForFile(context, authority, it) }
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    send.type = share.mimeType
    send.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.session_share_title)))
}
