package com.uic.uicpaymentapp.profile.profiledao

import com.uic.uicpaymentapp.profile.Profile

interface ProfileRepository {
    suspend fun insert(vararg profile: Profile)

    suspend fun delete(vararg profile: Profile)

    suspend fun update(vararg profile: Profile)

    suspend fun get(): Profile?
}