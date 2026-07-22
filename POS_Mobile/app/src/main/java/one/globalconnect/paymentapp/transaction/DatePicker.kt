package one.globalconnect.paymentapp.transaction

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.records.dateFormatter
import one.globalconnect.paymentapp.ui.theme.color_black
import one.globalconnect.paymentapp.ui.theme.color_secondaryThree
import one.globalconnect.paymentapp.ui.theme.color_white
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerTextField(
    date: LocalDate,
    label: String,
    setDate: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var showPicker by remember { mutableStateOf(false) }
    var dateState by remember { mutableStateOf(date.format(dateFormatter)) }
    OutlinedTextField(
        value = date.format(dateFormatter),
        label = { Text(label) },
        onValueChange = { dateState = it },
        readOnly = true,
        modifier = modifier,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.onBackground
        ),
        shape = RoundedCornerShape(24.dp),
        placeholder = { Text(stringResource(id = R.string.select_date)) },
        trailingIcon = {
            IconButton(
                onClick = {
                    showPicker = !showPicker
                }
            ) {
                Icon(Icons.Filled.ExpandMore, contentDescription = "Open")
            }
        }
    )
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = date.atStartOfDay().toInstant(
            ZoneOffset.UTC
        ).toEpochMilli()
    )
    if (showPicker) {
        ScaledDatePicker(onDialogClose = { showPicker = false }, datePickerState, setDate)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScaledDatePicker(
    onDialogClose: () -> Unit,
    datePickerState: DatePickerState,
    setDate: (Long) -> Unit
) {
    val confirmEnabled =
        remember { derivedStateOf { datePickerState.selectedDateMillis != null } }
    DatePickerDialog(
        onDismissRequest = onDialogClose,
        confirmButton = {
            TextButton(
                onClick = {
                    datePickerState.selectedDateMillis?.let {
                        setDate(it)
                    }
                    onDialogClose.invoke()
                },
                Modifier.border(1.dp, color_secondaryThree, shape = RoundedCornerShape(8.dp)),
                enabled = confirmEnabled.value
            ) {
                Text(stringResource(id = R.string.ok), Modifier.padding(start = 24.dp, end = 24.dp), color = color_black,)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDialogClose,
                Modifier
                    .border(1.dp, color_secondaryThree, shape = RoundedCornerShape(8.dp))
            ) {
                Text(stringResource(id = R.string.cancel), Modifier
                    .padding(start = 24.dp, end = 24.dp), color = color_black)
            }
        }
    ) {
        DatePicker(state = datePickerState,
            colors = DatePickerDefaults.colors(
                selectedDayContentColor = color_white,
                selectedDayContainerColor = color_secondaryThree,
                selectedYearContentColor = color_white,
                selectedYearContainerColor = color_secondaryThree
            ))
    }
}


fun utcToLocal(utcTime: Long): String {
    val utcInstant: Instant = Date(utcTime).toInstant()
    val there = ZonedDateTime.ofInstant(utcInstant, ZoneId.of("UTC"))
    val here = there.withZoneSameLocal(ZoneId.systemDefault()).toLocalDateTime()
    return here.format(dateFormatter)
}