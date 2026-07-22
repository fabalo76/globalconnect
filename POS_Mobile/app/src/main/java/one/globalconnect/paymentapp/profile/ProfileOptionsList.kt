package one.globalconnect.paymentapp.profile

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import one.globalconnect.paymentapp.ui.theme.color_grey90
import one.globalconnect.paymentapp.ui.theme.color_grey95
import one.globalconnect.paymentapp.ui.theme.GlobalConnectPaymentTheme


@Composable
fun ProfileRowEditText(
    _text: String,
    profileOptionType: ProfileOptionsType,
    onSaveSetting: (ProfileOptionsType, String) -> Unit,
    enabled: Boolean,
    errorOnInput: Boolean,
    leadingLabel: String? = null,
    modifier: Modifier = Modifier
) {
    TextField(
        modifier = modifier,
        value = _text,
        onValueChange = {it ->
            onSaveSetting(profileOptionType, it)
        },
        enabled = enabled,
        singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = color_grey95,
            unfocusedContainerColor = color_grey95,
            focusedTextColor = MaterialTheme.colorScheme.onBackground,
            disabledContainerColor = color_grey90,
            unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        textStyle = TextStyle(
            fontSize = 16.sp,
            textAlign = TextAlign.End
        ),
        shape = RoundedCornerShape(10.dp),
        leadingIcon = {
            Text(
                text = leadingLabel ?: profileOptionType.toUserLabel(),
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 16.dp),
                fontSize = 16.sp
            )
        },
        isError = errorOnInput,
        keyboardOptions = when (profileOptionType) {
            ProfileOptionsType.PhoneNumber -> KeyboardOptions.Default.copy(keyboardType = KeyboardType.Phone)
            ProfileOptionsType.Email -> KeyboardOptions.Default.copy(keyboardType = KeyboardType.Email)
            else -> KeyboardOptions.Default.copy(keyboardType = KeyboardType.Text, autoCorrectEnabled = false)
        },
//        supportingText = {
//            if (errorOnInput) {
//                Text(
//                    modifier = Modifier.fillMaxWidth(),
//                    text = "Invalid input",
//                    color = MaterialTheme.colorScheme.error
//                )
//            }
//        }
    )
}

@Preview(showBackground = true, name = "Profile row enabled")
@Composable
private fun ProfileRowEditTextPreview() {
    GlobalConnectPaymentTheme {
        ProfileRowEditText(
            _text = "Sample Merchant",
            profileOptionType = ProfileOptionsType.Name,
            onSaveSetting = { _, _ -> },
            enabled = true,
            errorOnInput = false,
            leadingLabel = "Business Name",
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        )
    }
}

@Preview(showBackground = true, name = "Profile row error state")
@Composable
private fun ProfileRowEditTextErrorPreview() {
    GlobalConnectPaymentTheme {
        ProfileRowEditText(
            _text = "Invalid Email",
            profileOptionType = ProfileOptionsType.Email,
            onSaveSetting = { _, _ -> },
            enabled = true,
            errorOnInput = true,
            leadingLabel = "Email",
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        )
    }
}
