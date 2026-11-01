package com.launcher.tv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** What the TV shows for the phone page. */
data class PhoneInfo(val enabled: Boolean, val running: Boolean, val address: String?, val loginLink: String?, val pin: String)

fun qrBitmap(text: String, size: Int = 360): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

@Composable
fun PhonePanelContent(info: PhoneInfo, onNewPin: () -> Unit, onToggle: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier.background(Color(0xFF1C222B), RoundedCornerShape(18.dp)).padding(26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val link = info.loginLink
        if (info.enabled && info.running && link != null) {
            val bmp = remember(link) { qrBitmap(link).asImageBitmap() }
            Box(Modifier.background(Color.White, RoundedCornerShape(12.dp)).padding(8.dp)) {
                Image(bmp, "QR code", Modifier.size(190.dp), filterQuality = FilterQuality.None)
            }
            Spacer(Modifier.width(26.dp))
        }
        Column(Modifier.width(360.dp)) {
            Text("Phone page", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            when {
                !info.enabled -> Text("Turned off.", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                !info.running || info.address == null -> Text(
                    "Starting… If this stays, check that the box is on your home network.",
                    color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp),
                )
                else -> {
                    Text("Scan the code with your phone's camera, or open:", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    Text(info.address.orEmpty(), color = Accent, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                    Text("PIN", color = Faint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, modifier = Modifier.padding(top = 14.dp))
                    Text(info.pin.chunked(3).joinToString(" "), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                    Text(
                        "Search and send things to the TV, edit teams, add providers, change settings, back up. Works on your home Wi-Fi only.",
                        color = Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
            Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                PillButton("Done", onClose, primary = true)
                if (info.enabled) PillButton("New PIN", onNewPin)
                PillButton(if (info.enabled) "Turn off" else "Turn on", onToggle)
            }
        }
    }
}

@Composable
fun PhonePanel(info: PhoneInfo, onNewPin: () -> Unit, onToggle: () -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        PhonePanelContent(info, onNewPin, onToggle, onClose)
    }
}
