package com.music.bitchord.ui.tv.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.music.bitchord.gateway.Gateway
import com.music.bitchord.ui.tv.auth.TvAuthServer
import com.music.bitchord.ui.tv.auth.TvQrCodeView
import com.music.bitchord.ui.tv.components.TvActivityIndicator
import com.music.bitchord.ui.tv.components.TvDialog
import com.music.bitchord.ui.tv.components.TvDialogButton
import com.music.bitchord.ui.tv.theme.TvGlass
import com.music.bitchord.ui.tv.theme.TvType
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Fork: the Marble gateway account on this TV. Signed out, a code for the phone
 * to open: the TV serves a sign-in page there (TvAuthServer's /gateway), since
 * typing a password with a remote is no way to sign in. Signed in, who as and a
 * way out. Signing in is what puts the TV among the account's Connect devices
 * and counts what it plays in the account's stats.
 */
@Composable
fun TvGatewayDialog(onDismiss: () -> Unit) {
    val user by Gateway.username.collectAsState()
    if (user.isNotEmpty()) {
        TvDialog(
            title = "Marble Account",
            message = "Signed in as $user. This TV is one of your Connect devices, and what it plays counts in your stats.",
            onDismissRequest = onDismiss,
        ) {
            TvDialogButton(text = "Sign Out", destructive = true, onClick = { Gateway.signOut() })
            Spacer(modifier = Modifier.height(10.dp))
            TvDialogButton(text = "Done", onClick = onDismiss, initialFocus = true)
        }
        return
    }

    var url by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        TvAuthServer.onGatewayLogin = { name, password ->
            // On the server's own request thread, which waits for the answer.
            runBlocking { Gateway.signIn(name, password) }.exceptionOrNull()?.let {
                it.message?.takeIf(String::isNotBlank) ?: "That username or password didn't work."
            }
        }
        scope.launch {
            TvAuthServer.start()?.let { (port, ip) -> url = "http://$ip:$port/gateway" }
        }
        onDispose {
            TvAuthServer.onGatewayLogin = null
            TvAuthServer.stop()
        }
    }

    TvDialog(title = "Sign In to Marble", width = 576.dp, onDismissRequest = onDismiss) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(29.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                val link = url
                if (link != null) TvQrCodeView(content = link, size = 147.dp) else TvActivityIndicator(color = Color.Black)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Step(1, "Connect your phone to the same Wi-Fi as this TV.")
                Step(2, "Scan the code, or open ${url ?: "the address shown here"}.")
                Step(3, "Sign in with your Marble Music account. This TV signs in by itself.")
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        TvDialogButton(text = "Back", onClick = onDismiss, initialFocus = true, modifier = Modifier.width(256.dp))
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(21.dp)
                .clip(CircleShape)
                .background(TvGlass.Fill),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = number.toString(), style = TvType.Caption, color = TvGlass.TextPrimary)
        }
        Text(text = text, style = TvType.Callout, color = TvGlass.TextSecondary, modifier = Modifier.weight(1f))
    }
}
