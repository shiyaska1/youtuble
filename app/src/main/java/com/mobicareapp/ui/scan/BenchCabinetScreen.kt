package com.mobicareapp.ui.scan

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.mobicareapp.applock.AppLockManager
import com.mobicareapp.handwriting.CalculatorNotes
import com.mobicareapp.ui.handwriting.TypeTextDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal

/** The numbers and rules of the bench-cabinet calculation (a port of bench-cabinet-calc.html). */
private object BenchCabinetCalc {
    /** Rounded to 2 decimals, without a trailing ".0" (5, not 5.0), like the web page shows it. */
    fun fmt(n: Double): String {
        val rounded = Math.floor(n * 100 + 0.5) / 100.0
        return BigDecimal.valueOf(rounded).stripTrailingZeros().toPlainString()
    }

    fun num(text: String): Double = text.trim().toDoubleOrNull() ?: 0.0

    /** Total length minus everything placed on it. */
    fun balance(v: List<String>): Double =
        num(v[LENGTH]) - 90 * num(v[N90]) - 75 * num(v[N75]) - 60 * num(v[N60]) - 45 * num(v[N45]) -
            num(v[LEN]) * num(v[ANO]) - num(v[LEN2]) * num(v[AN2]) - num(v[COR]) * num(v[CORLENGTH]) - num(v[EDGE])

    /** Cabinet count: every counted piece adds one space. */
    fun cabinetCount(v: List<String>): Int =
        Math.floor(num(v[N90]) + num(v[N60]) + num(v[N75]) + num(v[N45]) + num(v[COR]) + num(v[ANO]) + num(v[AN2])).toInt().coerceIn(0, 100000)

    /** Splits the balance over 1..k equal spaces and keeps the ones between 50 and 120. */
    fun cabinets(v: List<String>): String {
        val k = cabinetCount(v)
        val bal = balance(v)
        val out = (1..k).mapNotNull { i ->
            val s = bal / i
            if (s in 50.0..120.0) "space ${fmt(s)}   ks $i" else null
        }
        return if (out.isEmpty()) "No combination between 50 and 120." else out.joinToString("\n")
    }

    /** Splits the balance over 1..N equal frames and keeps the lengths between 80 and 200. */
    fun frames(v: List<String>): String {
        val bal = balance(v)
        val n = Math.min(Math.floor(bal), 100000.0).toInt()
        val out = (1..n).mapNotNull { i ->
            val s = bal / i
            if (s in 80.0..200.0) "Frame Length : ${fmt(s)}   frame No $i" else null
        }
        return if (out.isEmpty()) "No combination between 80 and 200." else out.joinToString("\n")
    }

    // Positions of the inputs in the list that holds them.
    const val LENGTH = 0
    const val N90 = 1
    const val N75 = 2
    const val N60 = 3
    const val N45 = 4
    const val ANO = 5
    const val LEN = 6
    const val AN2 = 7
    const val LEN2 = 8
    const val COR = 9
    const val CORLENGTH = 10
    const val EDGE = 11
    const val COUNT = 12
}

/**
 * Bench cabinet calculator: the total length, the pieces placed on it (90/75/60/45 counts, An, An2,
 * Cor, Edge), and the live balance. "Cabinets" and "Frames" list the equal splits of the balance
 * that fit the allowed range into the last box, which is selectable and has a Copy button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchCabinetScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)

    // Keep the app lock off while this screen is in use.
    DisposableEffect(Unit) {
        AppLockManager.acquireLockHold()
        onDispose { AppLockManager.releaseLockHold() }
    }
    var showNotes by remember { mutableStateOf(false) }

    // Kept across rotation / recreation.
    val values = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) {
        List(BenchCabinetCalc.COUNT) { "" }.toMutableStateList()
    }
    var result by rememberSaveable { mutableStateOf("") }
    var cabinetsShown by rememberSaveable { mutableStateOf("") }

    val balance = BenchCabinetCalc.fmt(BenchCabinetCalc.balance(values))
    val bigText = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold)

    // A number box that writes into values[index].
    @Composable
    fun NumberField(index: Int, label: String, modifier: Modifier = Modifier, decimal: Boolean = true) {
        OutlinedTextField(
            value = values[index],
            onValueChange = { values[index] = it.filter { c -> c.isDigit() || (decimal && c == '.') } },
            label = { Text(label) },
            singleLine = true,
            textStyle = bigText,
            keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
            modifier = modifier
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bench cabinet calculator") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(BenchCabinetCalc.LENGTH, "Total Length", Modifier.weight(1f))
                OutlinedTextField(
                    value = balance,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Balance") },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f)
                )
            }
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(BenchCabinetCalc.N90, "90 (count)", Modifier.weight(1f), decimal = false)
                NumberField(BenchCabinetCalc.N75, "75 (count)", Modifier.weight(1f), decimal = false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(BenchCabinetCalc.N60, "60 (count)", Modifier.weight(1f), decimal = false)
                NumberField(BenchCabinetCalc.N45, "45 (count)", Modifier.weight(1f), decimal = false)
            }

            // Count × length pairs.
            listOf(
                Triple("An", BenchCabinetCalc.ANO, BenchCabinetCalc.LEN),
                Triple("An2", BenchCabinetCalc.AN2, BenchCabinetCalc.LEN2),
                Triple("Cor", BenchCabinetCalc.COR, BenchCabinetCalc.CORLENGTH)
            ).forEach { (name, countIndex, lengthIndex) ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("$name\n(count × length)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(0.9f))
                    NumberField(countIndex, "count", Modifier.weight(1f), decimal = false)
                    NumberField(lengthIndex, "length", Modifier.weight(1f))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(BenchCabinetCalc.EDGE, "Edge (deduction)", Modifier.weight(1f))
                OutlinedTextField(
                    value = cabinetsShown,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Cabinets (total count)") },
                    singleLine = true,
                    textStyle = bigText,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        cabinetsShown = BenchCabinetCalc.cabinetCount(values).toString()
                        result = BenchCabinetCalc.cabinets(values)
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Cabinets\n(space 50–120)") }
                Button(
                    onClick = { result = BenchCabinetCalc.frames(values) },
                    modifier = Modifier.weight(1f)
                ) { Text("Frames\n(length 80–200)") }
                OutlinedButton(
                    onClick = {
                        for (i in values.indices) values[i] = ""
                        cabinetsShown = ""
                        result = ""
                    }
                ) { Text("Clear") }
            }

            // The result: a read-only box whose text can be selected and copied, plus a Copy button.
            OutlinedTextField(
                value = result,
                onValueChange = {},
                readOnly = true,
                label = { Text("Result (tap and hold to select, or use Copy)") },
                textStyle = TextStyle(fontSize = 18.sp, fontFamily = FontFamily.Monospace),
                minLines = 8,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                enabled = result.isNotBlank(),
                onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Bench cabinet result", result))
                    Toast.makeText(context, "Copied to the clipboard", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Spacer(Modifier.height(0.dp).padding(start = 8.dp))
                Text("Copy result")
            }
            // A text popup (the same one the writing screen has) whose saved texts are a group of their own.
            OutlinedButton(onClick = { showNotes = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Text notes (type, save with a title, search, copy)")
            }
            Text(
                "Balance = Total − (90×n90 + 75×n75 + 60×n60 + 45×n45 + An + An2 + Cor + Edge). It updates as you type.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Room so the last items are not hidden behind the bottom bar (Save / Library / Scan / Record).
            Spacer(Modifier.height(96.dp))
        }
    }

    if (showNotes) {
        TypeTextDialog(
            onInsert = { _, _ -> },
            onDismiss = { showNotes = false },
            store = CalculatorNotes,
            simple = true,
            initialText = result
        )
    }
}
