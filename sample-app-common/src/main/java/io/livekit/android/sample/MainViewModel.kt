package io.livekit.android.sample

import android.app.Application
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.preference.PreferenceManager
import io.livekit.android.sample.common.BuildConfig

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = PreferenceManager.getDefaultSharedPreferences(application)

    fun getSavedUrl() = preferences.getString(PREFERENCES_KEY_URL, URL) as String
    fun getSavedToken() = preferences.getString(PREFERENCES_KEY_TOKEN, TOKEN) as String
    fun getE2EEOptionsOn() = preferences.getBoolean(PREFERENCES_KEY_E2EE_ON, false)
    fun getSavedE2EEKey() = preferences.getString(PREFERENCES_KEY_E2EE_KEY, E2EE_KEY) as String

    fun setSavedUrl(url: String) {
        preferences.edit {
            putString(PREFERENCES_KEY_URL, url)
        }
    }

    fun setSavedToken(token: String) {
        preferences.edit {
            putString(PREFERENCES_KEY_TOKEN, token)
        }
    }

    fun setSavedE2EEOn(yesno: Boolean) {
        preferences.edit {
            putBoolean(PREFERENCES_KEY_E2EE_ON, yesno)
        }
    }

    fun setSavedE2EEKey(key: String) {
        preferences.edit {
            putString(PREFERENCES_KEY_E2EE_KEY, key)
        }
    }

    fun reset() {
        preferences.edit { clear() }
    }

    companion object {
        private const val PREFERENCES_KEY_URL = "url"
        private const val PREFERENCES_KEY_TOKEN = "token"
        private const val PREFERENCES_KEY_E2EE_ON = "enable_e2ee"
        private const val PREFERENCES_KEY_E2EE_KEY = "e2ee_key"

        const val E2EE_KEY = "12345678"

        const val URL = "wss://web.rentsoft.cn/open_rtc"
        const val TOKEN =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJleHAiOjE3MDEyNDk3NDAsImlzcyI6IkFQSUdQVzNnbkZUenFISCIsIm1ldGFkYXRhIjoie1wiZ3JvdXBJbmZvXCI6e1wiZ3JvdXBJRFwiOlwiXCIsXCJncm91cE5hbWVcIjpcIlwiLFwibm90aWZpY2F0aW9uXCI6XCJcIixcImludHJvZHVjdGlvblwiOlwiXCIsXCJmYWNlVVJMXCI6XCJcIixcIm93bmVyVXNlcklEXCI6XCJcIixcImNyZWF0ZVRpbWVcIjowLFwibWVtYmVyQ291bnRcIjowLFwiZXhcIjpcIlwiLFwic3RhdHVzXCI6MCxcImNyZWF0b3JVc2VySURcIjpcIlwiLFwiZ3JvdXBUeXBlXCI6MCxcIm5lZWRWZXJpZmljYXRpb25cIjowLFwibG9va01lbWJlckluZm9cIjowLFwiYXBwbHlNZW1iZXJGcmllbmRcIjowLFwibm90aWZpY2F0aW9uVXBkYXRlVGltZVwiOjAsXCJub3RpZmljYXRpb25Vc2VySURcIjpcIlwifSxcImdyb3VwTWVtYmVySW5mb1wiOntcImdyb3VwSURcIjpcIlwiLFwidXNlcklEXCI6XCJcIixcInJvbGVMZXZlbFwiOjAsXCJqb2luVGltZVwiOjAsXCJuaWNrbmFtZVwiOlwiXCIsXCJmYWNlVVJMXCI6XCJcIixcImFwcE1hbmdlckxldmVsXCI6MCxcImpvaW5Tb3VyY2VcIjowLFwib3BlcmF0b3JVc2VySURcIjpcIlwiLFwiZXhcIjpcIlwiLFwibXV0ZUVuZFRpbWVcIjowLFwiaW52aXRlclVzZXJJRFwiOlwiXCJ9LFwidXNlckluZm9cIjp7XCJ1c2VySURcIjpcIjE3NjI1NTk0MTdcIixcIm5pY2tuYW1lXCI6XCJCXCIsXCJmYWNlVVJMXCI6XCJodHRwczovL3dlYi5yZW50c29mdC5jbi9hcGlfZW50ZXJwcmlzZS9vYmplY3QvMTc2MjU1OTQxNy9zdG9yYWdlL2VtdWxhdGVkLzAvQW5kcm9pZC9kYXRhL2lvLm9wZW5pbS5hbmRyb2lkLmRlbW8vY2FjaGUvMTcwMDQ3MTg0OTc5MC8xNzAwNDcxODM2MjQwLmpwZ1wiLFwiZXhcIjpcIlwifX0iLCJuYW1lIjoicGFydGljaXBhbnQtbmFtZSIsIm5iZiI6MTcwMTI0NjE0MCwic3ViIjoiMTc2MjU1OTQxNyIsInZpZGVvIjp7ImNhblB1Ymxpc2giOnRydWUsImNhblB1Ymxpc2hEYXRhIjp0cnVlLCJjYW5TdWJzY3JpYmUiOnRydWUsInJvb20iOiIxODcwNDgxMDE4Iiwicm9vbUpvaW4iOnRydWV9fQ.KWWGygW4_5TVptxhGEUH9fxUbnza3f2rMUGpl1YHCZ4"

    }
}
