package one.globalconnect.paymentapp.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Entity
import androidx.room.PrimaryKey
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import one.globalconnect.paymentapp.profile.profiledao.ProfileRepository
import kotlinx.coroutines.launch
import one.globalconnect.paymentapp.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow

class ProfileViewModel(
    private val profileRepository: ProfileRepository,
) : ViewModel() {
    /**
     * Holds current item ui state
     */

    private var _profileUiState = MutableStateFlow(ProfileUiState())
    var profileUiState: StateFlow<ProfileUiState> = _profileUiState
    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val profileSnapshot = profileRepository.get() ?: Profile()
                _profileUiState.value = profileSnapshot.toProfileOptionsList()
            }
        }
    }

    fun saveProfile() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val profileSnapshot = profileRepository.get()
                if (profileSnapshot == null) {
                    val profile = Profile().insertProfileOptionsList(_profileUiState.value)
                    profileRepository.insert(profile)
                } else {
                    profileSnapshot.insertProfileOptionsList(_profileUiState.value)
                    profileRepository.update(profileSnapshot)
                }
            }
        }
    }

    fun updateProfileOption(type: ProfileOptionsType, newValue: String) {
        _profileUiState.value = when (type) {
            ProfileOptionsType.Name -> _profileUiState.value.copy(
                businessName = _profileUiState.value.businessName.copy(
                    text = newValue
                )
            )

            ProfileOptionsType.Email -> _profileUiState.value.copy(
                email = _profileUiState.value.email.copy(
                    text = newValue
                )
            )

            ProfileOptionsType.PhoneNumber -> _profileUiState.value.copy(
                phoneNumber = _profileUiState.value.phoneNumber.copy(
                    text = newValue
                )
            )

            ProfileOptionsType.Address -> _profileUiState.value.copy(
                streetAddress = _profileUiState.value.streetAddress.copy(
                    text = newValue
                )
            )

            ProfileOptionsType.CityState -> _profileUiState.value.copy(
                cityState = _profileUiState.value.cityState.copy(
                    text = newValue
                )
            )

            ProfileOptionsType.ZipCode -> _profileUiState.value.copy(
                zipCode = _profileUiState.value.zipCode.copy(
                    text = newValue
                )
            )
        }
    }
}

data class ProfileOption(var text: String, val label: String, val type: ProfileOptionsType)

data class ProfileUiState(
    var businessName: ProfileOption = ProfileOption(
        text = "",
        label = GlobalConnectPaymentApplication.instance.resources.getString(R.string.profile_business_name),
        type = ProfileOptionsType.Name
    ),
    var email: ProfileOption = ProfileOption(
        text = "",
        label = GlobalConnectPaymentApplication.instance.resources.getString(R.string.profile_email),
        type = ProfileOptionsType.Email
    ),
    var phoneNumber: ProfileOption = ProfileOption(
        text = "",
        label = GlobalConnectPaymentApplication.instance.resources.getString(R.string.profile_phone_number),
        type = ProfileOptionsType.PhoneNumber
    ),
    var streetAddress: ProfileOption = ProfileOption(
        text = "",
        label = GlobalConnectPaymentApplication.instance.resources.getString(R.string.profile_street_address),
        type = ProfileOptionsType.Address
    ),
    var cityState: ProfileOption = ProfileOption(
        text = "",
        label = GlobalConnectPaymentApplication.instance.resources.getString(R.string.profile_city_state),
        type = ProfileOptionsType.CityState
    ),
    var zipCode: ProfileOption = ProfileOption(
        text = "",
        label = GlobalConnectPaymentApplication.instance.resources.getString(R.string.profile_zip_code),
        type = ProfileOptionsType.ZipCode
    )
)

enum class ProfileOptionsType {
    Name,
    Email,
    PhoneNumber,
    Address,
    CityState,
    ZipCode;

    fun toUserLabel(): String {
        return when (this) {
            Name -> GlobalConnectPaymentApplication.instance.resources.getString(R.string.profile_business_name)
            Email -> GlobalConnectPaymentApplication.instance.resources.getString(R.string.profile_email)
            PhoneNumber -> GlobalConnectPaymentApplication.instance.resources.getString(R.string.profile_phone_number)
            Address -> GlobalConnectPaymentApplication.instance.resources.getString(R.string.profile_street_address)
            CityState -> GlobalConnectPaymentApplication.instance.resources.getString(R.string.profile_city_state)
            ZipCode -> GlobalConnectPaymentApplication.instance.resources.getString(R.string.profile_zip_code)
        }
    }
}


@Entity
data class Profile(
    var businessName: String = "",
    var email: String = "",
    var phoneNumber: String = "",
    var streetAddress: String = "",
    var cityState: String = "",
    var zipCode: String = "",
) {
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0
}

fun Profile.toProfileOptionsList(): ProfileUiState {
    val profileUiState = ProfileUiState()
    profileUiState.businessName.text = businessName
    profileUiState.email.text = email
    profileUiState.phoneNumber.text = phoneNumber
    profileUiState.streetAddress.text = streetAddress
    profileUiState.cityState.text = cityState
    profileUiState.zipCode.text = zipCode
    return profileUiState
}

fun Profile.insertProfileOptionsList(profileUiState: ProfileUiState): Profile {
    businessName = profileUiState.businessName.text
    email = profileUiState.email.text
    phoneNumber = profileUiState.phoneNumber.text
    streetAddress = profileUiState.streetAddress.text
    cityState = profileUiState.cityState.text
    zipCode = profileUiState.zipCode.text
    return this
}
