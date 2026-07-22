package one.globalconnect.paymentapp.profile.profiledao

import one.globalconnect.paymentapp.profile.Profile

interface ProfileRepository {
    suspend fun insert(vararg profile: Profile)

    suspend fun delete(vararg profile: Profile)

    suspend fun update(vararg profile: Profile)

    suspend fun get(): Profile?
}