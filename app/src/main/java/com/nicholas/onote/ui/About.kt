package com.nicholas.onote.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lines = remember { buildAboutInfo(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About ONote") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (line in lines) {
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    )
}

private fun buildAboutInfo(context: Context): List<String> {
    val pm = context.packageManager
    val version = runCatching {
        pm.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(0)
        ).versionName
    }.getOrDefault("?")
    val hasPen = runCatching {
        pm.hasSystemFeature("com.sec.feature.spen_usp") ||
            pm.hasSystemFeature("android.hardware.stylus")
    }.getOrDefault(false)
    return listOf(
        "ONote",
        "Version $version (${context.packageName})",
        "${Build.MANUFACTURER} ${Build.MODEL}",
        "Android ${Build.VERSION.RELEASE} · SDK ${Build.VERSION.SDK_INT}",
        "S Pen reported: $hasPen"
    )
}