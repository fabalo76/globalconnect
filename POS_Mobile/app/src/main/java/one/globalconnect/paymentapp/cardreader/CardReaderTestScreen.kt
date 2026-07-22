package one.globalconnect.paymentapp.cardreader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import one.globalconnect.paymentapp.cardreader.EmvTag
import com.nexgo.oaf.apiv3.device.reader.CardSlotTypeEnum
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.ui.theme.color_alert
import one.globalconnect.paymentapp.ui.theme.color_error
import one.globalconnect.paymentapp.ui.theme.color_secondaryFive
import one.globalconnect.paymentapp.ui.theme.color_secondaryThree
import one.globalconnect.paymentapp.ui.theme.color_success
import one.globalconnect.paymentapp.ui.theme.color_white

@Composable
fun CardReaderTestScreen(
    modifier: Modifier = Modifier,
    viewModel: CardReaderViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    val (statusText, statusColor) = statusDisplay(uiState.status)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(color_secondaryThree)
            .verticalScroll(scrollState)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.card_reader_test),
            color = color_white,
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
        )

        Text(
            text = stringResource(R.string.card_reader_instruction),
            color = color_white,
            fontSize = 16.sp,
            lineHeight = 22.sp,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = { viewModel.startCardSearch(amount = "0.00") },
                enabled = !uiState.isSearching,
                colors = ButtonDefaults.buttonColors(
                    containerColor = color_white,
                    contentColor = color_secondaryFive,
                ),
            ) {
                Text(
                    text = stringResource(R.string.card_reader_start),
                    fontWeight = FontWeight.SemiBold,
                )
            }

            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = { viewModel.cancelCardSearch() },
                enabled = uiState.isSearching,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = color_white,
                ),
                border = BorderStroke(1.dp, color_white),
            ) {
                Text(
                    text = stringResource(R.string.card_reader_cancel),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        if (uiState.isSearching) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                color = color_white,
                trackColor = color_white.copy(alpha = 0.3f),
            )
        }

        Text(
            text = statusText,
            color = statusColor,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )

        uiState.cardData?.let { data ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = color_white),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    InfoRow(
                        label = stringResource(R.string.card_reader_return_code),
                        value = data.returnCode.toString(),
                    )

                    InfoRow(
                        label = stringResource(R.string.card_reader_entry_method),
                        value = slotLabel(data.slotType),
                    )

                    InfoRow(
                        label = stringResource(R.string.card_reader_icc_flag),
                        value = stringResource(if (data.isIcc) R.string.yes else R.string.no),
                    )

                    data.rfCardType?.name?.takeIf { it.isNotBlank() }?.let {
                        InfoRow(
                            label = stringResource(R.string.card_reader_rf_type),
                            value = it,
                        )
                    }

                    data.csn?.takeIf { it.isNotBlank() }?.let {
                        InfoRow(
                            label = stringResource(R.string.card_reader_csn),
                            value = it,
                        )
                    }

                    data.maskedCardNumber?.takeIf { it.isNotBlank() }?.let {
                        InfoRow(
                            label = stringResource(R.string.card_reader_masked_number),
                            value = it,
                        )
                    }

                    data.cardNumber?.takeIf { it.isNotBlank() }?.let {
                        InfoRow(
                            label = stringResource(R.string.card_reader_card_number),
                            value = it,
                        )
                    }

                    data.expiryDate?.takeIf { it.isNotBlank() }?.let {
                        InfoRow(
                            label = stringResource(R.string.card_reader_expiry),
                            value = it,
                        )
                    }

                    data.serviceCode?.takeIf { it.isNotBlank() }?.let {
                        InfoRow(
                            label = stringResource(R.string.card_reader_service_code),
                            value = it,
                        )
                    }

                    data.track1?.takeIf { it.isNotBlank() }?.let {
                        TrackRow(number = 1, value = it)
                    }

                    data.track2?.takeIf { it.isNotBlank() }?.let {
                        TrackRow(number = 2, value = it)
                    }

                    data.track3?.takeIf { it.isNotBlank() }?.let {
                        TrackRow(number = 3, value = it)
                    }

                    if (data.emvTags.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.card_reader_emv_tags),
                            style = MaterialTheme.typography.labelMedium,
                            color = color_secondaryFive,
                        )
                        data.emvTags.forEach { tag ->
                            EmvTagRow(tag)
                        }
                    }

                    data.rawEmvData?.takeIf { it.isNotBlank() }?.let { raw ->
                        Text(
                            text = stringResource(R.string.card_reader_emv_raw),
                            style = MaterialTheme.typography.labelMedium,
                            color = color_secondaryFive,
                        )
                        SelectionContainer {
                            Text(
                                text = raw,
                                style = MaterialTheme.typography.bodyMedium,
                                color = color_secondaryFive,
                                textAlign = TextAlign.Start,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun statusDisplay(status: CardReaderStatus): Pair<String, Color> {
    val color = when (status) {
        CardReaderStatus.Success -> color_success
        is CardReaderStatus.Error -> color_error
        CardReaderStatus.SwipeIncorrect -> color_alert
        CardReaderStatus.MultipleCards -> color_alert
        else -> color_white
    }

    val message = when (status) {
        CardReaderStatus.Idle -> stringResource(R.string.card_reader_ready)
        CardReaderStatus.Waiting -> stringResource(R.string.card_reader_waiting)
        CardReaderStatus.SwipeIncorrect -> stringResource(R.string.card_reader_swipe_incorrect)
        CardReaderStatus.MultipleCards -> stringResource(R.string.card_reader_multiple_cards)
        CardReaderStatus.ProcessingEmv -> stringResource(R.string.card_reader_processing)
        CardReaderStatus.Success -> stringResource(R.string.card_reader_success)
        is CardReaderStatus.Error -> stringResource(R.string.card_reader_error, status.reason)
    }

    return message to color
}

@Composable
private fun slotLabel(slotType: CardSlotTypeEnum?): String {
    return when (slotType) {
        CardSlotTypeEnum.SWIPE -> stringResource(R.string.card_reader_slot_swipe)
        CardSlotTypeEnum.ICC1, CardSlotTypeEnum.ICC2 -> stringResource(R.string.card_reader_slot_icc)
        CardSlotTypeEnum.RF -> stringResource(R.string.card_reader_slot_rf)
        null -> stringResource(R.string.card_reader_slot_unknown)
        else -> slotType.name
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color_secondaryFive,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = color_secondaryFive,
            overflow = TextOverflow.Ellipsis,
            maxLines = 3,
        )
    }
}

@Composable
private fun TrackRow(number: Int, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.card_reader_track, number),
            style = MaterialTheme.typography.labelMedium,
            color = color_secondaryFive,
        )
        SelectionContainer {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = color_secondaryFive,
                textAlign = TextAlign.Start,
            )
        }
    }
}

@Composable
private fun EmvTagRow(tag: EmvTag) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.card_reader_emv_tag, tag.tag),
            style = MaterialTheme.typography.labelMedium,
            color = color_secondaryFive,
        )
        SelectionContainer {
            Text(
                text = tag.value,
                style = MaterialTheme.typography.bodyMedium,
                color = color_secondaryFive,
                textAlign = TextAlign.Start,
            )
        }
    }
}
