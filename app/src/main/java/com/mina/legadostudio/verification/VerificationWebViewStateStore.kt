package com.mina.legadostudio.verification

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.util.Log
import android.webkit.WebView
import java.io.File

class VerificationWebViewStateStore(private val context: Context) {
    private val dir = File(context.filesDir, "verification-webview-state").apply { mkdirs() }

    fun save(id: String, view: WebView) {
        runCatching {
            val bundle = Bundle()
            view.saveState(bundle)
            val parcel = Parcel.obtain()
            try {
                bundle.writeToParcel(parcel, 0)
                File(dir, "$id.bin").writeBytes(parcel.marshall())
            } finally { parcel.recycle() }
        }
    }

    fun restore(id: String, view: WebView): Boolean {
        val file = File(dir, "$id.bin")
        if (!file.isFile) return false
        return runCatching {
            val parcel = Parcel.obtain()
            try {
                val bytes = file.readBytes()
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)
                val bundle = parcel.readBundle(context.classLoader)
                if (bundle == null || bundle.isEmpty) {
                    Log.w(TAG, "restore $id: empty bundle")
                    return false
                }
                val list = view.restoreState(bundle)
                if (list == null || list.size == 0) {
                    Log.w(TAG, "restore $id: empty back-forward list")
                    return false
                }
                true
            } finally { parcel.recycle() }
        }.getOrElse {
            Log.w(TAG, "restore $id failed", it)
            false
        }
    }

    fun clear(id: String) { File(dir, "$id.bin").delete() }

    private companion object {
        const val TAG = "VerificationWebState"
    }
}
