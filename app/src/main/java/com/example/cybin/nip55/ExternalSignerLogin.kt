package com.example.cybin.nip55

import android.content.Intent

/**
 * Builds the NIP-55 login intent for Amber (or compatible external signers).
 *
 * The intent launches the signer app's login activity, requesting the specified permissions.
 * The result contains the user's public key in the "result" extra.
 */
object ExternalSignerLogin {

    private const val SIGNER_ACTION = "android.intent.action.VIEW"
    private const val SIGNER_CATEGORY = "android.intent.category.BROWSABLE"

    /**
     * Create a login intent for the external signer.
     *
     * @param permissions List of permissions to request.
     * @param packageName Package name of the signer app (e.g. "com.greenart7c3.nostrsigner").
     */
    fun createIntent(
        permissions: List<Permission> = emptyList(),
        packageName: String = "",
    ): Intent {
        val permissionsJson = "[${permissions.joinToString(",") { it.toJson() }}]"
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("nostrsigner:"))
        intent.putExtra("type", "get_public_key")
        intent.putExtra("permissions", permissionsJson)
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (packageName.isNotBlank()) {
            intent.`package` = packageName
        }
        return intent
    }
}
