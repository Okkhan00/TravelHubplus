package com.tabeer.travelhub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.tabeer.travelhub.security.BiometricHelper
import com.tabeer.travelhub.security.PinManager

@Composable
fun LockScreen(vm: AppViewModel, activity: FragmentActivity) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val bioAvailable = vm.settings.biometricEnabled && BiometricHelper.isAvailable(activity)

    fun tryBiometric() {
        BiometricHelper.prompt(activity, "Unlock TravelHub", onSuccess = { vm.unlockWithBiometric() }, onFailure = { })
    }
    LaunchedEffect(Unit) { if (bioAvailable) tryBiometric() }

    fun submit() {
        if (busy || pin.isEmpty()) return
        busy = true
        vm.unlock(pin) { r ->
            busy = false
            pin = ""
            error = when (r) {
                is PinManager.VerifyResult.Success -> null
                is PinManager.VerifyResult.Wrong -> "Wrong PIN. ${r.attemptsLeft} attempt(s) left before a temporary lockout."
                is PinManager.VerifyResult.LockedOut -> "Too many attempts. Try again at ${formatTime(r.untilMs)}."
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(32.dp).imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text("TravelHub is locked", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter { c -> c.isDigit() }.take(12) },
            label = { Text("PIN") },
            singleLine = true,
            isError = error != null,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { submit() }, enabled = !busy && pin.length >= 4, modifier = Modifier.fillMaxWidth()) { Text("Unlock") }
        if (bioAvailable) TextButton(onClick = { tryBiometric() }) { Text("Use fingerprint / face") }
        TextButton(onClick = { confirmReset = true }) { Text("Forgot PIN?") }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Forgot your PIN?") },
            text = {
                Text(
                    "A PIN can't be recovered or bypassed. To regain access, TravelHub can remove the lock — but this " +
                        "ERASES all saved logins and signs you out of every portal. Your portal list is kept."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; vm.resetForgottenPin() }) {
                    Text("Erase logins & remove lock", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}
