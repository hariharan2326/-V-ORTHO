package com.example.data

import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SupabaseService {
    private val client = OkHttpClient.Builder()
        .build()

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val appointmentListAdapter = moshi.adapter<List<Appointment>>(
        Types.newParameterizedType(List::class.java, Appointment::class.java)
    )

    suspend fun testConnection(url: String, anonKey: String): Boolean = withContext(Dispatchers.IO) {
        if (url.isEmpty() || anonKey.isEmpty()) return@withContext false
        val cleanUrl = url.trim().removeSuffix("/")
        val request = Request.Builder()
            .url("$cleanUrl/rest/v1/appointments?select=id&limit=1")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $anonKey")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                Log.d("SupabaseService", "testConnection code: ${response.code}")
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e("SupabaseService", "testConnection failed", e)
            false
        }
    }

    suspend fun fetchAppointments(url: String, anonKey: String): List<Appointment> = withContext(Dispatchers.IO) {
        if (url.isEmpty() || anonKey.isEmpty()) return@withContext emptyList()
        val cleanUrl = url.trim().removeSuffix("/")
        val request = Request.Builder()
            .url("$cleanUrl/rest/v1/appointments?select=*&order=timestamp.desc")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $anonKey")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    Log.d("SupabaseService", "fetchAppointments success: $body")
                    if (!body.isNullOrEmpty()) {
                        return@withContext appointmentListAdapter.fromJson(body) ?: emptyList()
                    }
                } else {
                    Log.e("SupabaseService", "fetchAppointments error ${response.code}: ${response.body?.string()}")
                }
            }
        } catch (e: Exception) {
            Log.e("SupabaseService", "fetchAppointments failed", e)
        }
        emptyList()
    }

    suspend fun insertAppointment(url: String, anonKey: String, appointment: Appointment): Boolean = withContext(Dispatchers.IO) {
        if (url.isEmpty() || anonKey.isEmpty()) return@withContext false
        val cleanUrl = url.trim().removeSuffix("/")
        
        // Form payload map to avoid sending local Room ID = 0 to Supabase
        val appointmentMap = mutableMapOf<String, Any>(
            "doctorId" to appointment.doctorId,
            "doctorName" to appointment.doctorName,
            "doctorSpecialty" to appointment.doctorSpecialty,
            "clinicName" to appointment.clinicName,
            "consultingFee" to appointment.consultingFee,
            "date" to appointment.date,
            "timeSlot" to appointment.timeSlot,
            "consultationType" to appointment.consultationType,
            "symptoms" to appointment.symptoms,
            "paymentId" to appointment.paymentId,
            "status" to appointment.status,
            "patientName" to appointment.patientName,
            "patientUniqueNo" to appointment.patientUniqueNo,
            "patientAge" to appointment.patientAge,
            "patientSex" to appointment.patientSex,
            "patientMobile" to appointment.patientMobile,
            "patientEmail" to appointment.patientEmail,
            "timestamp" to appointment.timestamp
        )
        if (appointment.id > 0) {
            appointmentMap["id"] = appointment.id
        }

        val mapAdapter = moshi.adapter<Map<String, Any>>(
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        )
        val jsonPayload = mapAdapter.toJson(appointmentMap)

        val body = jsonPayload.toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$cleanUrl/rest/v1/appointments")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $anonKey")
            .header("Prefer", "return=representation")
            .post(body)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                Log.d("SupabaseService", "insertAppointment code: ${response.code}")
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e("SupabaseService", "insertAppointment failed", e)
            false
        }
    }

    suspend fun deleteAppointment(url: String, anonKey: String, paymentId: String, doctorId: Int, timestamp: Long): Boolean = withContext(Dispatchers.IO) {
        if (url.isEmpty() || anonKey.isEmpty()) return@withContext false
        val cleanUrl = url.trim().removeSuffix("/")
        
        // Remove matching appointments by paymentId (for paid appointments) or doctorId/timestamp combination
        val filter = if (paymentId.isNotEmpty()) {
            "paymentId=eq.$paymentId"
        } else {
            "and=(doctorId.eq.$doctorId,timestamp.eq.$timestamp)"
        }
        
        val request = Request.Builder()
            .url("$cleanUrl/rest/v1/appointments?$filter")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $anonKey")
            .delete()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                Log.d("SupabaseService", "deleteAppointment code: ${response.code}")
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e("SupabaseService", "deleteAppointment failed", e)
            false
        }
    }

    suspend fun updateAppointmentStatus(url: String, anonKey: String, paymentId: String, doctorId: Int, timestamp: Long, newStatus: String): Boolean = withContext(Dispatchers.IO) {
        if (url.isEmpty() || anonKey.isEmpty()) return@withContext false
        val cleanUrl = url.trim().removeSuffix("/")
        
        val filter = if (paymentId.isNotEmpty()) {
            "paymentId=eq.$paymentId"
        } else {
            "and=(doctorId.eq.$doctorId,timestamp.eq.$timestamp)"
        }
        
        val jsonPayload = "{\"status\": \"$newStatus\"}"
        val body = jsonPayload.toRequestBody(jsonMediaType)
        
        val request = Request.Builder()
            .url("$cleanUrl/rest/v1/appointments?$filter")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $anonKey")
            .header("Content-Type", "application/json")
            .patch(body)
            .build()
            
        try {
            client.newCall(request).execute().use { response ->
                Log.d("SupabaseService", "updateAppointmentStatus code: ${response.code}")
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e("SupabaseService", "updateAppointmentStatus failed", e)
            false
        }
    }
}
