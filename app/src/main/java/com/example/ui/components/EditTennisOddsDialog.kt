package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import com.example.quant.TennisMatchAnalysis
import java.util.Locale

@Composable
fun EditTennisOddsDialog(
    match: TennisMatchAnalysis,
    onDismiss: () -> Unit,
    onConfirm: (
        totalGamesLine: Double,
        p1GameHcLine: Double,
        oddP1Ml: Double,
        oddP2Ml: Double,
        oddP1Set1: Double,
        oddP2Set1: Double,
        oddGamesOver: Double,
        oddGamesUnder: Double,
        oddP1Hc: Double,
        oddP2Hc: Double,
        oddStraight: Double,
        oddOverSets: Double,
        oddTiebreak: Double
    ) -> Unit
) {
    val raw = match.rawMatch
    var totalGamesStr by remember(raw.id) { mutableStateOf(String.format(Locale.US, "%.1f", raw.totalGamesLine)) }
    var hcP1Str by remember(raw.id) { mutableStateOf(String.format(Locale.US, "%.1f", raw.p1GameHandicapLine)) }
    var ml1Str by remember(raw.id) { mutableStateOf(String.format(Locale.US, "%.2f", raw.oddP1Ml)) }
    var ml2Str by remember(raw.id) { mutableStateOf(String.format(Locale.US, "%.2f", raw.oddP2Ml)) }
    var set1J1Str by remember(raw.id) { mutableStateOf(String.format(Locale.US, "%.2f", raw.oddP1Set1)) }
    var set1J2Str by remember(raw.id) { mutableStateOf(String.format(Locale.US, "%.2f", raw.oddP2Set1)) }
    var gOverStr by remember(raw.id) { mutableStateOf(String.format(Locale.US, "%.2f", raw.oddGamesOver)) }
    var gUnderStr by remember(raw.id) { mutableStateOf(String.format(Locale.US, "%.2f", raw.oddGamesUnder)) }
    var hc1OddStr by remember(raw.id) { mutableStateOf(String.format(Locale.US, "%.2f", raw.oddP1GameHc)) }
    var hc2OddStr by remember(raw.id) { mutableStateOf(String.format(Locale.US, "%.2f", raw.oddP2GameHc)) }
    var straightStr by remember(raw.id) { mutableStateOf(String.format(Locale.US, "%.2f", raw.oddP1StraightSets)) }
    var overSetsStr by remember(raw.id) { mutableStateOf(String.format(Locale.US, "%.2f", raw.oddOverSets)) }
    var tbStr by remember(raw.id) { mutableStateOf(String.format(Locale.US, "%.2f", raw.oddTiebreakYes)) }

    fun parseOdd(s: String, fallback: Double): Double =
        s.replace(',', '.').toDoubleOrNull()?.coerceIn(1.02, 40.0) ?: fallback

    fun parseLine(s: String, fallback: Double, minV: Double, maxV: Double): Double =
        s.replace(',', '.').toDoubleOrNull()?.coerceIn(minV, maxV) ?: fallback

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Linhas & Odds Betclic Ténis",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "${raw.jogador1} vs ${raw.jogador2}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = "Atualiza as linhas e odds exatas da Betclic.pt para recalcular o modelo Markov, Edge, Kelly e Monte Carlo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TennisField("Total Jogos", totalGamesStr, { totalGamesStr = it }, Modifier.weight(1f))
                    TennisField("HC J1 (ex -2.5)", hcP1Str, { hcP1Str = it }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TennisField("ML J1", ml1Str, { ml1Str = it }, Modifier.weight(1f))
                    TennisField("ML J2", ml2Str, { ml2Str = it }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TennisField("Set 1 J1", set1J1Str, { set1J1Str = it }, Modifier.weight(1f))
                    TennisField("Set 1 J2", set1J2Str, { set1J2Str = it }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TennisField("Mais Jogos", gOverStr, { gOverStr = it }, Modifier.weight(1f))
                    TennisField("Menos Jogos", gUnderStr, { gUnderStr = it }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TennisField("Odd HC J1", hc1OddStr, { hc1OddStr = it }, Modifier.weight(1f))
                    TennisField("Odd HC J2", hc2OddStr, { hc2OddStr = it }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TennisField(if (raw.bestOfSets == 5) "J1 3-0" else "J1 2-0", straightStr, { straightStr = it }, Modifier.weight(1f))
                    TennisField(if (raw.bestOfSets == 5) "Mais 3.5 Sets" else "Mais 2.5 Sets", overSetsStr, { overSetsStr = it }, Modifier.weight(1f))
                }
                TennisField("Tie-Break (Sim)", tbStr, { tbStr = it }, Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        parseLine(totalGamesStr, raw.totalGamesLine, 14.5, 52.5),
                        parseLine(hcP1Str, raw.p1GameHandicapLine, -14.5, 14.5),
                        parseOdd(ml1Str, raw.oddP1Ml),
                        parseOdd(ml2Str, raw.oddP2Ml),
                        parseOdd(set1J1Str, raw.oddP1Set1),
                        parseOdd(set1J2Str, raw.oddP2Set1),
                        parseOdd(gOverStr, raw.oddGamesOver),
                        parseOdd(gUnderStr, raw.oddGamesUnder),
                        parseOdd(hc1OddStr, raw.oddP1GameHc),
                        parseOdd(hc2OddStr, raw.oddP2GameHc),
                        parseOdd(straightStr, raw.oddP1StraightSets),
                        parseOdd(overSetsStr, raw.oddOverSets),
                        parseOdd(tbStr, raw.oddTiebreakYes)
                    )
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("confirm_tennis_odds_button")
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
private fun TennisField(
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
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}
