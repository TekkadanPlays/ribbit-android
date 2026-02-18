package com.example.cybin.nip55

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo

/**
 * Detects installed NIP-55 compatible external signers (e.g. Amber).
 */
object AmberDetector {

    /** Check if any NIP-55 external signer is installed. */
    fun isExternalSignerInstalled(context: Context): Boolean =
        getExternalSignersInstalled(context).isNotEmpty()

    /** Get a list of installed NIP-55 signer packages. */
    fun getExternalSignersInstalled(context: Context): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("nostrsigner:"))
        return context.packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )
    }
}
