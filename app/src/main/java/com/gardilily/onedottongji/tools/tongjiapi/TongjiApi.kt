// SPDX-License-Identifier: MulanPSL-2.0

package com.gardilily.onedottongji.tools.tongjiapi

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.media.session.MediaSession.Token
import android.util.Log
import androidx.appcompat.app.AlertDialog
import com.gardilily.onedottongji.activity.Login
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.IOException
import org.json.JSONArray
import org.json.JSONObject

class TongjiApi {

    companion object {

        const val CLIENT_ID = "authorization-xxb-onedottongji-yuchen"
        const val BASE_URL = "https://api.tongji.edu.cn"

        const val OAUTH_REDIRECT_URL = "onetj://fakeredir.gardilily.com"

        const val CODE2TOKEN_URL = "$BASE_URL/v1/token"

        val SCOPE_LIST = listOf(
            "dc_user_student_info",
            "rt_onetongji_cet_score",
            "rt_onetongji_school_calendar_current_term_calendar",
            "rt_onetongji_undergraduate_score",
            "rt_teaching_info_undergraduate_summarized_grades", // 暂未使用
            "rt_onetongji_student_timetable",
            "rt_onetongji_student_exams"
        )

        private var _instance: TongjiApi? = null

        val instance: TongjiApi
            get() {
                if (_instance == null) {
                    _instance = TongjiApi()
                }

                return _instance!!
            }



        const val SHARED_PREFERENCES_NAME = "onetj.tjapi"
        const val SP_KEY_TOKEN_DATA = "tkdata"
        const val SP_KEY_SWITCH_ACCOUNT_REQUIRED = "swacc"

    }

    private val client = OkHttpClient()
    private lateinit var sp : SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    data class TokenData(
        var token: String,
        var expireTimeSec: Long
    ) {
        override fun toString(): String {
            return "$token|$expireTimeSec"
        }
    }

    fun tokenAvailable(): Boolean {
        val tokenData = getTokenData()
        return tokenData.expireTimeSec > System.currentTimeMillis() / 1000 + 10
    }

    fun clearCache() {
        sp.edit().remove(SP_KEY_TOKEN_DATA).apply()
    }

    var switchAccountRequired: Boolean
        get() {
            return sp.getBoolean(SP_KEY_SWITCH_ACCOUNT_REQUIRED, false)
        }
        set(value) {
            sp.edit().putBoolean(SP_KEY_SWITCH_ACCOUNT_REQUIRED, value).apply()
        }

    private fun storeTokenData(data: TokenData) {
        sp.edit().putString(SP_KEY_TOKEN_DATA, data.toString()).apply()
    }

    private fun SharedPreferences.getStringOrNull(key: String): String? {
        return if (this.contains(key))
            this.getString(key, null)
        else null
    }

    private fun getTokenData(): TokenData {
        val datas = (sp.getStringOrNull(SP_KEY_TOKEN_DATA) ?: "0|0").split("|")
        return TokenData(token = datas[0], expireTimeSec = datas[1].toLong())
    }

    fun Request.Builder.addAuthorization(): Request.Builder {
        val token = getTokenData().token
        return this.addHeader("Authorization", "Bearer ${getTokenData().token}")
    }

    fun code2token(code: String): Boolean {
        val jsonType = "application/json".toMediaType()
        val json = JSONObject()

        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", CLIENT_ID)
            .add("code", code)
            .add("redirect_uri", OAUTH_REDIRECT_URL)
            .build()

        val request = Request.Builder()
            .url(CODE2TOKEN_URL)
            .post(body)
            .build()

        val response = client.newCall(request).execute()

        val bodyString = response.body?.string()
        val responseJson = bodyString?.let { JSONObject(it) } ?: return false

        val accessToken = responseJson.getString("access_token")
        // 有效期。单位为秒（应该是吧）
        val expiresIn = responseJson.getLong("expires_in")
        val currentTime = System.currentTimeMillis() / 1000
        val expireTime = currentTime + expiresIn - 10

        storeTokenData(TokenData(token = accessToken, expireTimeSec = expireTime))

        return true
    }

    data class StudentInfo(
        var userId: String? = null,
        var name: String? = null,
        var gender: Gender? = null,
        var deptName: String? = null,
        var secondDeptName: String? = null,
        var schoolName: String? = null,
        var currentGrade: String? = null
    ) {
        enum class Gender(val code: Int) {
            UNKNOWN(0),
            MALE(1),
            FEMALE(2),
            UNTOLD(9)

            ;

            companion object {
                fun make(code: Int): Gender {
                    return when (code) {
                        UNKNOWN.code -> UNKNOWN
                        MALE.code -> MALE
                        FEMALE.code -> FEMALE
                        UNTOLD.code -> UNTOLD
                        else -> UNKNOWN
                    }
                }
            }

        }
    }

    private fun basicRequestBuilder(url: String): Request.Builder {
        return Request.Builder().addAuthorization().url(url)
    }

    private val netErrorDialogOnSemaphore = Semaphore(1, 0)

    private fun<T> Request.Builder.execute(activity: Activity): T? {
        return this.build().execute<T>(activity)
    }

    private fun<T> Request.execute(activity: Activity): T? {
        val response = try {
            client.newCall(this).execute()
        } catch (e: IOException) {

            val semaphoreAcquired = runBlocking {
                netErrorDialogOnSemaphore.tryAcquire()
            }

            if (semaphoreAcquired) {
                val msg = "请检查网络连接，然后重新打开此页面。\n\n" +
                        "详细信息：\n" + e.message

                activity.runOnUiThread {
                    AlertDialog.Builder(activity)
                        .setTitle("网络错误")
                        .setMessage(msg)
                        .setPositiveButton("好") { _, _ -> }
                        .setOnDismissListener {
                            netErrorDialogOnSemaphore.release()
                        }
                        .show()
                }

            }

            return null

        }

        return response.checkErrorAndGetData<T>(activity)
    }

    /**
     *
     * @return JSONObject or JSONArray
     */
    private fun<T> Response?.checkErrorAndGetData(activity: Activity): T? {

        fun solveError(msg: String = "无") {

            clearCache()

            val msgBuilder = StringBuilder()
            msgBuilder.append("请重新登录。\n\n")
                .append("错误信息: \n")
                .append(msg)

            activity.runOnUiThread {
                AlertDialog.Builder(activity)
                    .setTitle("登录状态异常")
                    .setMessage(msgBuilder)
                    .setPositiveButton("OK") { _, _ ->
                        activity.startActivity(Intent(activity, Login::class.java))
                        activity.finish()
                    }
                    .setCancelable(false)
                    .show()
            }
        }


        if (this?.body == null) {
            solveError()
            return null
        }

        val json = try {
            val bodyString = this.body!!.string()
            JSONObject(bodyString)
        } catch (_: Exception) {
            solveError("json 解析错误。${this.code}")
            return null
        }

        if (json.getString("code") != "A00000") {
            solveError()
            return null
        }

        return json.get("data") as T

    }

    fun getStudentInfo(activity: Activity): StudentInfo? {
        val url = "$BASE_URL/v1/dc/user/student_info"

        val request = basicRequestBuilder(url)
            .get()
            .build()

        val data = request.execute<JSONArray>(activity)?.getJSONObject(0) ?: return null

        return StudentInfo(
            userId = data.getString("userId"),
            name = data.getString("name"),
            deptName = data.getString("deptName"),
            secondDeptName = data.getString("secondDeptName"),
            currentGrade = data.getString("currentGrade"),
            gender = StudentInfo.Gender.make(data.getString("sexCode").toInt())
        )
    }

    data class SchoolCalendar(
        var calendarId: String? = null,
        var year: String? = null,
        var term: String? = null,
        var schoolWeek: String? = null,
        var simpleName: String? = null
    )

    fun getOneTongjiSchoolCalendar(activity: Activity): SchoolCalendar? {
        val url = "$BASE_URL/v1/rt/onetongji/school_calendar_current_term_calendar"
        val request = basicRequestBuilder(url)
            .get()
            .build()

        val data = request.execute<JSONObject>(activity) ?: return null

        val schoolCalendar = data.getJSONObject("schoolCalendar")

        return SchoolCalendar(
            calendarId = schoolCalendar.getString("id"),
            simpleName = data.getString("simpleName"),
            schoolWeek = data.getString("week")
        )
    }



    fun getOneTongjiUndergraduateScore(activity: Activity): JSONObject? {
        val url = "$BASE_URL/v1/rt/onetongji/undergraduate_score"

        return basicRequestBuilder("$url?calendarId=-1")
            .get()
            .build()
            .execute<JSONObject>(activity)

    }

    fun getOneTongjiStudentTimetable(activity: Activity): JSONArray? {
        val url = "$BASE_URL/v1/rt/onetongji/student_timetable"
        return basicRequestBuilder(url)
            .get()
            .execute<JSONArray>(activity)
    }


    fun getOneTongjiStudentExams(activity: Activity): JSONObject? {
        val url = "$BASE_URL/v1/rt/onetongji/student_exams"
        return basicRequestBuilder(url)
            .get()
            .execute(activity)
    }

}

