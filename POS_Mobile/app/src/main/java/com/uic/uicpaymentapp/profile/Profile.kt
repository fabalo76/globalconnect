package com.uic.uicpaymentapp.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uic.uicpaymentapp.AppViewModelProvider
import com.uic.uicpaymentapp.R
import com.uic.uicpaymentapp.navigation.TopBar
import com.uic.uicpaymentapp.records.BackButton
import com.uic.uicpaymentapp.ui.theme.color_grey95
import com.uic.uicpaymentapp.ui.theme.color_secondaryThree
import com.uic.uicpaymentapp.ui.theme.color_white

const val profileRowHeight = 60

@Composable
fun ProfileScreen(
    onBackPressed: () -> Unit,
) {
    val context = LocalContext.current

    val viewModelFactory = remember { AppViewModelProvider.provideFactory(context) }

    val viewModel: ProfileViewModel = viewModel(factory = viewModelFactory)

    var editEnabled by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    Scaffold(
        topBar = {
            TopBar(title = {
                Text(
                    fontSize = 16.sp,
                    text = stringResource(id = R.string.profile),
                    fontWeight = FontWeight.SemiBold
                )
            },
                actions = {
                    if (!editEnabled) {
                        TextButton(
                            modifier = Modifier.padding(16.dp),
                            onClick = { editEnabled = true }) {
                            Text(text = stringResource(id = R.string.edit), fontSize = 16.sp)
                        }
                    } else {
                        TextButton(modifier = Modifier.padding(16.dp), onClick = {
                            editEnabled = false
                            viewModel.saveProfile()
                        }
                        ) {
                            Text(text = stringResource(id = R.string.save), fontSize = 16.sp)
                        }
                    }
                },
                navigationIcon = {
                    BackButton(
                        onBackPressed = onBackPressed,
                        text = stringResource(id = R.string.setting)
                    )
                })
        }
    ) { contentPadding ->
        Column(
            Modifier
                .fillMaxSize(1f)
                .padding(contentPadding)
                .background(color_secondaryThree)
        ) {
            Column(
                Modifier
                    .padding(8.dp,16.dp)
                    .background(color_white, shape = RoundedCornerShape(6.dp))
                    .fillMaxSize()
            ) {
                val options by viewModel.profileUiState.collectAsState(ProfileUiState())
                LaunchedEffect(editEnabled) {
                    if (editEnabled) {
                        focusRequester.requestFocus()
                    } else {
                        focusRequester.freeFocus()
                    }
                }
                Column(
                    Modifier
                        .fillMaxWidth(1f)
                        .padding(16.dp)
                        .background(
                            color = color_grey95,
                            shape = RoundedCornerShape(10.dp)
                        )
                ) {
                    ProfileRowEditText(
                        _text = options.businessName.text,
                        profileOptionType = ProfileOptionsType.Name,
                        onSaveSetting = { type, s -> viewModel.updateProfileOption(type, s) },
                        enabled = editEnabled,
                        errorOnInput = false,
                        modifier = Modifier
                            .fillMaxWidth(1f)
                            .height(profileRowHeight.dp)
                            .focusRequester(focusRequester))

                    RowDivider(
                        Modifier
                            .fillMaxWidth(1f)
                            .padding(start = 4.dp))

                    ProfileRowEditText(
                        _text = options.email.text,
                        profileOptionType = ProfileOptionsType.Email,
                        onSaveSetting = { type, s -> viewModel.updateProfileOption(type, s) },
                        enabled = editEnabled,
                        errorOnInput = false,
                        modifier = Modifier
                            .fillMaxWidth(1f)
                            .height(profileRowHeight.dp)
                    )
                    RowDivider(
                        Modifier
                            .fillMaxWidth(1f)
                            .padding(start = 4.dp))

                    ProfileRowEditText(
                        _text = options.phoneNumber.text,
                        profileOptionType = ProfileOptionsType.PhoneNumber,
                        onSaveSetting = { type, s -> viewModel.updateProfileOption(type, s) },
                        enabled = editEnabled,
                        errorOnInput = false,
                        modifier = Modifier
                            .fillMaxWidth(1f)
                            .height(profileRowHeight.dp)

                    )
                    RowDivider(
                        Modifier
                            .fillMaxWidth(1f)
                            .padding(start = 4.dp))

                    ProfileRowEditText(
                        _text = options.streetAddress.text,
                        profileOptionType = ProfileOptionsType.Address,
                        onSaveSetting = { type, s -> viewModel.updateProfileOption(type, s) },
                        enabled = editEnabled,
                        errorOnInput = false,
                        modifier = Modifier
                            .fillMaxWidth(1f)
                            .height(profileRowHeight.dp)
                    )
                    RowDivider(
                        Modifier
                            .fillMaxWidth(1f)
                            .padding(start = 4.dp))

                    ProfileRowEditText(
                        _text = options.cityState.text,
                        profileOptionType = ProfileOptionsType.CityState,
                        onSaveSetting = { type, s -> viewModel.updateProfileOption(type, s) },
                        enabled = editEnabled,
                        errorOnInput = false,
                        modifier = Modifier
                            .fillMaxWidth(1f)
                            .height(profileRowHeight.dp)
                    )
                    RowDivider(
                        Modifier
                            .fillMaxWidth(1f)
                            .padding(start = 4.dp))


                    ProfileRowEditText(
                        _text = options.zipCode.text,
                        profileOptionType = ProfileOptionsType.ZipCode,
                        onSaveSetting = { type, s -> viewModel.updateProfileOption(type, s) },
                        enabled = editEnabled,
                        errorOnInput = false,
                        modifier = Modifier
                            .fillMaxWidth(1f)
                            .height(profileRowHeight.dp)
                    )
                }
            }
        }
    }
}
