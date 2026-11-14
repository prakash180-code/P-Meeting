package com.prakash.pmeeting.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object Permissions {
    val CAMERA = Manifest.permission.CAMERA
    val RECORD_AUDIO = Manifest.permission.RECORD_AUDIO

    fun audioGranted(context: Context): Boolean =
        granted(context, RECORD_AUDIO)

    fun cameraGranted(context: Context): Boolean =
        granted(context, CAMERA)

    fun cameraAndMicGranted(context: Context): Boolean =
        granted(context, CAMERA) && granted(context, RECORD_AUDIO)

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
}
