package com.dsh.mobile.ui.components

import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

@Composable
fun BiometricLockOverlay(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context as FragmentActivity }
    var message by remember { mutableStateOf("验证身份以继续") }
    var promptActive by remember { mutableStateOf(false) }

    // 可重复触发的验证流程：失败/取消后由「重试」按钮再次拉起 BiometricPrompt；
    // promptActive 防重入——提示框仍活跃时不再创建第二个 BiometricPrompt。
    fun authenticate() {
        if (promptActive) return
        promptActive = true
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    promptActive = false
                    onUnlocked()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    promptActive = false
                    message = errString.toString()
                }
                override fun onAuthenticationFailed() {
                    promptActive = false
                    message = "验证失败，请重试"
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("DSH Remote 已锁定")
            .setSubtitle("需要身份验证才能操控电脑")
            .setAllowedAuthenticators(
                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
            .build()
        prompt.authenticate(info)
    }

    LaunchedEffect(Unit) {
        authenticate()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Lock, null,
                Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text(message, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(24.dp))
            Button(onClick = { authenticate() }, enabled = !promptActive) {
                Text("重试")
            }
        }
    }
}
