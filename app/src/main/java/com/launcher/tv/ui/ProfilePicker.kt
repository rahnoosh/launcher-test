package com.launcher.tv.ui

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.launcher.tv.data.Profile
import com.launcher.tv.data.Profiles
import kotlinx.coroutines.delay

private val AvatarColors = listOf(
    Color(0xFF7FB2FF), Color(0xFFFFA65C), Color(0xFFC9B6FF), Color(0xFF6FD3A8),
    Color(0xFFFF7A8A), Color(0xFFFFD166), Color(0xFF5ED1E8),
)

fun Profile.avatarColor(): Color = AvatarColors[(color % AvatarColors.size + AvatarColors.size) % AvatarColors.size]

/** A round avatar with the person's initial. */
@Composable
fun Avatar(p: Profile, size: Dp) {
    val c = p.avatarColor()
    Box(Modifier.size(size).background(Brush.linearGradient(listOf(c, c.darker(0.35f))), CircleShape), contentAlignment = Alignment.Center) {
        Text(p.name.take(1).uppercase(), color = Color(0xFF0B0D12), fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Who's watching: everyone's avatar (OK switches, hold OK renames or removes), Add profile, and the
 * current person's Trakt/Simkl accounts.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BoxScope.ProfilePicker(
    profiles: List<Profile>,
    activeId: String,
    onPick: (Profile) -> Unit,
    onEdit: (Profile) -> Unit,
    onAdd: () -> Unit,
    onAccounts: () -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { first.requestFocus() }
    }
    val backdrop = LocalBackdrop.current
    Box(
        Modifier.fillMaxSize().drawBehind {
            val tiny = backdrop.tiny
            if (tiny != null) {
                drawImage(tiny, IntOffset.Zero, IntSize(tiny.width, tiny.height), IntOffset.Zero, IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.High)
            } else {
                drawRect(Color(0xFF0B1426))
            }
            drawRect(Color(0x9905080F))
        },
    )
    val active = profiles.firstOrNull { it.id == activeId } ?: profiles.first()
    Column(
        Modifier.align(Alignment.Center).focusGroup().focusProperties { exit = { FocusRequester.Cancel } },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Who's watching?", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Light)
        Row(Modifier.padding(top = 34.dp), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
            profiles.forEach { p ->
                ProfileTile(
                    profile = p,
                    note = when {
                        p.id == activeId -> "Watching now"
                        p.kids -> "Kids"
                        else -> ""
                    },
                    modifier = if (p.id == active.id) Modifier.focusRequester(first) else Modifier,
                    onClick = { onPick(p) },
                    onLongClick = { onEdit(p) },
                )
            }
            if (profiles.size < Profiles.MAX) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        onClick = onAdd,
                        modifier = Modifier.size(104.dp).glass(CircleShape),
                        shape = ClickableSurfaceDefaults.shape(CircleShape),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.White, contentColor = Color.White, focusedContentColor = Color(0xFF0B0D12)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("+", fontSize = 40.sp, fontWeight = FontWeight.Light) }
                    }
                    Text("Add profile", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                    Text("", fontSize = 10.sp)
                }
            }
        }
        Row(Modifier.padding(top = 30.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Trakt & Simkl for ${active.name}", onAccounts)
            PillButton("Close", onDismiss)
        }
        Text("Hold OK on someone to rename or remove them", color = Faint, fontSize = 11.sp, modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable
private fun ProfileTile(profile: Profile, note: String, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier.size(104.dp),
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = CircleShape)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        ) {
            Avatar(profile, 104.dp)
        }
        Text(profile.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        Text(note, color = Dim, fontSize = 10.sp)
    }
}

// ---------------- native dialogs (the TV keyboard works best with real EditTexts) ----------------

/** Runs [block] straight away, or after the PIN while a kids profile is active. */
fun guarded(context: Context, block: () -> Unit) {
    if (!Profiles.locked(context)) return block()
    askPin(context, "Grown-ups only", "Enter the PIN to continue.") { ok -> if (ok) block() }
}

fun askPin(context: Context, title: String, message: String, onResult: (Boolean) -> Unit) {
    val field = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        hint = "PIN"
    }
    AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle(title)
        .setMessage(message)
        .setView(padded(context, field))
        .setPositiveButton("OK") { _, _ ->
            val ok = Profiles.checkPin(context, field.text.toString())
            if (ok) Profiles.unlock() else Toast.makeText(context, "Wrong PIN", Toast.LENGTH_SHORT).show()
            onResult(ok)
        }
        .setNegativeButton("Cancel") { _, _ -> onResult(false) }
        .show()
}

/** Choose the 4-digit PIN that kids profiles ask for. */
fun choosePin(context: Context, onSet: () -> Unit) {
    val field = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        hint = "4 digits"
    }
    AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle("Choose a PIN")
        .setMessage("Kids profiles ask for it before Settings, Control Center, accounts, teams and switching profiles.")
        .setView(padded(context, field))
        .setPositiveButton("Save") { _, _ ->
            val pin = field.text.toString().trim()
            if (pin.length == 4 && pin.all { it.isDigit() }) {
                Profiles.setPin(context, pin)
                onSet()
            } else {
                Toast.makeText(context, "The PIN needs 4 digits", Toast.LENGTH_SHORT).show()
            }
        }
        .setNegativeButton("Cancel", null)
        .show()
}

/** Name, and whether it's a kids profile. */
fun showAddProfile(context: Context, onAdd: (String, Boolean) -> Unit) {
    val name = EditText(context).apply {
        hint = "Name"
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
    }
    val kids = CheckBox(context).apply { text = "Kids profile (asks for a PIN for grown-up things)" }
    val layout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, 0)
        addView(name); addView(kids)
    }
    AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle("Add a profile")
        .setMessage("Each profile keeps its own Trakt and Simkl, teams, settings and Continue Watching.")
        .setView(layout)
        .setPositiveButton("Add") { _, _ ->
            val isKids = kids.isChecked
            if (isKids && !Profiles.hasPin(context)) choosePin(context) { onAdd(name.text.toString(), true) }
            else onAdd(name.text.toString(), isKids)
        }
        .setNegativeButton("Cancel", null)
        .show()
}

/** Rename, or remove (everything it kept on the box goes too). */
fun showEditProfile(context: Context, profile: Profile, onRename: (String) -> Unit, onRemove: () -> Unit) {
    val name = EditText(context).apply {
        setText(profile.name)
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
    }
    val b = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle(profile.name)
        .setView(padded(context, name))
        .setPositiveButton("Save") { _, _ -> onRename(name.text.toString()) }
        .setNegativeButton("Cancel", null)
    if (profile.id != Profiles.MAIN) {
        b.setNeutralButton("Remove") { _, _ ->
            AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Remove ${profile.name}?")
                .setMessage("Their Trakt and Simkl sign-ins, teams, settings and Continue Watching are deleted from this box.")
                .setPositiveButton("Remove") { _, _ -> onRemove() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
    b.show()
}

private fun padded(context: Context, view: android.view.View): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    val pad = (20 * context.resources.displayMetrics.density).toInt()
    setPadding(pad, pad / 2, pad, 0)
    addView(view)
}
