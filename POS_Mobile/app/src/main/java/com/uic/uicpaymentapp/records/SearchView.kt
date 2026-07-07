package com.uic.uicpaymentapp.records

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import com.uic.uicpaymentapp.R

import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import com.uic.uicpaymentapp.ui.theme.color_black
import com.uic.uicpaymentapp.ui.theme.color_white


@Composable
fun SearchView(
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
    originalText: String = ""
) {
    var text by rememberSaveable { mutableStateOf(originalText) }
    val focusManager = LocalFocusManager.current
    TextField(
        value = text,
        onValueChange = { value ->
            onValueChange(value)
            text = value
        },
        modifier = modifier
            .fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onBackground,
            textDecoration = TextDecoration.None,
            textAlign = TextAlign.Start
        ),
        placeholder = {
            Text(
                fontSize = 14.sp,
                text = stringResource(id = R.string.search_by),
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Start
            )
        },
        leadingIcon = null,
        trailingIcon = null,
        singleLine = true,
        shape = RoundedCornerShape(40),
        colors = TextFieldDefaults.colors(
            focusedTextColor = MaterialTheme.colorScheme.outlineVariant,
            unfocusedTextColor = MaterialTheme.colorScheme.outline,
            disabledTextColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedLeadingIconColor = MaterialTheme.colorScheme.outlineVariant,
            unfocusedLeadingIconColor = MaterialTheme.colorScheme.outline,
            focusedTrailingIconColor = MaterialTheme.colorScheme.outlineVariant,
            unfocusedTrailingIconColor = MaterialTheme.colorScheme.outline,
            focusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant,
            unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
        ),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Search
        ),
        keyboardActions = KeyboardActions(onSearch = {
            focusManager.clearFocus()
        })
    )
}

@Composable
fun BackButton(
    onBackPressed: () -> Unit,
    modifier: Modifier = Modifier,
    text: String = stringResource(id = R.string.back),
) {
    TextButton(
        onClick = onBackPressed,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = color_white,
            contentColor = color_black, // Color del texto
        ),
    ) {
        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Left")
        Text(text)
    }
}
