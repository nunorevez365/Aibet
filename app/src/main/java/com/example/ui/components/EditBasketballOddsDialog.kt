package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.quant.BasketballMatchAnalysis
import java.util.Locale

@Composable
fun EditBasketballOddsDialog(
    match: BasketballMatchAnalysis,
    onDismiss: () -> Unit,
    onConfirm: (
        totalLine: Double,
        homeSpreadLine: Double,
        q1TotalLine: Double,
        oddHomeMl: Double,
        oddAwayMl: Double,
        oddHomeSpread: Double,
        oddAwaySpread: Double,
        oddTotalOver: Double,
        oddTotalUnder: Double,
        oddQ1Over: Double,
        oddQ1Under: Double
    ) -> Unit
) {
    val raw = match.rawMatch
    var totalLine by remember(raw.id) { mutableStateOf(String.format(Locale.US, "%.1f", raw.totalLine)) }
    var homeSpread by remember(raw.id) { mutableStateOf(String.format(Locale.US, "%.1f", raw.homeSpreadLine)) }
    var q1Line by remember(raw.id) { mutableStateOf(String.format(Locale.US, "%.1f", raw.q1TotalLine)) }
    var oddHomeMl by remember(raw.id) { mutableStateOf(String.format(Locale.US, "%.2f", raw.oddHomeMl)) }
    var oddAwayMl by remember(raw.id) { mutableStateOf(String.format(Locale.US, "%.2f", raw.oddAwayMl)) }
    var oddHomeSpread by remember(raw.id) { mutableStateOf(String.format(Locale.US, "%.2f", raw.oddHomeSpread)) }
    var oddAwaySpread by remember(raw.id) { mutableStateOf(String.format(Locale.US, "%.2f", raw.oddAwaySpread)) }
    var oddTotalOver by remember(raw.id) { mutableStateOf(String.format(Locale.US, "%.2f", raw.oddTotalOver)) }
    var oddTotalUnder by remember(raw.id) { mutableStateOf(String.format(Locale.US, "%.2f", raw.oddTotalUnder)) }
    var oddQ1Over by remember(raw.id) { mutableStateOf(String.format(Locale.US, "%.2f", raw.oddQ1Over)) }
    var oddQ1Under by remember(raw.id) { mutableStateOf(String.format(Locale.US, "%.2f", raw.oddQ1Under)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Linhas & Odds Betclic.pt",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${raw.equipaCasa} vs ${raw.equipaFora}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Ajusta as linhas oficiais da Betclic para recalcular Edge, quartos Q1–Q4, 1/4 Kelly e Monte Carlo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasketNumField("Linha Total", totalLine, { totalLine = it }, Modifier.weight(1f))
                    BasketNumField("Handicap Casa", homeSpread, { homeSpread = it }, Modifier.weight(1f))
                    BasketNumField("Linha Q1", q1Line, { q1Line = it }, Modifier.weight(1f))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasketNumField("Odd ML 1", oddHomeMl, { oddHomeMl = it }, Modifier.weight(1f))
                    BasketNumField("Odd ML 2", oddAwayMl, { oddAwayMl = it }, Modifier.weight(1f))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasketNumField("Odd HC Casa", oddHomeSpread, { oddHomeSpread = it }, Modifier.weight(1f))
                    BasketNumField("Odd HC Fora", oddAwaySpread, { oddAwaySpread = it }, Modifier.weight(1f))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasketNumField("Odd Mais Pts", oddTotalOver, { oddTotalOver = it }, Modifier.weight(1f))
                    BasketNumField("Odd Menos Pts", oddTotalUnder, { oddTotalUnder = it }, Modifier.weight(1f))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasketNumField("Odd Q1 Mais", oddQ1Over, { oddQ1Over = it }, Modifier.weight(1f))
                    BasketNumField("Odd Q1 Menos", oddQ1Under, { oddQ1Under = it }, Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    fun p(s: String, fb: Double) = s.replace(',', '.').toDoubleOrNull() ?: fb
                    onConfirm(
                        p(totalLine, raw.totalLine),
                        p(homeSpread, raw.homeSpreadLine),
                        p(q1Line, raw.q1TotalLine),
                        p(oddHomeMl, raw.oddHomeMl),
                        p(oddAwayMl, raw.oddAwayMl),
                        p(oddHomeSpread, raw.oddHomeSpread),
                        p(oddAwaySpread, raw.oddAwaySpread),
                        p(oddTotalOver, raw.oddTotalOver),
                        p(oddTotalUnder, raw.oddTotalUnder),
                        p(oddQ1Over, raw.oddQ1Over),
                        p(oddQ1Under, raw.oddQ1Under)
                    )
                },
                modifier = Modifier.testTag("confirm_edit_basket_odds_button")
            ) {
                Text("Recalcular")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
private fun BasketNumField(
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
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
    )
}
