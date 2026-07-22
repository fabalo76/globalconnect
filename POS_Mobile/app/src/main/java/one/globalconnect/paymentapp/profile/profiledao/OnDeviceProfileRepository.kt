package one.globalconnect.paymentapp.profile.profiledao

import one.globalconnect.paymentapp.profile.Profile

class OnDeviceProfileRepository(private val profileDao: ProfileDao): ProfileRepository {
    override suspend fun insert(vararg profile: Profile) {
        profileDao.insert(*profile)
    }

    override suspend fun delete(vararg profile: Profile) {
        profileDao.delete(*profile)
    }

    override suspend fun update(vararg profile: Profile) {
        profileDao.update(*profile)
    }

    override suspend fun get(): Profile? {
        return profileDao.get()
    }
}