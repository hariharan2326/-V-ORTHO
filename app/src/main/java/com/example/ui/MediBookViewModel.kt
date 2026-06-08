package com.example.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.Appointment
import com.example.data.Patient
import com.example.data.AppointmentRepository
import com.example.data.SupabaseService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class Doctor(
    val id: Int,
    val name: String,
    val degree: String,
    val specialty: String,
    val clinic: String,
    val fee: String,
    val rating: Double,
    val reviewsCount: Int,
    val availableSlots: List<String>,
    val bio: String,
    val avatarColor: Long
)

data class PaymentDetails(
    val doctorName: String,
    val feeAmount: String,
    val email: String,
    val contact: String,
    val paymentMethod: String = "any"
)

data class CustomToast(
    val id: Long = System.currentTimeMillis() + (0..100000).random(),
    val title: String,
    val message: String,
    val isRegistration: Boolean = false
)

class MediBookViewModel(
    private val repository: AppointmentRepository,
    private val context: Context
) : ViewModel() {

    private val _notifications = MutableStateFlow<List<CustomToast>>(emptyList())
    val notifications: StateFlow<List<CustomToast>> = _notifications.asStateFlow()

    fun triggerToast(title: String, message: String, isRegistration: Boolean = false) {
        val newToast = CustomToast(title = title, message = message, isRegistration = isRegistration)
        _notifications.value = _notifications.value + newToast
        
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(context, "$title\n$message", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        
        viewModelScope.launch {
            kotlinx.coroutines.delay(5000)
            _notifications.value = _notifications.value.filter { it.id != newToast.id }
        }
    }

    fun dismissNotification(toastId: Long) {
        _notifications.value = _notifications.value.filter { it.id != toastId }
    }

    private val sharedPrefs = context.getSharedPreferences("medibook_supabase_prefs", Context.MODE_PRIVATE)
    private val supabaseService = SupabaseService()

    private val _supabaseUrl = MutableStateFlow(
        sharedPrefs.getString("supabase_url", "").orEmpty().ifEmpty {
            val configUrl = try { com.example.BuildConfig.SUPABASE_URL } catch (e: Throwable) { "" }
            if (configUrl.isNotEmpty() && configUrl != "https://your-project.supabase.co") {
                configUrl
            } else {
                "https://howrghnrbalwcvysvzct.supabase.co"
            }
        }
    )
    val supabaseUrl: StateFlow<String> = _supabaseUrl.asStateFlow()

    private val _supabaseAnonKey = MutableStateFlow(
        sharedPrefs.getString("supabase_anon_key", "").orEmpty().ifEmpty {
            val configKey = try { com.example.BuildConfig.SUPABASE_ANON_KEY } catch (e: Throwable) { "" }
            if (configKey.isNotEmpty() && configKey != "YOUR_ANON_KEY") {
                configKey
            } else {
                ""
            }
        }
    )
    val supabaseAnonKey: StateFlow<String> = _supabaseAnonKey.asStateFlow()

    private val _useSupabase = MutableStateFlow(
        if (sharedPrefs.contains("use_supabase")) {
            sharedPrefs.getBoolean("use_supabase", false)
        } else {
            _supabaseUrl.value.isNotEmpty() && _supabaseAnonKey.value.isNotEmpty() && _supabaseAnonKey.value != "YOUR_ANON_KEY"
        }
    )
    val useSupabase: StateFlow<Boolean> = _useSupabase.asStateFlow()

    private val _supabaseSyncStatus = MutableStateFlow("Idle")
    val supabaseSyncStatus: StateFlow<String> = _supabaseSyncStatus.asStateFlow()

    init {
        // Start periodic real-time sync when enabled
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(8000) // Poll every 8 seconds for responsive real-time updates
                if (_useSupabase.value) {
                    fetchRealtimeSilently()
                }
            }
        }

        if (_useSupabase.value && _supabaseUrl.value.isNotEmpty() && _supabaseAnonKey.value.isNotEmpty() && _supabaseAnonKey.value != "YOUR_ANON_KEY") {
            triggerSync()
        }
    }

    private suspend fun fetchRealtimeSilently() {
        val url = _supabaseUrl.value
        val key = _supabaseAnonKey.value
        if (url.isEmpty() || key.isEmpty() || key == "YOUR_ANON_KEY") return
        try {
            val remoteList = supabaseService.fetchAppointments(url, key)
            val localList = repository.getAppointmentsDirect()
            
            // Check if lists match in size and items (by doctorId and timestamp)
            val isMatch = remoteList.size == localList.size && remoteList.all { remote ->
                localList.any { it.timestamp == remote.timestamp && it.doctorId == remote.doctorId }
            }
            if (!isMatch) {
                // Upload any local bookings that were successfully generated but not synced yet
                var localOnlyCount = 0
                for (local in localList) {
                    val existsRemote = remoteList.any {
                        (it.paymentId.isNotEmpty() && it.paymentId == local.paymentId) ||
                        (it.timestamp == local.timestamp && it.doctorId == local.doctorId)
                    }
                    if (!existsRemote) {
                        val inserted = supabaseService.insertAppointment(url, key, local)
                        if (inserted) localOnlyCount++
                    }
                }
                
                val finalRemoteList = if (localOnlyCount > 0) {
                    supabaseService.fetchAppointments(url, key)
                } else {
                    remoteList
                }
                
                // Trigger toast for new remote appointments if any exist
                for (remote in finalRemoteList) {
                    val existsLocally = localList.any { it.timestamp == remote.timestamp && it.doctorId == remote.doctorId }
                    if (!existsLocally) {
                        triggerToast(
                            title = "New Appointment Booked",
                            message = "Active booking registered for ${remote.doctorName} on ${remote.date} at ${remote.timeSlot}.",
                            isRegistration = false
                        )
                    }
                }

                // Clear and re-populate local Room database to maintain single source of truth
                repository.clear()
                for (remote in finalRemoteList) {
                    repository.insert(remote.copy(id = 0))
                }
                _supabaseSyncStatus.value = "Synced in real-time"
            }
        } catch (e: Exception) {
            android.util.Log.e("MediBookViewModel", "Real-time sync failed silently", e)
        }
    }

    fun saveSupabaseConfig(url: String, key: String, enabled: Boolean) {
        sharedPrefs.edit()
            .putString("supabase_url", url)
            .putString("supabase_anon_key", key)
            .putBoolean("use_supabase", enabled)
            .apply()

        _supabaseUrl.value = url
        _supabaseAnonKey.value = key
        _useSupabase.value = enabled

        if (enabled && url.isNotEmpty() && key.isNotEmpty() && key != "YOUR_ANON_KEY") {
            triggerSync()
        } else {
            _supabaseSyncStatus.value = if (enabled) "Config updated. Awaiting sync." else "Supabase DB Disabled"
        }
    }

    fun testSupabaseConnection(url: String, key: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val result = supabaseService.testConnection(url, key)
            onResult(result)
        }
    }

    fun triggerSync() {
        val url = _supabaseUrl.value
        val key = _supabaseAnonKey.value
        if (url.isEmpty() || key.isEmpty() || key == "YOUR_ANON_KEY") {
            _supabaseSyncStatus.value = "Error: Configure Url/Key first"
            return
        }
        viewModelScope.launch {
            _supabaseSyncStatus.value = "Syncing..."
            try {
                // 1. Fetch remote appointments from Supabase
                val remoteList = supabaseService.fetchAppointments(url, key)
                
                // 2. Fetch local appointments from Room
                val localList = repository.getAppointmentsDirect()
                
                var uploaded = 0

                // 3. For any local appointment NOT on remote, upload it to Supabase
                for (local in localList) {
                    val existsRemote = remoteList.any { 
                        (it.paymentId.isNotEmpty() && it.paymentId == local.paymentId) || 
                        (it.timestamp == local.timestamp && it.doctorId == local.doctorId)
                    }
                    if (!existsRemote) {
                        val inserted = supabaseService.insertAppointment(url, key, local)
                        if (inserted) uploaded++
                    }
                }
                
                // 4. Fetch the remote list again in case lists were updated
                val updatedRemoteList = supabaseService.fetchAppointments(url, key)
                
                // 5. Overwrite local Room db with exact remote list to match deletions and insertions cleanly
                repository.clear()
                for (remote in updatedRemoteList) {
                    repository.insert(remote.copy(id = 0))
                }
                
                _supabaseSyncStatus.value = "Success: Connected. Synced ${updatedRemoteList.size} bookings."
            } catch (e: Exception) {
                _supabaseSyncStatus.value = "Sync failed: ${e.localizedMessage}"
            }
        }
    }

    val standardSlots = listOf(
        "05:00 PM", "05:15 PM", "05:30 PM", "05:45 PM",
        "06:00 PM", "06:15 PM", "06:30 PM", "06:45 PM",
        "07:00 PM", "07:15 PM", "07:30 PM", "07:45 PM",
        "08:00 PM", "08:15 PM", "08:30 PM", "08:45 PM"
    )

    // Master list of specialists matching the actual MediBook directory
    val doctorsList = listOf(
        Doctor(
            id = 1,
            name = "DR. M. VENKATESH",
            degree = "M.S. (ORTHO), DNB (ORTHO), MNAMS",
            specialty = "Orthopaedic",
            clinic = "V ORTHO CLINIC",
            fee = "₹50",
            rating = 4.9,
            reviewsCount = 312,
            availableSlots = standardSlots,
            bio = "CONSULTANT ORTHOPAEDIC SURGEON. Specialist in orthopaedic surgery, sports medicine, joint replacements, and bone health with over 15 years of trusted experience.",
            avatarColor = 0xFF0D9488
        ),
        Doctor(
            id = 2,
            name = "DR. M.P.S. RAJESREE",
            degree = "M.B.,B.S., D.C.H.",
            specialty = "Paediatrics",
            clinic = "KAVIESH CHILD CARE",
            fee = "₹50",
            rating = 4.9,
            reviewsCount = 286,
            availableSlots = standardSlots,
            bio = "CONSULTANT PAEDIATRICIAN. Compassionate paediatrician specializing in newborn care, childhood growth development, nutritional guidance, and immunizations.",
            avatarColor = 0xFF0EA5E9
        )
    )

    // Observes scheduled appointments reactively
    val appointments: StateFlow<List<Appointment>> = repository.allAppointments
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Current navigation state
    private val _currentTab = MutableStateFlow("Home")
    val currentTab: StateFlow<String> = _currentTab.asStateFlow()

    // Filters for search and specialty
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedSpecialtyFilter = MutableStateFlow<String?>(null)
    val selectedSpecialtyFilter: StateFlow<String?> = _selectedSpecialtyFilter.asStateFlow()

    // User Profile settings and variables
    private val _profileName = MutableStateFlow(sharedPrefs.getString("profile_name", "Alex Kim") ?: "Alex Kim")
    val profileName: StateFlow<String> = _profileName.asStateFlow()

    private val _profileEmail = MutableStateFlow(sharedPrefs.getString("profile_email", "alex.kim@email.com") ?: "alex.kim@email.com")
    val profileEmail: StateFlow<String> = _profileEmail.asStateFlow()

    private val _profileAge = MutableStateFlow(sharedPrefs.getString("profile_age", "25") ?: "25")
    val profileAge: StateFlow<String> = _profileAge.asStateFlow()

    private val _profileSex = MutableStateFlow(sharedPrefs.getString("profile_sex", "Male") ?: "Male")
    val profileSex: StateFlow<String> = _profileSex.asStateFlow()

    private val _profileMobile = MutableStateFlow(sharedPrefs.getString("profile_mobile", "9876543210") ?: "9876543210")
    val profileMobile: StateFlow<String> = _profileMobile.asStateFlow()

    private val _profileUniqueNo = MutableStateFlow(sharedPrefs.getString("profile_unique_no", "") ?: "")
    val profileUniqueNo: StateFlow<String> = _profileUniqueNo.asStateFlow()

    val allPatients: StateFlow<List<Patient>> = repository.allPatients
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Login roles & doctor identification
    private val _userRole = MutableStateFlow(sharedPrefs.getString("user_role", "NONE") ?: "NONE")
    val userRole: StateFlow<String> = _userRole.asStateFlow()

    private val _loggedInDoctorId = MutableStateFlow<Int?>(
        if (sharedPrefs.contains("logged_in_doctor_id")) {
            sharedPrefs.getInt("logged_in_doctor_id", -1).let { if (it == -1) null else it }
        } else null
    )
    val loggedInDoctorId: StateFlow<Int?> = _loggedInDoctorId.asStateFlow()

    // Active Booking Details Dialog/Screen State
    private val _bookingDoctor = MutableStateFlow<Doctor?>(null)
    val bookingDoctor: StateFlow<Doctor?> = _bookingDoctor.asStateFlow()

    private val _selectedDate = MutableStateFlow("Tue, Jun 2")
    val selectedDate: StateFlow<String> = _selectedDate.asStateFlow()

    private val _selectedTimeSlot = MutableStateFlow("05:00 PM")
    val selectedTimeSlot: StateFlow<String> = _selectedTimeSlot.asStateFlow()

    private val _consultationType = MutableStateFlow("In-Clinic Visit")
    val consultationType: StateFlow<String> = _consultationType.asStateFlow()

    private val _patientSymptoms = MutableStateFlow("")
    val patientSymptoms: StateFlow<String> = _patientSymptoms.asStateFlow()

    private val _bookingSuccess = MutableStateFlow(false)
    val bookingSuccess: StateFlow<Boolean> = _bookingSuccess.asStateFlow()

    // Razorpay State variables
    private val _paymentError = MutableStateFlow<String?>(null)
    val paymentError: StateFlow<String?> = _paymentError.asStateFlow()

    private val _customKeyId = MutableStateFlow("rzp_test_SwfZyxNgHor83f")
    val customKeyId: StateFlow<String> = _customKeyId.asStateFlow()

    private val _selectedPaymentMethod = MutableStateFlow("any") // "any", "upi", "card"
    val selectedPaymentMethod: StateFlow<String> = _selectedPaymentMethod.asStateFlow()

    private val _launchPaymentEvent = MutableStateFlow<PaymentDetails?>(null)
    val launchPaymentEvent: StateFlow<PaymentDetails?> = _launchPaymentEvent.asStateFlow()

    private val _paymentSuccessId = MutableStateFlow<String>("")
    val paymentSuccessId: StateFlow<String> = _paymentSuccessId.asStateFlow()

    // Phone & OTP verification states for booking
    private val _bookingPhone = MutableStateFlow("9876543210")
    val bookingPhone: StateFlow<String> = _bookingPhone.asStateFlow()

    private val _otpSent = MutableStateFlow(false)
    val otpSent: StateFlow<Boolean> = _otpSent.asStateFlow()

    private val _otpCode = MutableStateFlow("")
    val otpCode: StateFlow<String> = _otpCode.asStateFlow()

    private val _otpVerificationError = MutableStateFlow<String?>(null)
    val otpVerificationError: StateFlow<String?> = _otpVerificationError.asStateFlow()

    private val _isPhoneVerified = MutableStateFlow(false)
    val isPhoneVerified: StateFlow<Boolean> = _isPhoneVerified.asStateFlow()

    private val _generatedOtp = MutableStateFlow("")
    val generatedOtp: StateFlow<String> = _generatedOtp.asStateFlow()

    fun setBookingPhone(phone: String) {
        _bookingPhone.value = phone
        // If they change phone number, we reset verification state
        _isPhoneVerified.value = false
        _otpSent.value = false
        _otpCode.value = ""
        _otpVerificationError.value = null
    }

    fun setOtpCode(code: String) {
        _otpCode.value = code
    }

    fun sendOtp() {
        val phoneNum = _bookingPhone.value.trim()
        if (phoneNum.length < 10) {
            _otpVerificationError.value = "Please enter a valid 10-digit mobile number."
            return
        }
        val otp = (1000..9999).random().toString()
        _generatedOtp.value = otp
        _otpSent.value = true
        _otpVerificationError.value = null
        triggerToast(
            title = "SMS OTP Sent",
            message = "Your verification code is: $otp. Enter it to verify.",
            isRegistration = false
        )
    }

    fun verifyOtp() {
        if (_otpCode.value == _generatedOtp.value) {
            _isPhoneVerified.value = true
            _otpVerificationError.value = null
            triggerToast(
                title = "Phone Verified",
                message = "Your phone number has been successfully verified for booking.",
                isRegistration = false
            )
        } else {
            _otpVerificationError.value = "Invalid OTP code. Please enter the correct code."
        }
    }

    fun resetPhoneVerification() {
        _isPhoneVerified.value = false
        _otpSent.value = false
        _otpCode.value = ""
        _otpVerificationError.value = null
        _generatedOtp.value = ""
    }

    // Navigation setters
    fun setTab(tabName: String) {
        _currentTab.value = tabName
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSpecialtyFilter(specialty: String?) {
        _selectedSpecialtyFilter.value = specialty
    }

    fun updateProfile(name: String, email: String) {
        val trimmedName = name.trim().ifEmpty { "Alex Kim" }
        val trimmedEmail = email.trim().ifEmpty { "alex.kim@email.com" }
        _profileName.value = trimmedName
        _profileEmail.value = trimmedEmail
        sharedPrefs.edit()
            .putString("profile_name", trimmedName)
            .putString("profile_email", trimmedEmail)
            .apply()
    }

    fun loginAsPatient(name: String, email: String) {
        loginAsPatient(name, "25", "Male")
    }

    fun loginAsPatient(name: String, email: String, age: String, sex: String, mobile: String) {
        loginAsPatient(name, age, sex)
    }

    fun loginAsPatient(name: String, age: String, sex: String) {
        val trimmedName = name.trim().ifEmpty { "Alex Kim" }
        val trimmedAge = age.trim().ifEmpty { "25" }
        val trimmedSex = sex.trim().ifEmpty { "Male" }

        viewModelScope.launch {
            var existingPatient = repository.getPatientByNameAgeSex(trimmedName, trimmedAge, trimmedSex)
            if (existingPatient == null) {
                val totalCount = repository.getPatientCount()
                val newIdNo = String.format("%04d", totalCount + 1)
                existingPatient = Patient(
                    uniqueNumber = newIdNo,
                    name = trimmedName,
                    age = trimmedAge,
                    sex = trimmedSex,
                    mobile = "N/A",
                    email = "N/A"
                )
                repository.insertPatient(existingPatient)
                
                triggerToast(
                    title = "New Patient Registered",
                    message = "Patient: ${existingPatient.name} (ID: ${existingPatient.uniqueNumber}) registered successfully.",
                    isRegistration = true
                )
            }

            _profileName.value = existingPatient.name
            _profileEmail.value = existingPatient.email
            _profileAge.value = existingPatient.age
            _profileSex.value = existingPatient.sex
            _profileMobile.value = existingPatient.mobile
            _profileUniqueNo.value = existingPatient.uniqueNumber

            _userRole.value = "PATIENT"
            _loggedInDoctorId.value = null

            sharedPrefs.edit()
                .putString("profile_name", existingPatient.name)
                .putString("profile_email", existingPatient.email)
                .putString("profile_age", existingPatient.age)
                .putString("profile_sex", existingPatient.sex)
                .putString("profile_mobile", existingPatient.mobile)
                .putString("profile_unique_no", existingPatient.uniqueNumber)
                .putString("user_role", "PATIENT")
                .remove("logged_in_doctor_id")
                .apply()
        }
    }

    fun loginAsDoctor(doctorId: Int) {
        _loggedInDoctorId.value = doctorId
        _userRole.value = "DOCTOR"
        sharedPrefs.edit()
            .putString("user_role", "DOCTOR")
            .putInt("logged_in_doctor_id", doctorId)
            .apply()
    }

    fun logout() {
        _userRole.value = "NONE"
        _loggedInDoctorId.value = null
        sharedPrefs.edit()
            .putString("user_role", "NONE")
            .remove("logged_in_doctor_id")
            .apply()
    }

    // Booking configuration
    fun startBooking(doctor: Doctor) {
        _bookingDoctor.value = doctor
        _selectedDate.value = "Tue, Jun 2"
        _selectedTimeSlot.value = doctor.availableSlots.firstOrNull() ?: "05:00 PM"
        _consultationType.value = "In-Clinic Visit"
        _patientSymptoms.value = ""
        _bookingSuccess.value = false
        _paymentError.value = null
        _launchPaymentEvent.value = null
        _paymentSuccessId.value = ""
        _bookingPhone.value = if (_profileMobile.value.isNotEmpty() && _profileMobile.value != "N/A") _profileMobile.value else "9876543210"
        resetPhoneVerification()
    }

    fun setBookingDate(date: String) {
        _selectedDate.value = date
    }

    fun setBookingTimeSlot(slot: String) {
        _selectedTimeSlot.value = slot
    }

    fun setConsultationType(type: String) {
        _consultationType.value = type
    }

    fun setSymptoms(symptoms: String) {
        _patientSymptoms.value = symptoms
    }

    fun setSelectedPaymentMethod(method: String) {
        _selectedPaymentMethod.value = method
    }

    fun dismissBooking() {
        _bookingDoctor.value = null
        _bookingSuccess.value = false
        _paymentError.value = null
        _launchPaymentEvent.value = null
        _paymentSuccessId.value = ""
        _selectedPaymentMethod.value = "any"
        resetPhoneVerification()
    }

    fun clearPaymentEvent() {
        _launchPaymentEvent.value = null
    }

    fun setCustomKeyId(keyId: String) {
        _customKeyId.value = keyId
    }

    fun initiatePayment() {
        val doctor = _bookingDoctor.value ?: return
        _paymentError.value = null
        _launchPaymentEvent.value = PaymentDetails(
            doctorName = doctor.name,
            feeAmount = doctor.fee,
            email = _profileEmail.value,
            contact = _bookingPhone.value, // User's verified phone number
            paymentMethod = _selectedPaymentMethod.value
        )
    }

    fun handlePaymentSuccess(paymentId: String) {
        val doctor = _bookingDoctor.value ?: return
        viewModelScope.launch {
            val appointment = Appointment(
                doctorId = doctor.id,
                doctorName = doctor.name,
                doctorSpecialty = doctor.specialty,
                clinicName = doctor.clinic,
                consultingFee = doctor.fee,
                date = _selectedDate.value,
                timeSlot = _selectedTimeSlot.value,
                consultationType = _consultationType.value,
                symptoms = _patientSymptoms.value,
                paymentId = paymentId,
                patientName = _profileName.value,
                patientUniqueNo = _profileUniqueNo.value,
                patientAge = _profileAge.value,
                patientSex = _profileSex.value,
                patientMobile = _bookingPhone.value, // User's verified phone number
                patientEmail = _profileEmail.value
            )
            repository.insert(appointment)
            
            triggerToast(
                title = "New Appointment Booked",
                message = "Consultation booked with ${appointment.doctorName} for ${appointment.date} at ${appointment.timeSlot}.",
                isRegistration = false
            )

            _paymentSuccessId.value = paymentId
            _bookingSuccess.value = true
            _launchPaymentEvent.value = null
            _paymentError.value = null

            // Sync with Supabase on-the-fly
            if (_useSupabase.value) {
                _supabaseSyncStatus.value = "Syncing checkout..."
                val success = supabaseService.insertAppointment(
                    url = _supabaseUrl.value,
                    anonKey = _supabaseAnonKey.value,
                    appointment = appointment
                )
                if (success) {
                    _supabaseSyncStatus.value = "Appointment synced to cloud database"
                } else {
                    _supabaseSyncStatus.value = "Saved offline (Sync pending)"
                }
            }
        }
    }

    fun handlePaymentFailure(errorMsg: String) {
        // Clean error display message
        val displayMsg = if (errorMsg.contains("BAD_REQUEST_ERROR")) {
            "Payment failed. Check your API Key settings."
        } else {
            errorMsg
        }
        _paymentError.value = displayMsg
        _launchPaymentEvent.value = null
    }

    fun confirmAppointment() {
        initiatePayment()
    }

    fun cancelAppointment(id: Int) {
        viewModelScope.launch {
            val localList = repository.getAppointmentsDirect()
            val appointment = localList.find { it.id == id } ?: return@launch
            val updated = appointment.copy(status = "Cancelled")
            
            repository.insert(updated)
            
            triggerToast(
                title = "Booking Cancelled",
                message = "Consultation with ${appointment.doctorName} has been cancelled. Note: No refund is available.",
                isRegistration = false
            )
            
            if (_useSupabase.value) {
                _supabaseSyncStatus.value = "Syncing remote cancellation..."
                val success = supabaseService.updateAppointmentStatus(
                    url = _supabaseUrl.value,
                    anonKey = _supabaseAnonKey.value,
                    paymentId = updated.paymentId,
                    doctorId = updated.doctorId,
                    timestamp = updated.timestamp,
                    newStatus = "Cancelled"
                )
                if (success) {
                    _supabaseSyncStatus.value = "Cancelled remotely"
                } else {
                    _supabaseSyncStatus.value = "Cancelled locally (offline)"
                }
            }
        }
    }

    fun completeAppointment(id: Int) {
        viewModelScope.launch {
            val localList = repository.getAppointmentsDirect()
            val appointment = localList.find { it.id == id } ?: return@launch
            val updated = appointment.copy(status = "Completed")
            
            repository.insert(updated)
            
            if (_useSupabase.value) {
                _supabaseSyncStatus.value = "Syncing remote completion..."
                val success = supabaseService.updateAppointmentStatus(
                    url = _supabaseUrl.value,
                    anonKey = _supabaseAnonKey.value,
                    paymentId = updated.paymentId,
                    doctorId = updated.doctorId,
                    timestamp = updated.timestamp,
                    newStatus = "Completed"
                )
                if (success) {
                    _supabaseSyncStatus.value = "Completed remotely"
                } else {
                    _supabaseSyncStatus.value = "Completed locally"
                }
            }
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            repository.clear()
        }
    }
}

class MediBookViewModelFactory(
    private val repository: AppointmentRepository,
    private val context: Context
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MediBookViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MediBookViewModel(repository, context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
