package one.globalconnect.paymentapp.transaction.installments

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.ui.theme.*

@Composable
fun InstallmentChoiceScreen(title: String, subtitle: String = "", choices: List<Pair<String, String>>,
    onSelected: (String) -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.headlineSmall, color = color_secondaryFive)
        if (subtitle.isNotBlank()) Text(subtitle, Modifier.padding(horizontal = 16.dp), color = color_secondaryFive)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(6.dp)) {
            // Six rows remain visible on a typical terminal; larger lists scroll.
            val tileHeight = ((maxHeight - 30.dp) / 6).coerceIn(56.dp, 88.dp)
            LazyVerticalGrid(columns = GridCells.Fixed(2), verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(choices, key = { it.first }) { (id, label) ->
                    Button(onClick = { onSelected(id) }, modifier = Modifier.fillMaxWidth().height(tileHeight),
                        shape = menuButtonShape, contentPadding = PaddingValues(6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = color_secondaryFive, contentColor = color_white)) {
                        Text(label, fontSize = if (label.length <= 3) 28.sp else 20.sp,
                            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    }
                }
                if (choices.size % 2 != 0) item {
                    // Decorative filler has no action or accessibility button semantics.
                    Spacer(Modifier.fillMaxWidth().height(tileHeight).background(color_secondaryFive, menuButtonShape))
                }
            }
        }
        Button(onClick = onCancel,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp).width(250.dp).height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = color_primaryBrand, contentColor = color_secondaryFive)) {
            Text(stringResource(R.string.cancel), fontSize = 18.sp)
        }
    }
}
