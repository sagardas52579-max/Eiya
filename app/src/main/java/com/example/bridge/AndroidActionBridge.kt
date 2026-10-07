package com.example.bridge

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.model.ContactItem

sealed class BridgeResult {
    data class Success(val summary: String, val details: String, val toolName: String) : BridgeResult()
    data class MultipleContacts(val contacts: List<ContactItem>, val question: String) : BridgeResult()
    data class Failure(val reason: String, val toolName: String) : BridgeResult()
}

class AndroidActionBridge(private val context: Context) {

    // Fallback/cached contacts in case system contacts is empty on fresh emulator
    private val localDemoContacts = mutableListOf(
        ContactItem(name = "Mummy", number = "+91 98765 43210", type = "Mobile"),
        ContactItem(name = "Rahul Sharma", number = "+91 98111 22233", type = "Work"),
        ContactItem(name = "Rahul Verma", number = "+91 98222 33344", type = "Home"),
        ContactItem(name = "Dad", number = "+91 98333 44455", type = "Mobile"),
        ContactItem(name = "Priya", number = "+91 98444 55566", type = "Mobile")
    )

    fun hasCallPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Open WhatsApp or initiate WhatsApp chat
     */
    fun openWhatsApp(phoneNumber: String? = null, message: String? = null): BridgeResult {
        val cleanPhone = phoneNumber?.replace(Regex("[^0-9+]"), "")
        val encodedMessage = Uri.encode(message ?: "")

        val isInstalled = isPackageInstalled("com.whatsapp") || isPackageInstalled("com.whatsapp.w4b")

        if (cleanPhone.isNullOrBlank()) {
            if (isInstalled) {
                val launchIntent = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
                    ?: context.packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")
                    ?: Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://app"))
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return try {
                    context.startActivity(launchIntent)
                    BridgeResult.Success(
                        summary = "WhatsApp Opened",
                        details = "Successfully launched WhatsApp on device.",
                        toolName = "openWhatsApp"
                    )
                } catch (e: Exception) {
                    BridgeResult.Failure("Could not open WhatsApp: ${e.message}", "openWhatsApp")
                }
            } else {
                // Not installed: Open Play Store link gracefully
                return try {
                    val storeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.whatsapp")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(storeIntent)
                    BridgeResult.Success(
                        summary = "WhatsApp Not Installed",
                        details = "WhatsApp is not installed. Opened Google Play Store to install it.",
                        toolName = "openWhatsApp"
                    )
                } catch (e: Exception) {
                    // Fallback to web
                    openUrl("https://web.whatsapp.com")
                    BridgeResult.Success(
                        summary = "WhatsApp Web",
                        details = "Opened WhatsApp Web in browser.",
                        toolName = "openWhatsApp"
                    )
                }
            }
        } else {
            // Direct chat to phone number
            val url = if (encodedMessage.isNotBlank()) {
                "https://api.whatsapp.com/send?phone=$cleanPhone&text=$encodedMessage"
            } else {
                "https://api.whatsapp.com/send?phone=$cleanPhone"
            }
            return try {
                val chatIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    if (isInstalled) setPackage("com.whatsapp")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chatIntent)
                BridgeResult.Success(
                    summary = "WhatsApp Chat Initiated",
                    details = "Opening WhatsApp conversation with $cleanPhone.",
                    toolName = "openWhatsApp"
                )
            } catch (e: Exception) {
                BridgeResult.Failure("Failed to open WhatsApp chat: ${e.message}", "openWhatsApp")
            }
        }
    }

    /**
     * Open an installed app or standard Android utility
     */
    fun openApp(appName: String): BridgeResult {
        val lower = appName.trim().lowercase()

        when {
            lower.contains("whatsapp") -> {
                return openWhatsApp()
            }
            lower.contains("youtube") -> {
                val pkg = "com.google.android.youtube"
                if (isPackageInstalled(pkg)) {
                    val intent = context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (intent != null) {
                        context.startActivity(intent)
                        return BridgeResult.Success("YouTube Opened", "Launched YouTube app.", "openApp")
                    }
                }
                return openUrl("https://www.youtube.com")
            }
            lower.contains("instagram") -> {
                val pkg = "com.instagram.android"
                if (isPackageInstalled(pkg)) {
                    val intent = context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (intent != null) {
                        context.startActivity(intent)
                        return BridgeResult.Success("Instagram Opened", "Launched Instagram app.", "openApp")
                    }
                }
                return openUrl("https://www.instagram.com")
            }
            lower.contains("chrome") -> {
                val pkg = "com.android.chrome"
                if (isPackageInstalled(pkg)) {
                    val intent = context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (intent != null) {
                        context.startActivity(intent)
                        return BridgeResult.Success("Chrome Opened", "Launched Google Chrome.", "openApp")
                    }
                }
                return openUrl("https://www.google.com")
            }
            lower.contains("setting") -> {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return BridgeResult.Success("Settings Opened", "Opened device Settings.", "openApp")
            }
            lower.contains("camera") -> {
                val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                return try {
                    context.startActivity(intent)
                    BridgeResult.Success("Camera Opened", "Opened device Camera.", "openApp")
                } catch (e: Exception) {
                    BridgeResult.Failure("Camera app could not be opened: ${e.message}", "openApp")
                }
            }
            lower.contains("map") -> {
                val pkg = "com.google.android.apps.maps"
                if (isPackageInstalled(pkg)) {
                    val intent = context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (intent != null) {
                        context.startActivity(intent)
                        return BridgeResult.Success("Google Maps Opened", "Launched Google Maps.", "openApp")
                    }
                }
                return openUrl("https://maps.google.com")
            }
            lower.contains("spotify") -> {
                val pkg = "com.spotify.music"
                if (isPackageInstalled(pkg)) {
                    val intent = context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (intent != null) {
                        context.startActivity(intent)
                        return BridgeResult.Success("Spotify Opened", "Launched Spotify.", "openApp")
                    }
                }
                return openUrl("https://open.spotify.com")
            }
            lower.contains("gmail") || lower.contains("email") || lower.contains("mail") -> {
                val pkg = "com.google.android.gm"
                if (isPackageInstalled(pkg)) {
                    val intent = context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (intent != null) {
                        context.startActivity(intent)
                        return BridgeResult.Success("Gmail Opened", "Launched Gmail.", "openApp")
                    }
                }
                return openUrl("https://mail.google.com")
            }
        }

        // Dynamic lookup by app label
        try {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val apps = pm.queryIntentActivities(mainIntent, 0)
            for (resolveInfo in apps) {
                val label = resolveInfo.loadLabel(pm).toString().lowercase()
                if (label.contains(lower) || lower.contains(label)) {
                    val launchIntent = pm.getLaunchIntentForPackage(resolveInfo.activityInfo.packageName)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (launchIntent != null) {
                        context.startActivity(launchIntent)
                        return BridgeResult.Success(
                            summary = "${resolveInfo.loadLabel(pm)} Opened",
                            details = "Launched ${resolveInfo.loadLabel(pm)} on device.",
                            toolName = "openApp"
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        return BridgeResult.Failure("App '$appName' was not found on this device.", "openApp")
    }

    /**
     * Make a phone call directly or open the phone dialer prefilled
     */
    fun makeCall(phoneNumber: String): BridgeResult {
        val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
        if (cleanNumber.isBlank()) {
            return BridgeResult.Failure("Invalid phone number provided.", "makeCall")
        }

        return try {
            if (hasCallPermission()) {
                val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$cleanNumber")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(callIntent)
                BridgeResult.Success(
                    summary = "Calling $cleanNumber",
                    details = "Initiating phone call to $cleanNumber directly.",
                    toolName = "makeCall"
                )
            } else {
                // Open dialer prefilled as safe fallback
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanNumber")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(dialIntent)
                BridgeResult.Success(
                    summary = "Phone Dialer Opened",
                    details = "Dialer pre-filled with $cleanNumber (safe call flow).",
                    toolName = "makeCall"
                )
            }
        } catch (e: Exception) {
            BridgeResult.Failure("Unable to place call: ${e.message}", "makeCall")
        }
    }

    /**
     * Search contacts by name, handling 1 match, multiple matches, or 0 matches
     */
    fun callContact(contactName: String): BridgeResult {
        val cleanQuery = contactName.trim()
        val allContacts = loadAllContacts()

        // Normalize aliases
        val searchTerms = normalizeContactQuery(cleanQuery)

        val matches = allContacts.filter { contact ->
            searchTerms.any { term ->
                contact.name.contains(term, ignoreCase = true) ||
                        term.contains(contact.name, ignoreCase = true)
            }
        }.distinctBy { it.name to it.number }

        return when {
            matches.isEmpty() -> {
                BridgeResult.Failure(
                    reason = "No contact found with name '$cleanQuery' in phone contacts.",
                    toolName = "callContact"
                )
            }
            matches.size == 1 -> {
                val contact = matches.first()
                val callRes = makeCall(contact.number)
                when (callRes) {
                    is BridgeResult.Success -> BridgeResult.Success(
                        summary = "Calling ${contact.name}",
                        details = "Found ${contact.name} (${contact.number}). Initiating call.",
                        toolName = "callContact"
                    )
                    else -> callRes
                }
            }
            else -> {
                val namesList = matches.joinToString(separator = ", ") { "${it.name} (${it.number})" }
                BridgeResult.MultipleContacts(
                    contacts = matches,
                    question = "I found ${matches.size} contacts for '$cleanQuery': $namesList. Which one would you like to call?"
                )
            }
        }
    }

    /**
     * Open web URL in browser
     */
    fun openUrl(url: String): BridgeResult {
        val formatted = if (!url.startsWith("http://") && !url.startsWith("https://")) {
            "https://$url"
        } else {
            url
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(formatted)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            BridgeResult.Success(
                summary = "Opened URL",
                details = "Navigated to $formatted in browser.",
                toolName = "openUrl"
            )
        } catch (e: Exception) {
            BridgeResult.Failure("Failed to open URL $formatted: ${e.message}", "openUrl")
        }
    }

    /**
     * Load contacts from Android ContentResolver, falling back to local demo list
     */
    fun loadAllContacts(): List<ContactItem> {
        val list = mutableListOf<ContactItem>()

        if (hasContactsPermission()) {
            try {
                val cursor = context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.TYPE
                    ),
                    null,
                    null,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
                )

                cursor?.use {
                    val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val typeIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)

                    while (it.moveToNext()) {
                        val name = if (nameIdx >= 0) it.getString(nameIdx) ?: "" else ""
                        val num = if (numIdx >= 0) it.getString(numIdx) ?: "" else ""
                        val typeVal = if (typeIdx >= 0) it.getInt(typeIdx) else 1
                        val type = when (typeVal) {
                            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Mobile"
                            ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Home"
                            ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Work"
                            else -> "Other"
                        }
                        if (name.isNotBlank() && num.isNotBlank()) {
                            list.add(ContactItem(name, num, type))
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // If system database has contacts, return them combined with demo contacts
        val combined = (list + localDemoContacts).distinctBy { it.name.lowercase() to it.number }
        return combined
    }

    /**
     * Seeds demo contacts into device's Contacts Provider if write permissions allow
     */
    fun seedContactsIntoDevice(): Boolean {
        // Adds to local list guaranteed
        if (localDemoContacts.isEmpty()) {
            localDemoContacts.addAll(
                listOf(
                    ContactItem("Mummy", "+91 98765 43210", "Mobile"),
                    ContactItem("Rahul Sharma", "+91 98111 22233", "Work"),
                    ContactItem("Rahul Verma", "+91 98222 33344", "Home"),
                    ContactItem("Dad", "+91 98333 44455", "Mobile")
                )
            )
        }
        return true
    }

    private fun normalizeContactQuery(query: String): List<String> {
        val q = query.trim().lowercase()
        return when {
            q == "mom" || q == "mummy" || q == "maa" || q == "mother" ->
                listOf("mummy", "mom", "maa", "mother")
            q == "dad" || q == "papa" || q == "father" || q == "daddy" ->
                listOf("dad", "papa", "father", "daddy")
            else -> listOf(query.trim())
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
