package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.quant.MatchQuantAnalysis
import com.example.ui.QuantFormatters
import com.example.ui.theme.MonoFontFamily

@Composable
fun EditBetclicOddsDialog(
    match: MatchQuantAnalysis,
    onDismiss: () -> Unit,
    onConfirm: (Double, Double, Double, Double, Double, Double) -> Unit
) {
    val raw = match.rawMatch
    var odd1Text by remember { mutableStateOf(QuantFormatters.odd(raw.odd1)) }
    var oddXText by remember { mutableStateOf(QuantFormatters.odd(raw.oddX)) }
    var odd2Text by remember { mutableStateOf(QuantFormatters.odd(raw.odd2)) }
    var oddO25Text by remember { mutableStateOf(QuantFormatters.odd(raw.oddOver25)) }
    var oddU35Text by remember { mutableStateOf(QuantFormatters.odd(raw.oddUnder35)) }
    var oddBttsText by remember { mutableStateOf(QuantFormatters.odd(raw.oddBtts)) }

    fun parseOr(text: String, fallback: Double): Double =
        text.replace(',', '.').toDoubleOrNull()?.coerceIn(1.02, 50.0) ?: fallback

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Ajustar Odds Betclic.pt",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${raw.equipaCasa} vs ${raw.equipaFora}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OddInputField("Odd 1", odd1Text, { odd1Text = it }, Modifier.weight(1f))
                    OddInputField("Odd X", oddXText, { oddXText = it }, Modifier.weight(1f))
                    OddInputField("Odd 2", odd2Text, { odd2Text = it }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OddInputField("Mais 2.5", oddO25Text, { oddO25Text = it }, Modifier.weight(1f))
                    OddInputField("Menos 3.5", oddU35Text, { oddU35Text = it }, Modifier.weight(1f))
                    OddInputField("BTTS", oddBttsText, { oddBttsText = it }, Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        parseOr(odd1Text, raw.odd1),
                        parseOr(oddXText, raw.oddX),
                        parseOr(odd2Text, raw.odd2),
                        parseOr(oddO25Text, raw.oddOver25),
                        parseOr(oddU35Text, raw.oddUnder35),
                        parseOr(oddBttsText, raw.oddBtts)
                    )
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("confirm_betclic_odds_button")
            ) {
                Text("Recalcular")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
private fun OddInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        textStyle = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFontFamily),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}
