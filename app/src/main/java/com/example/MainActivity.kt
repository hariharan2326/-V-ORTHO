package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.razorpay.Checkout
import com.razorpay.PaymentResultListener
import android.app.Activity
import com.example.data.AppDatabase
import com.example.data.Appointment
import com.example.data.AppointmentRepository
import com.example.ui.*
import com.example.ui.theme.*

class MainActivity : ComponentActivity(), PaymentResultListener {
    private var mainViewModel: MediBookViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Create Room database instance and repository manually
        val database = AppDatabase.getDatabase(this)
        val repository = AppointmentRepository(database.appointmentDao(), database.patientDao())

        setContent {
            MyApplicationTheme {
                val viewModel: MediBookViewModel = viewModel(
                    factory = MediBookViewModelFactory(repository, applicationContext)
                )
                mainViewModel = viewModel
                MediBookApp(viewModel = viewModel)
            }
        }
    }

    override fun onPaymentSuccess(razorpayPaymentId: String?) {
        mainViewModel?.handlePaymentSuccess(razorpayPaymentId ?: "PAY_SUCCESS")
    }

    override fun onPaymentError(code: Int, response: String?) {
        mainViewModel?.handlePaymentFailure(response ?: "Payment cancelled or failed (Code: $code)")
    }
}

@Composable
fun MediBookApp(viewModel: MediBookViewModel) {
    val userRole by viewModel.userRole.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = userRole,
            transitionSpec = {
                fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
            },
            label = "RoleAppTransition",
            modifier = Modifier.fillMaxSize()
        ) { role ->
            when (role) {
                "NONE" -> LoginScreen(viewModel = viewModel)
                "DOCTOR" -> DoctorDashboardScreen(viewModel = viewModel)
                else -> PatientAppScreen(viewModel = viewModel)
            }
        }

        NotificationToastOverlay(
            viewModel = viewModel,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 40.dp)
        )
    }
}

@Composable
fun PatientAppScreen(viewModel: MediBookViewModel) {
    val currentTab by viewModel.currentTab.collectAsStateWithLifecycle()
    val bookingDoctor by viewModel.bookingDoctor.collectAsStateWithLifecycle()

    val launchPayment by viewModel.launchPaymentEvent.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(launchPayment) {
        launchPayment?.let { details ->
            try {
                var currentContext = context
                while (currentContext is android.content.ContextWrapper && currentContext !is Activity) {
                    currentContext = currentContext.baseContext
                }
                val activity = currentContext as? Activity ?: return@LaunchedEffect
                val checkout = Checkout()
                
                // Retrieve Key ID from Profile setting (or BuildConfig / default test key)
                val customKey = viewModel.customKeyId.value.trim()
                val keyId = if (customKey.isNotEmpty() && customKey.length >= 8 && customKey.startsWith("rzp_")) {
                    customKey
                } else if (BuildConfig.RAZORPAY_KEY_ID.isNotEmpty() && 
                           BuildConfig.RAZORPAY_KEY_ID != "rzp_test_placeholder_key_id" && 
                           BuildConfig.RAZORPAY_KEY_ID.trim().length >= 8 && 
                           BuildConfig.RAZORPAY_KEY_ID.trim().startsWith("rzp_")) {
                    BuildConfig.RAZORPAY_KEY_ID.trim()
                } else {
                    // Safe demonstratable public test Key ID
                    "rzp_test_SwfZyxNgHor83f"
                }
                checkout.setKeyID(keyId)

                val options = org.json.JSONObject()
                options.put("name", "V Ortho Payment")
                options.put("description", "Booking charge for ${details.doctorName}")
                options.put("theme.color", "#0D9488")
                options.put("currency", "INR")
                
                // Convert amounts like "₹50" or "50" cleanly into paise (amount * 100)
                val cleanAmount = details.feeAmount.replace("₹", "").trim().toDoubleOrNull() ?: 50.0
                val amountInPaise = (cleanAmount * 100).toInt()
                options.put("amount", amountInPaise)

                val prefill = org.json.JSONObject()
                prefill.put("email", details.email)
                prefill.put("contact", details.contact)
                if (details.paymentMethod == "upi") {
                    prefill.put("method", "upi")
                    prefill.put("vpa", "success@razorpay") // Autofills test VPA so UPI payments can be simulated/tested on any device/emulator without UPI apps installed!
                } else if (details.paymentMethod == "card") {
                    prefill.put("method", "card")
                }
                options.put("prefill", prefill)

                val retryObj = org.json.JSONObject()
                retryObj.put("enabled", true)
                retryObj.put("max_count", 4)
                options.put("retry", retryObj)

                checkout.open(activity, options)
            } catch (e: Exception) {
                viewModel.handlePaymentFailure("Payment initiation failed: ${e.localizedMessage}")
            } finally {
                viewModel.clearPaymentEvent()
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("main_scaffold"),
        bottomBar = {
            MediBookBottomBar(
                currentTab = currentTab,
                onTabSelected = { viewModel.setTab(it) }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AsyncImage(
                model = R.drawable.img_ortho_bg,
                contentDescription = "Orthopedic background theme illustration",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.12f
            )

            AnimatedContent(
                targetState = currentTab,
                transitionSpec = {
                    fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(220))
                },
                label = "TabTransition"
            ) { tab ->
                when (tab) {
                    "Home" -> HomeScreen(viewModel = viewModel)
                    "Doctors" -> DoctorsScreen(viewModel = viewModel)
                    "Bookings" -> BookingsScreen(viewModel = viewModel)
                    "Profile" -> ProfileScreen(viewModel = viewModel)
                }
            }

            // Booking Modal Sheet overlay
            bookingDoctor?.let { doctor ->
                BookingModalOverlay(
                    doctor = doctor,
                    viewModel = viewModel,
                    onDismiss = { viewModel.dismissBooking() }
                )
            }
        }
    }
}

@Composable
fun LoginScreen(viewModel: MediBookViewModel) {
    var isPatientRole by remember { mutableStateOf(true) }
    
    // Patient form state
    var patientName by remember { mutableStateOf("") }
    var patientEmail by remember { mutableStateOf("") }
    var patientAge by remember { mutableStateOf("") }
    var patientSex by remember { mutableStateOf("Male") }
    var patientMobile by remember { mutableStateOf("") }
    
    // Doctor form state
    var selectedDoctorId by remember { mutableStateOf(1) } // Default Dr. Venkatesh (ID=1)
    var passcode by remember { mutableStateOf("") }
    
    var showPasswordError by remember { mutableStateOf(false) }
    var showInputError by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SoftBg)
    ) {
        // Aesthetic Top Wave Gradient Background
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.38f)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(TealSecondary, TealPrimary)
                    ),
                    shape = RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp)
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .statusBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Large Medical Icon Badge with beautiful logo branding
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.White)
                        .border(1.5.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(18.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = R.drawable.logo_red_orange_1780921081403,
                        contentDescription = "V Ortho Logo",
                        modifier = Modifier.size(54.dp),
                        contentScale = ContentScale.Fit
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "V Ortho Healthcare",
                    fontSize = 25.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    letterSpacing = 0.8.sp
                )

                Text(
                    text = "Premium Patient & Doctor Booking Network",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.88f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // Login Card Panel overlapping the background
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 18.dp, vertical = 20.dp)
                .offset(y = (-20).dp)
                .testTag("login_card"),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "SELECT APPOINTMENT PORTAL",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = TextDarkSecondary,
                    letterSpacing = 1.sp,
                    modifier = Modifier.align(Alignment.Start)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Beautiful Role Selector Tab Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(BorderLight)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Patient Tab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(22.dp))
                            .background(if (isPatientRole) TealPrimary else Color.Transparent)
                            .clickable { isPatientRole = true }
                            .testTag("tab_patient_login")
                            .minimumInteractiveComponentSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = if (isPatientRole) Color.White else TextDarkSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Patient",
                                color = if (isPatientRole) Color.White else TextDarkSecondary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }

                    // Doctor Tab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(22.dp))
                            .background(if (!isPatientRole) TealPrimary else Color.Transparent)
                            .clickable { isPatientRole = false }
                            .testTag("tab_doctor_login")
                            .minimumInteractiveComponentSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.MedicalServices,
                                contentDescription = null,
                                tint = if (!isPatientRole) Color.White else TextDarkSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Doctor",
                                color = if (!isPatientRole) Color.White else TextDarkSecondary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Form layouts with Premium designs
                AnimatedContent(
                    targetState = isPatientRole,
                    transitionSpec = {
                        fadeIn(animationSpec = spring(dampingRatio = 0.82f)) togetherWith fadeOut(animationSpec = spring(dampingRatio = 0.82f))
                    },
                    label = "LoginFormTransition"
                ) { isPatient ->
                    if (isPatient) {
                        // Patient Login Layout
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Secure Patient Log In",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextDarkPrimary
                            )
                            Text(
                                text = "Enter details to view doctors and book clinical slots",
                                fontSize = 12.sp,
                                color = TextDarkSecondary,
                                modifier = Modifier.padding(top = 2.dp, bottom = 14.dp)
                            )

                            // Name Input
                            OutlinedTextField(
                                value = patientName,
                                onValueChange = {
                                    patientName = it
                                    showInputError = false
                                },
                                label = { Text("Enter Your Name") },
                                leadingIcon = {
                                    Icon(Icons.Default.Person, contentDescription = null, tint = TealPrimary)
                                },
                                placeholder = { Text("e.g. Alex Kim") },
                                singleLine = true,
                                shape = RoundedCornerShape(11.dp),
                                isError = showInputError && patientName.isBlank(),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = TealPrimary,
                                    focusedLabelColor = TealPrimary
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("patient_username_input")
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // Age & Sex in a clean row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Age Input
                                OutlinedTextField(
                                    value = patientAge,
                                    onValueChange = {
                                        patientAge = it
                                        showInputError = false
                                    },
                                    label = { Text("Age") },
                                    leadingIcon = {
                                        Icon(Icons.Default.DateRange, contentDescription = null, tint = TealPrimary)
                                    },
                                    placeholder = { Text("e.g. 28") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(11.dp),
                                    isError = showInputError && (patientAge.isBlank() || patientAge.toIntOrNull() == null),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = TealPrimary,
                                        focusedLabelColor = TealPrimary
                                    ),
                                    modifier = Modifier
                                        .weight(1.2f)
                                        .testTag("patient_age_input")
                                )

                                // Sex Selection
                                Column(
                                    modifier = Modifier.weight(1.8f)
                                ) {
                                    Text(
                                        text = "Gender",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextDarkSecondary,
                                        modifier = Modifier.padding(bottom = 2.dp)
                                    )
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(44.dp)
                                            .clip(RoundedCornerShape(11.dp))
                                            .background(BorderLight)
                                            .padding(2.dp),
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        listOf("Male", "Female", "Other").forEach { gender ->
                                            val isGenderSelected = patientSex == gender
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .fillMaxHeight()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(if (isGenderSelected) TealPrimary else Color.Transparent)
                                                    .clickable { patientSex = gender }
                                                    .minimumInteractiveComponentSize(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = gender,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isGenderSelected) Color.White else TextDarkSecondary
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            if (showInputError) {
                                val errorMsg = when {
                                    patientName.isBlank() -> "Please enter your name"
                                    patientAge.isBlank() || patientAge.toIntOrNull() == null -> "Please enter your age"
                                    else -> "Please enter all required fields"
                                }
                                Text(
                                    text = errorMsg,
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = {
                                    if (patientName.isNotBlank() && 
                                        patientAge.isNotBlank() &&
                                        patientAge.toIntOrNull() != null
                                    ) {
                                        viewModel.loginAsPatient(
                                            name = patientName,
                                            age = patientAge,
                                            sex = patientSex
                                        )
                                    } else {
                                        showInputError = true
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("patient_login_button"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = TealPrimary),
                                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                            ) {
                                Text(
                                    text = "Enter Patient Dashboard",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else {
                        // Doctor Login Layout
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Welcome Clinical Specialist",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextDarkPrimary
                            )
                            Text(
                                text = "Choose your specialist profile and input your clinic access PIN",
                                fontSize = 12.sp,
                                color = TextDarkSecondary,
                                modifier = Modifier.padding(top = 2.dp, bottom = 14.dp)
                            )

                            Text(
                                text = "SELECT YOUR PROFILE:",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = TextDarkPrimary,
                                letterSpacing = 0.5.sp,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )

                            // Selectable Cards for each Doctor in directory with premium border lights
                            viewModel.doctorsList.forEach { doctor ->
                                val isSelected = doctor.id == selectedDoctorId
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .border(
                                            1.5.dp,
                                            if (isSelected) TealPrimary else BorderLight,
                                            RoundedCornerShape(12.dp)
                                        )
                                        .clickable { selectedDoctorId = doctor.id }
                                        .testTag("doctor_select_${doctor.id}"),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected) TealContainerLight.copy(alpha = 0.6f) else SoftBg
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(38.dp)
                                                .clip(CircleShape)
                                                .background(Color(doctor.avatarColor)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = doctor.name.split(" ").filter { it.isNotEmpty() && !it.contains(".") }.take(2).map { it.first() }.joinToString(""),
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(10.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = doctor.name,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) TealSecondary else TextDarkPrimary
                                            )
                                            Text(
                                                text = "${doctor.specialty} • ${doctor.clinic}",
                                                fontSize = 11.sp,
                                                color = TextDarkSecondary
                                            )
                                        }

                                        if (isSelected) {
                                            Box(
                                                modifier = Modifier
                                                    .size(18.dp)
                                                    .clip(CircleShape)
                                                    .background(TealPrimary),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "Selected",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Passcode Input
                            OutlinedTextField(
                                value = passcode,
                                onValueChange = {
                                    passcode = it
                                    showPasswordError = false
                                },
                                label = { Text("Specialist PIN/Passcode") },
                                leadingIcon = {
                                    Icon(Icons.Default.Lock, contentDescription = null, tint = TealPrimary)
                                },
                                placeholder = { Text("e.g. 1234") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                isError = showPasswordError,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = TealPrimary,
                                    focusedLabelColor = TealPrimary
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("doctor_password_input")
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Demo access code: 1234",
                                    fontSize = 11.sp,
                                    color = TextDarkSecondary,
                                    fontWeight = FontWeight.Medium
                                )
                                if (showPasswordError) {
                                    Text(
                                        text = "Incorrect PIN. Use 1234.",
                                        color = MaterialTheme.colorScheme.error,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            Button(
                                onClick = {
                                    if (passcode == "1234") {
                                        viewModel.loginAsDoctor(selectedDoctorId)
                                    } else {
                                        showPasswordError = true
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("doctor_login_button"),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = TealPrimary),
                                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                            ) {
                                Text(
                                    text = "Enter Doctor Dashboard",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DoctorDashboardScreen(viewModel: MediBookViewModel) {
    val loggedInDoctorId by viewModel.loggedInDoctorId.collectAsStateWithLifecycle()
    val appointments by viewModel.appointments.collectAsStateWithLifecycle()
    val patients by viewModel.allPatients.collectAsStateWithLifecycle()

    val doctor = remember(loggedInDoctorId) {
        viewModel.doctorsList.find { it.id == loggedInDoctorId } ?: viewModel.doctorsList.first()
    }

    val doctorAppointments = remember(appointments, loggedInDoctorId) {
        appointments.filter { it.doctorId == loggedInDoctorId && it.status != "Completed" && it.status != "Cancelled" }
    }

    var doctorTab by remember { mutableStateOf("Schedule") } // "Schedule" or "Patients"
    var selectedAppointmentToDetail by remember { mutableStateOf<Appointment?>(null) }
    var showMarkCompletedDialog by remember { mutableStateOf<Appointment?>(null) }

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("doctor_scaffold"),
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Specialist Dashboard",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "V Ortho Clinic Portal",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                },
                actions = {
                    // Accentuated Sign Out Button
                    IconButton(
                        onClick = { viewModel.logout() },
                        modifier = Modifier
                            .testTag("btn_doctor_logout")
                            .minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Default.ExitToApp,
                            contentDescription = "Log Out of Doctor Portal",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TealPrimary,
                    titleContentColor = Color.White
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SoftBg)
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            // Welcome Header Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, BorderLight),
                modifier = Modifier.fillMaxWidth().testTag("doctor_profile_banner")
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(Color(doctor.avatarColor)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = doctor.name.split(" ").filter { it.isNotEmpty() && !it.contains(".") }.take(2).map { it.first() }.joinToString(""),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = doctor.name,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextDarkPrimary
                        )
                        Text(
                            text = doctor.degree,
                            fontSize = 11.sp,
                            color = TextDarkSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(TealContainerLight)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = doctor.specialty,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TealSecondary
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = doctor.clinic,
                                fontSize = 11.sp,
                                color = TextDarkSecondary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Inline Metrics Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Total slots or appointments metric card
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, BorderLight),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Active Consultations", fontSize = 11.sp, color = TextDarkSecondary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("${doctorAppointments.size}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TealPrimary)
                    }
                }

                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, BorderLight),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Rating index", fontSize = 11.sp, color = TextDarkSecondary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Star, contentDescription = null, tint = StarYellow, modifier = Modifier.size(18.dp))
                            Text(" ${doctor.rating}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextDarkPrimary)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Beautiful sub navigation pills
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(BorderLight)
                    .padding(3.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (doctorTab == "Schedule") TealPrimary else Color.Transparent)
                        .clickable { doctorTab = "Schedule" }
                        .testTag("btn_tab_schedule"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Clinical Schedule (${doctorAppointments.size})",
                        color = if (doctorTab == "Schedule") Color.White else TextDarkSecondary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (doctorTab == "Patients") TealPrimary else Color.Transparent)
                        .clickable { doctorTab = "Patients" }
                        .testTag("btn_tab_patients"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Registered Patients (${patients.size})",
                        color = if (doctorTab == "Patients") Color.White else TextDarkSecondary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (doctorTab == "Schedule") {
                // Appointments Section Heading
                Text(
                    text = "Next Consultations Schedule",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDarkPrimary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                if (doctorAppointments.isEmpty()) {
                    // Beautiful Empty Schedule state
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, BorderLight, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(TealContainerLight),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DateRange,
                                    contentDescription = null,
                                    tint = TealSecondary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "No Appointments Found",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextDarkPrimary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "When patients request clinical visits or video consultations with you, they will appear here.",
                                fontSize = 12.sp,
                                color = TextDarkSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    // LazyColumn schedule listing
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .testTag("doctor_appointments_list")
                    ) {
                        items(doctorAppointments, key = { it.id }) { appointment ->
                            DoctorAppointmentCardItem(
                                appointment = appointment,
                                onCompleteClick = { showMarkCompletedDialog = appointment },
                                onDetailClick = { selectedAppointmentToDetail = appointment }
                            )
                        }
                    }
                }
            } else {
                PatientsTable(
                    patients = patients,
                    appointments = appointments,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    // Mark Completed Dialog
    showMarkCompletedDialog?.let { appointment ->
        AlertDialog(
            onDismissRequest = { showMarkCompletedDialog = null },
            title = { Text("Complete Consultation") },
            text = { Text("Have you completed your medical consultation with patient on ${appointment.date} at ${appointment.timeSlot}?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.completeAppointment(appointment.id) // This marks it completed!
                        showMarkCompletedDialog = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TealPrimary)
                ) {
                    Text("Yes, Complete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showMarkCompletedDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Consultation detail dialog
    selectedAppointmentToDetail?.let { appointment ->
        AlertDialog(
            onDismissRequest = { selectedAppointmentToDetail = null },
            title = { Text("Consultation Details", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Patient: ${appointment.patientName}", fontWeight = FontWeight.Bold, color = TextDarkPrimary)
                    Text("Time Slot: ${appointment.date} at ${appointment.timeSlot}", fontSize = 13.sp, color = TextDarkSecondary)
                    Text("Session Mode: ${appointment.consultationType}", fontSize = 13.sp, color = TextDarkSecondary)
                    Text("Pre-booking Charge: ${appointment.consultingFee}", fontSize = 13.sp, color = TextDarkSecondary)
                    if (appointment.paymentId.isNotEmpty()) {
                        Text("Payment Ref ID: ${appointment.paymentId}", fontSize = 13.sp, color = TealSecondary, fontWeight = FontWeight.SemiBold)
                    }
                    HorizontalDivider(color = BorderLight)
                    Text("Consultation Notes / Transcribed Symptoms:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = TextDarkPrimary)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(SoftBg)
                            .padding(10.dp)
                    ) {
                        Text(
                            text = appointment.symptoms.ifEmpty { "Patient did not specify symptoms." },
                            fontSize = 12.sp,
                            color = TextDarkPrimary
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { selectedAppointmentToDetail = null },
                    colors = ButtonDefaults.buttonColors(containerColor = TealPrimary)
                ) {
                    Text("Dismiss")
                }
            }
        )
    }
}

@Composable
fun DoctorAppointmentCardItem(
    appointment: Appointment,
    onCompleteClick: () -> Unit,
    onDetailClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, BorderLight),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("doctor_appointment_card_${appointment.id}")
            .clickable { onDetailClick() }
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Patient Header row
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(TealContainerLight),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = TealSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Text(
                        text = "Patient: ${appointment.patientName}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDarkPrimary
                    )
                    Text(
                        text = "${appointment.consultationType} • ${appointment.consultingFee}",
                        fontSize = 11.sp,
                        color = TextDarkSecondary
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Date Time Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(BorderLight)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = appointment.timeSlot,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDarkPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Display Symptoms
            if (appointment.symptoms.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(SoftBg)
                        .padding(8.dp)
                ) {
                    Text(
                        text = appointment.symptoms,
                        fontSize = 11.sp,
                        color = TextDarkSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            HorizontalDivider(color = BorderLight, thickness = 1.dp)

            Spacer(modifier = Modifier.height(8.dp))

            // Actions row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = appointment.date,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TealPrimary
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = onDetailClick,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Text("Details", fontSize = 12.sp, color = TextDarkSecondary, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = onCompleteClick,
                        colors = ButtonDefaults.buttonColors(containerColor = TealPrimary),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Text("Complete", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun MediBookBottomBar(
    currentTab: String,
    onTabSelected: (String) -> Unit
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        modifier = Modifier.testTag("bottom_nav_bar")
    ) {
        NavigationBarItem(
            selected = currentTab == "Home",
            onClick = { onTabSelected("Home") },
            label = { Text("Home", fontWeight = FontWeight.Medium) },
            icon = {
                Icon(
                    imageVector = if (currentTab == "Home") Icons.Filled.Home else Icons.Outlined.Home,
                    contentDescription = "Home tab"
                )
            },
            modifier = Modifier.testTag("tab_home")
        )
        NavigationBarItem(
            selected = currentTab == "Doctors",
            onClick = { onTabSelected("Doctors") },
            label = { Text("Doctors", fontWeight = FontWeight.Medium) },
            icon = {
                Icon(
                    imageVector = if (currentTab == "Doctors") Icons.Filled.LocalHospital else Icons.Outlined.LocalHospital,
                    contentDescription = "Doctors tab"
                )
            },
            modifier = Modifier.testTag("tab_doctors")
        )
        NavigationBarItem(
            selected = currentTab == "Bookings",
            onClick = { onTabSelected("Bookings") },
            label = { Text("Bookings", fontWeight = FontWeight.Medium) },
            icon = {
                Icon(
                    imageVector = if (currentTab == "Bookings") Icons.Filled.DateRange else Icons.Outlined.DateRange,
                    contentDescription = "Appointments tab"
                )
            },
            modifier = Modifier.testTag("tab_bookings")
        )
        NavigationBarItem(
            selected = currentTab == "Profile",
            onClick = { onTabSelected("Profile") },
            label = { Text("Profile", fontWeight = FontWeight.Medium) },
            icon = {
                Icon(
                    imageVector = if (currentTab == "Profile") Icons.Filled.Person else Icons.Outlined.Person,
                    contentDescription = "Profile tab"
                )
            },
            modifier = Modifier.testTag("tab_profile")
        )
    }
}

@Composable
fun HomeScreen(viewModel: MediBookViewModel) {
    val profileName by viewModel.profileName.collectAsStateWithLifecycle()
    val profileUniqueNo by viewModel.profileUniqueNo.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        // Premium Top Header Greeting Layout with Subtle Depth
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Good morning",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Light,
                        color = TextDarkPrimary
                    )
                    Text(
                        text = " 👋",
                        fontSize = 24.sp,
                        color = TextDarkPrimary
                    )
                }
                Text(
                    text = "Hello $profileName, hope you feel healthy today",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextDarkSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
                if (profileUniqueNo.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(TealContainerLight)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "Patient Unique No: $profileUniqueNo",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = TealSecondary
                        )
                    }
                }
            }
            // User Avatar Container with Quick Logout Action
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(TealTertiary, TealPrimary)
                            )
                        )
                        .border(1.5.dp, Color.White, CircleShape)
                        .clickable { viewModel.setTab("Profile") }
                        .testTag("avatar_container"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = profileName.take(2).uppercase(),
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp,
                        letterSpacing = 0.5.sp
                    )
                }

                // Quick Logout Patient Action Button
                IconButton(
                    onClick = { viewModel.logout() },
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFFECEF)) // Elegant soft rose-pink
                        .border(1.dp, Color(0xFFFECDD3), CircleShape) // Sweet rose border
                        .testTag("patient_header_logout_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.ExitToApp,
                        contentDescription = "Log out",
                        tint = Color(0xFFE11D48), // Rose crimson
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Hero Promotional Card with Premium Radial/Linear Teal Gradient
        Card(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("hero_promo_card")
                .clickable { viewModel.setTab("Doctors") },
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(TealSecondary, TealPrimary)
                        )
                    )
                    .fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1.2f)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                "SPECIALIST PORTAL",
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Trusted specialists,\njust a slot away",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            lineHeight = 25.sp
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = { viewModel.setTab("Doctors") },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(10.dp),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                        ) {
                            Text(
                                text = "Book Doctor Now",
                                color = TealPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(0.8f)
                            .align(Alignment.CenterVertically),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(88.dp)
                                .clip(CircleShape)
                                .background(Color.White),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = R.drawable.logo_red_orange_1780921081403,
                                contentDescription = "Medical banner logo",
                                modifier = Modifier.size(66.dp),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Dynamic Bulletin Announcement Bar
        Card(
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = TealContainerLight.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = TealSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Clinic Update: All specialists verify medical registrations with us.",
                    fontSize = 11.sp,
                    color = TealSecondary,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Specialty Section Headline
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Clinical Specialities",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextDarkPrimary,
                letterSpacing = 0.2.sp
            )
            Text(
                text = "See all",
                fontSize = 13.sp,
                color = TealPrimary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable {
                        viewModel.setSpecialtyFilter(null)
                        viewModel.setTab("Doctors")
                    }
                    .padding(4.dp)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Beautiful Specialties Horizontal chips Grid
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val specialties = listOf(
                Triple("Orthopaedic", Icons.Default.Healing, "Bone care"),
                Triple("Paediatrics", Icons.Default.Face, "Kids health"),
                Triple("General", Icons.Default.MedicalServices, "Primary care")
            )
            specialties.forEach { (name, icon, desc) ->
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, BorderLight),
                    modifier = Modifier
                        .width(108.dp)
                        .clickable {
                            val filterName = if (name == "General") null else name
                            viewModel.setSpecialtyFilter(filterName)
                            viewModel.setTab("Doctors")
                        }
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(TealContainerLight),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = name,
                                tint = TealSecondary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = name,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextDarkPrimary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = desc,
                            fontSize = 9.sp,
                            color = TextDarkSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Our Doctors Featured List Inline Headline
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Our Featured Specialists",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextDarkPrimary,
                letterSpacing = 0.2.sp
            )
            Text(
                text = "Show all",
                fontSize = 13.sp,
                color = TealPrimary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable {
                        viewModel.setSpecialtyFilter(null)
                        viewModel.setTab("Doctors")
                    }
                    .padding(4.dp)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Featured Row containing MV and PR specialists
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // Display top 2 featured doctors directly on Home
            viewModel.doctorsList.take(2).forEach { doctor ->
                FeaturedDoctorRowItem(
                    doctor = doctor,
                    onBookClick = { viewModel.startBooking(doctor) }
                )
            }
        }
    }
}

@Composable
fun FeaturedDoctorRowItem(doctor: Doctor, onBookClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("featured_doctor_item_${doctor.id}")
            .clickable { onBookClick() },
        border = BorderStroke(1.dp, BorderLight),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Customized Avatar with Green Glowing Online status dot marker
            Box(
                modifier = Modifier.size(62.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.sweepGradient(
                                listOf(Color(doctor.avatarColor), Color(doctor.avatarColor).copy(alpha = 0.7f))
                            )
                        )
                        .border(1.5.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    val initials = doctor.name.split(" ").filter { it.isNotEmpty() && !it.contains(".") }.take(2).map { it.first() }.joinToString("")
                    Text(
                        text = if (initials.isNotEmpty()) initials else "DR",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                // Online Status Badge Dot Indicator
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .padding(2.dp)
                        .align(Alignment.BottomEnd)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(Color(0xFF10B981)) // Glow Green online status
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = doctor.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDarkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    // Perfect Verified Badge Pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(TealContainerLight)
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            "VERIFIED",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = TealSecondary,
                            letterSpacing = 0.5.sp
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Rating",
                            tint = StarYellow,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = " ${doctor.rating}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextDarkPrimary
                        )
                    }
                }

                Text(
                    text = "${doctor.degree} • ${doctor.reviewsCount}+ Patient Reviews",
                    fontSize = 11.sp,
                    color = TextDarkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )

                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(BorderLight)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = doctor.specialty,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextDarkSecondary
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = doctor.clinic,
                        fontSize = 11.sp,
                        color = TextDarkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = BorderLight, thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "PRE-BOOKING CHARGE",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextDarkSecondary,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = doctor.fee,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = TealSecondary,
                            modifier = Modifier.padding(top = 1.dp)
                        )
                    }
                    // Upgraded Action button matching tactile/visual expectations
                    Button(
                        onClick = onBookClick,
                        colors = ButtonDefaults.buttonColors(containerColor = TealPrimary),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text(
                            text = "Book Slot",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DoctorsScreen(viewModel: MediBookViewModel) {
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val specialtyFilter by viewModel.selectedSpecialtyFilter.collectAsStateWithLifecycle()
    val controller = LocalSoftwareKeyboardController.current

    val specialtiesList = listOf("All", "Orthopaedic", "Paediatrics")

    // Filter doctors base on search + specialty parameters
    val filteredDoctorsList = remember(searchQuery, specialtyFilter) {
        viewModel.doctorsList.filter { doc ->
            val matchesSearch = doc.name.contains(searchQuery, ignoreCase = true) ||
                    doc.clinic.contains(searchQuery, ignoreCase = true) ||
                    doc.specialty.contains(searchQuery, ignoreCase = true)
            val matchesSpecialty = specialtyFilter == null || doc.specialty.equals(specialtyFilter, ignoreCase = true)
            matchesSearch && matchesSpecialty
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .padding(16.dp)
    ) {
        Text(
            text = "Find Doctors",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = TextDarkPrimary
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Search Input box Outlined
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.setSearchQuery(it) },
            placeholder = { Text("Search doctors or clinics...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search icon") },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search query")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { controller?.hide() }),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("search_doctor_input"),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TealPrimary,
                unfocusedBorderColor = BorderLight,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface
            )
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Specialties Horizontal Filter Pill Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            specialtiesList.forEach { specialtyName ->
                val isActive = (specialtyName == "All" && specialtyFilter == null) ||
                        (specialtyFilter == specialtyName)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isActive) TealPrimary else BorderLight)
                        .clickable {
                            if (specialtyName == "All") {
                                viewModel.setSpecialtyFilter(null)
                            } else {
                                viewModel.setSpecialtyFilter(specialtyName)
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = specialtyName,
                        fontSize = 13.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                        color = if (isActive) Color.White else TextDarkPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Doctor Scroll Feed List
        if (filteredDoctorsList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Not found icon",
                        tint = TextDarkSecondary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No doctors found matching filters.",
                        fontSize = 15.sp,
                        color = TextDarkSecondary
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("doctors_lazy_column")
            ) {
                items(filteredDoctorsList, key = { it.id }) { doctor ->
                    FeaturedDoctorRowItem(
                        doctor = doctor,
                        onBookClick = { viewModel.startBooking(doctor) }
                    )
                }
            }
        }
    }
}

@Composable
fun BookingsScreen(viewModel: MediBookViewModel) {
    val appointments by viewModel.appointments.collectAsStateWithLifecycle()
    var appointmentToCancel by remember { mutableStateOf<Appointment?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .padding(16.dp)
    ) {
        Text(
            text = "My Appointments",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = TextDarkPrimary
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (appointments.isEmpty()) {
            // Elegant UI Empty State
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(BorderLight),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.DateRange,
                            contentDescription = "Empty bookings calendar",
                            tint = TextDarkSecondary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No upcoming appointments",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDarkPrimary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Book a medical consultant to see your scheduled sessions and details here.",
                        fontSize = 14.sp,
                        color = TextDarkSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = { viewModel.setTab("Doctors") },
                        colors = ButtonDefaults.buttonColors(containerColor = TealPrimary),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Find a doctor", fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("bookings_lazy_column")
            ) {
                items(appointments, key = { it.id }) { appointment ->
                    AppointmentCardItem(
                        appointment = appointment,
                        onCancelClick = { appointmentToCancel = appointment }
                    )
                }
            }
        }
    }

    // Cancellation Safeguard confirmation dialog
    appointmentToCancel?.let { appointment ->
        AlertDialog(
            onDismissRequest = { appointmentToCancel = null },
            title = { Text("Cancel Appointment", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Are you sure you want to cancel your consultation appointment with ${appointment.doctorName} on ${appointment.date} at ${appointment.timeSlot}?",
                        fontSize = 14.sp,
                        color = TextDarkPrimary
                    )
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF2F2)),
                        border = BorderStroke(1.dp, Color(0xFFFFD6D6)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Warning",
                                tint = Color(0xFFD32F2F),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Important Policy: No refund is available for cancelled consultation bookings.",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFD32F2F)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.cancelAppointment(appointment.id)
                        appointmentToCancel = null
                    }
                ) {
                    Text("Yes, Cancel", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { appointmentToCancel = null }) {
                    Text("No, Keep")
                }
            }
        )
    }
}

@Composable
fun StatusBadge(status: String) {
    val (bgColor, textColor, label) = when (status) {
        "Completed" -> Triple(Color(0xFFEAF9EB), Color(0xFF1B5E20), "Completed") // Fresh forest green for completed
        "Cancelled" -> Triple(Color(0xFFFFEEEE), Color(0xFFC62828), "Cancelled") // Warning soft red for cancelled
        "Pending" -> Triple(Color(0xFFFFF9C4), Color(0xFFF57F17), "Pending")       // Muted warm gold for pending
        else -> Triple(Color(0xFFE0F7FA), Color(0xFF006064), "Confirmed")         // Vibrant clinic cyan-teal for confirmed
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
    }
}

@Composable
fun AppointmentCardItem(appointment: Appointment, onCancelClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderLight),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("appointment_card_${appointment.id}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Colored small badge with doctor initials
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(TealContainerLight),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = appointment.doctorName.split(" ").filter { it.isNotEmpty() && !it.contains(".") }.take(2).map { it.first() }.joinToString(""),
                        color = TealSecondary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = appointment.doctorName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDarkPrimary
                    )
                    Text(
                        text = "${appointment.doctorSpecialty} • ${appointment.clinicName}",
                        fontSize = 11.sp,
                        color = TextDarkSecondary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Beautiful Visual Status Badge
                StatusBadge(status = appointment.status)
            }

            HorizontalDivider(color = BorderLight, thickness = 1.dp, modifier = Modifier.padding(vertical = 12.dp))

            // Schedule Badge and Details Row inside container
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Date & Time badge
                Box(
                    modifier = Modifier
                        .weight(1.2f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(TealContainerLight)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.DateRange,
                                contentDescription = null,
                                tint = TealSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = appointment.date,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = TealSecondary
                            )
                        }
                        Text(
                            text = appointment.timeSlot,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = TealSecondary,
                            modifier = Modifier.padding(top = 2.dp, start = 18.dp)
                        )
                    }
                }

                // Consultation Mode Badge
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BorderLight)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (appointment.consultationType.contains("Video")) Icons.Default.Videocam else Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = TextDarkPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = appointment.consultationType,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextDarkPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "Pre-booking charge: " + appointment.consultingFee,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextDarkSecondary,
                            modifier = Modifier.padding(top = 2.dp, start = 18.dp)
                        )
                    }
                }
            }

            // Symptoms / Patient details if they are typed
            if (appointment.symptoms.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = SoftBg),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "Consultation notes:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextDarkSecondary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = appointment.symptoms,
                            fontSize = 12.sp,
                            color = TextDarkPrimary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Card cancellation and detail control buttons
            if (appointment.status != "Completed" && appointment.status != "Cancelled") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onCancelClick,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text("Cancel Appointment", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (appointment.status == "Completed") Color(0xFFF1F8E9) else Color(0xFFFFEBEE),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(vertical = 8.dp, horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (appointment.status == "Completed") Icons.Default.CheckCircle else Icons.Default.Cancel,
                            contentDescription = null,
                            tint = if (appointment.status == "Completed") Color(0xFF43A047) else Color(0xFFE53935),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (appointment.status == "Completed") "Consultation Completed Successfully" else "This appointment was cancelled",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (appointment.status == "Completed") Color(0xFF2E7D32) else Color(0xFFC62828)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileScreen(viewModel: MediBookViewModel) {
    val profileName by viewModel.profileName.collectAsStateWithLifecycle()
    val profileEmail by viewModel.profileEmail.collectAsStateWithLifecycle()
    val appointments by viewModel.appointments.collectAsStateWithLifecycle()
    val customKeyId by viewModel.customKeyId.collectAsStateWithLifecycle()
    val controller = LocalSoftwareKeyboardController.current

    var editName by remember { mutableStateOf(profileName) }
    var editEmail by remember { mutableStateOf(profileEmail) }
    var editRazorpayKey by remember(customKeyId) { mutableStateOf(customKeyId) }

    var enableReminders by remember { mutableStateOf(true) }
    var biometricLock by remember { mutableStateOf(false) }
    var syncHealthData by remember { mutableStateOf(true) }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Text(
            text = "Profile",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = TextDarkPrimary
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Center Profile Avatar Badge
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(TealPrimary, TealTertiary)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = profileName.take(2).uppercase(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = profileName,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextDarkPrimary
            )
            Text(
                text = profileEmail,
                fontSize = 14.sp,
                color = TextDarkSecondary
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Basic Stats Block
        Card(
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, BorderLight)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Appointments", fontSize = 11.sp, color = TextDarkSecondary)
                    Text("${appointments.size}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TealPrimary)
                }
                VerticalDivider(modifier = Modifier.height(32.dp).width(1.dp), color = BorderLight)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Account Status", fontSize = 11.sp, color = TextDarkSecondary)
                    Text("Active Member", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TealSecondary)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Account edit section header
        Text(
            text = "Account",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = TextDarkPrimary
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Edit Profile Form Cards inside surface
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, BorderLight),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Input user name
                OutlinedTextField(
                    value = editName,
                    onValueChange = { editName = it },
                    label = { Text("Display Name") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TealPrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("profile_name_input")
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Input user email
                OutlinedTextField(
                    value = editEmail,
                    onValueChange = { editEmail = it },
                    label = { Text("Email Address") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TealPrimary),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        viewModel.updateProfile(editName, editEmail)
                        controller?.hide()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TealPrimary),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Account Metrics", fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Supabase Cloud Sync Header
        Text(
            text = "Supabase Database Sync",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = TextDarkPrimary
        )

        Spacer(modifier = Modifier.height(10.dp))

        val supabaseUrl by viewModel.supabaseUrl.collectAsStateWithLifecycle()
        val supabaseAnonKey by viewModel.supabaseAnonKey.collectAsStateWithLifecycle()
        val useSupabase by viewModel.useSupabase.collectAsStateWithLifecycle()
        val supabaseSyncStatus by viewModel.supabaseSyncStatus.collectAsStateWithLifecycle()

        var editUrl by remember(supabaseUrl) { mutableStateOf(supabaseUrl) }
        var editKey by remember(supabaseAnonKey) { mutableStateOf(supabaseAnonKey) }
        var tempUseSupabase by remember(useSupabase) { mutableStateOf(useSupabase) }
        var testResultMsg by remember { mutableStateOf<String?>(null) }
        var showSqlDialog by remember { mutableStateOf(false) }

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, BorderLight),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Sync your app appointments bidirectionally with a Cloud Supabase PostgreSQL database.",
                    fontSize = 13.sp,
                    color = TextDarkSecondary,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Toggle Switch
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enable Cloud Sync",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextDarkPrimary
                        )
                        Text(
                            text = "Save appointments directly in your remote PostgreSQL DB",
                            fontSize = 11.sp,
                            color = TextDarkSecondary
                        )
                    }
                    Switch(
                        checked = tempUseSupabase,
                        onCheckedChange = { tempUseSupabase = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TealPrimary,
                            checkedTrackColor = TealContainerLight
                        ),
                        modifier = Modifier.testTag("supabase_sync_switch")
                    )
                }

                // URL Field
                OutlinedTextField(
                    value = editUrl,
                    onValueChange = { editUrl = it.trim() },
                    label = { Text("Supabase URL") },
                    placeholder = { Text("https://your-project.supabase.co") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TealPrimary),
                    modifier = Modifier.fillMaxWidth().testTag("supabase_url_input")
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Anon Key Field
                OutlinedTextField(
                    value = editKey,
                    onValueChange = { editKey = it.trim() },
                    label = { Text("Supabase Anon Key") },
                    placeholder = { Text("eyJhbGciOiJIUzI1NiIsIn...") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TealPrimary),
                    modifier = Modifier.fillMaxWidth().testTag("supabase_key_input")
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Status message & SQL Quick copy
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Sync Status: $supabaseSyncStatus",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (supabaseSyncStatus.startsWith("Success") || supabaseSyncStatus.contains("synced")) TealPrimary 
                                    else if (supabaseSyncStatus.startsWith("Error") || supabaseSyncStatus.startsWith("Sync failed")) MaterialTheme.colorScheme.error
                                    else TextDarkSecondary
                        )
                        testResultMsg?.let { msg ->
                            Text(
                                text = msg,
                                fontSize = 11.sp,
                                color = if (msg.contains("Successful")) TealPrimary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    TextButton(
                        onClick = { showSqlDialog = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = TealPrimary),
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Show SQL Helper",
                            modifier = Modifier.size(16.dp).padding(end = 4.dp)
                        )
                        Text("Setup SQL", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Actions Layout
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Test Button
                    OutlinedButton(
                        onClick = {
                            testResultMsg = "Testing connection..."
                            viewModel.testSupabaseConnection(editUrl, editKey) { success ->
                                testResultMsg = if (success) "Connection Successful!" else "Connection Failed. Check URL and Key."
                            }
                        },
                        border = BorderStroke(1.dp, BorderLight),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Test API", fontWeight = FontWeight.Bold, color = TextDarkSecondary)
                    }

                    // Save Config & Sync Button
                    Button(
                        onClick = {
                            viewModel.saveSupabaseConfig(editUrl, editKey, tempUseSupabase)
                            controller?.hide()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = TealPrimary),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1.2f)
                    ) {
                        Text("Apply & Sync", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Supabase Dialog Setup Helper
        if (showSqlDialog) {
            AlertDialog(
                onDismissRequest = { showSqlDialog = false },
                title = { Text("Supabase Table Setup", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text(
                            text = "Paste this SQL in your Supabase SQL Editor to configure the appointments table on your postgres database:",
                            fontSize = 13.sp,
                            color = TextDarkPrimary
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(BorderLight)
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = "CREATE TABLE appointments (\n  id bigint primary key generated always as identity,\n  doctorId integer,\n  doctorName text,\n  doctorSpecialty text,\n  clinicName text,\n  consultingFee text,\n  date text,\n  timeSlot text,\n  consultationType text,\n  symptoms text,\n  paymentId text,\n  timestamp bigint\n);",
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = TextDarkPrimary
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showSqlDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = TealPrimary)
                    ) {
                        Text("Done")
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Preferences preferences switches
        Text(
            text = "Preferences",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = TextDarkPrimary
        )

        Spacer(modifier = Modifier.height(10.dp))

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, BorderLight),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // Row Reminders switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Appointment reminders", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextDarkPrimary)
                    Switch(
                        checked = enableReminders,
                        onCheckedChange = { enableReminders = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = TealPrimary, checkedTrackColor = TealContainerLight)
                    )
                }

                HorizontalDivider(color = BorderLight, modifier = Modifier.padding(vertical = 10.dp))

                // Row biometric switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Secure Biometric login", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextDarkPrimary)
                    Switch(
                        checked = biometricLock,
                        onCheckedChange = { biometricLock = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = TealPrimary, checkedTrackColor = TealContainerLight)
                    )
                }

                HorizontalDivider(color = BorderLight, modifier = Modifier.padding(vertical = 10.dp))

                // Row health synctrack switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Integrate System Health details", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextDarkPrimary)
                    Switch(
                        checked = syncHealthData,
                        onCheckedChange = { syncHealthData = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = TealPrimary, checkedTrackColor = TealContainerLight)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Switch Portal / logout button
        Button(
            onClick = { viewModel.logout() },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFFECEF), // Elegant soft rose bg
                contentColor = Color(0xFFE11D48)    // Crimson-rose text/icon color
            ),
            border = BorderStroke(1.dp, Color(0xFFFECDD3)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("patient_logout_button")
        ) {
            Icon(
                imageVector = Icons.Default.ExitToApp,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = Color(0xFFE11D48)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Log Out / Switch Portal", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Clear App Database buttons
        OutlinedButton(
            onClick = { viewModel.clearAll() },
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Clear Bookings & Database", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookingModalOverlay(
    doctor: Doctor,
    viewModel: MediBookViewModel,
    onDismiss: () -> Unit
) {
    val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
    val selectedTimeSlot by viewModel.selectedTimeSlot.collectAsStateWithLifecycle()
    val consultationType by viewModel.consultationType.collectAsStateWithLifecycle()
    val symptoms by viewModel.patientSymptoms.collectAsStateWithLifecycle()
    val bookingSuccess by viewModel.bookingSuccess.collectAsStateWithLifecycle()
    val paymentError by viewModel.paymentError.collectAsStateWithLifecycle()
    val paymentSuccessId by viewModel.paymentSuccessId.collectAsStateWithLifecycle()

    val bookingPhone by viewModel.bookingPhone.collectAsStateWithLifecycle()
    val otpSent by viewModel.otpSent.collectAsStateWithLifecycle()
    val otpCode by viewModel.otpCode.collectAsStateWithLifecycle()
    val otpVerificationError by viewModel.otpVerificationError.collectAsStateWithLifecycle()
    val isPhoneVerified by viewModel.isPhoneVerified.collectAsStateWithLifecycle()

    val availableDatesList = listOf("Tue, Jun 2", "Wed, Jun 3", "Thu, Jun 4", "Fri, Jun 5", "Sat, Jun 6", "Mon, Jun 8", "Tue, Jun 9", "Wed, Jun 10", "Thu, Jun 11", "Fri, Jun 12", "Sat, Jun 13")
    val scrollState = rememberScrollState()

    // Full Backdrop transparent layer
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable { if (!bookingSuccess) onDismiss() }
            .testTag("booking_overlay_backdrop")
    ) {
        // Active Content sheet at the bottom center
        Card(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .align(Alignment.BottomCenter)
                .clickable(enabled = false) { }
                .testTag("booking_sheet_container")
        ) {
            if (bookingSuccess) {
                // Success Transaction details UI block
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(TealContainerLight),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Success confirmation Icon",
                            tint = TealPrimary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Booking Confirmed!",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDarkPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Your appointment has been securely registered in the clinic system.",
                        fontSize = 14.sp,
                        color = TextDarkSecondary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Booking ticket card outline
                    Card(
                        border = BorderStroke(1.dp, BorderLight),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(doctor.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextDarkPrimary)
                            Text("${doctor.specialty} • ${doctor.clinic}", fontSize = 12.sp, color = TextDarkSecondary, modifier = Modifier.padding(top = 2.dp))
                            HorizontalDivider(color = BorderLight, modifier = Modifier.padding(vertical = 12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("DATE", fontSize = 10.sp, color = TextDarkSecondary, fontWeight = FontWeight.Bold)
                                    Text(selectedDate, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TealPrimary, modifier = Modifier.padding(top = 2.dp))
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("TIME", fontSize = 10.sp, color = TextDarkSecondary, fontWeight = FontWeight.Bold)
                                    Text(selectedTimeSlot, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TealPrimary, modifier = Modifier.padding(top = 2.dp))
                                }
                            }
                            if (paymentSuccessId.isNotEmpty()) {
                                HorizontalDivider(color = BorderLight, modifier = Modifier.padding(vertical = 12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("TRANSACTION ID", fontSize = 10.sp, color = TextDarkSecondary, fontWeight = FontWeight.Bold)
                                        Text(paymentSuccessId, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = TextDarkPrimary, modifier = Modifier.padding(top = 2.dp))
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("STATUS", fontSize = 10.sp, color = TextDarkSecondary, fontWeight = FontWeight.Bold)
                                        Text("PAID VIA RZP", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF10B981), modifier = Modifier.padding(top = 2.dp))
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(30.dp))

                    Button(
                        onClick = {
                            viewModel.setTab("Bookings")
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = TealPrimary),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("View My Bookings", fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    TextButton(
                        onClick = onDismiss
                    ) {
                        Text("Dismiss", color = TextDarkSecondary)
                    }
                }
            } else {
                // Scheduling Inputs form sheet layout
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // Title Bar with Back Arrow
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Text(
                            text = "Book Appointment",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextDarkPrimary,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Scrollable sheet content body
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(scrollState)
                    ) {
                        // Master doctor credentials card inside
                        Card(
                            border = BorderStroke(1.dp, BorderLight),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(Color(doctor.avatarColor)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = doctor.name.split(" ").filter { it.isNotEmpty() && !it.contains(".") }.take(2).map { it.first() }.joinToString(""),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(doctor.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextDarkPrimary)
                                    Text("${doctor.specialty} • ${doctor.clinic}", fontSize = 11.sp, color = TextDarkSecondary)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Selection selector header for Date
                        Text("Select Inspection Date", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextDarkPrimary)

                        Spacer(modifier = Modifier.height(8.dp))

                        // Horizontal Scroll calendar chips row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            availableDatesList.forEach { date ->
                                val isActive = date == selectedDate
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(1.dp, if (isActive) TealPrimary else BorderLight, RoundedCornerShape(8.dp))
                                        .background(if (isActive) TealContainerLight else MaterialTheme.colorScheme.surface)
                                        .clickable { viewModel.setBookingDate(date) }
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = date,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isActive) TealPrimary else TextDarkPrimary
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Selection selector header for Time slot
                        Text("Select Inspection Time", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextDarkPrimary)

                        Spacer(modifier = Modifier.height(8.dp))

                        // Grid containing doctor available times
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            doctor.availableSlots.forEach { slot ->
                                val isActive = slot == selectedTimeSlot
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(1.dp, if (isActive) TealPrimary else BorderLight, RoundedCornerShape(8.dp))
                                        .background(if (isActive) TealContainerLight else MaterialTheme.colorScheme.surface)
                                        .clickable { viewModel.setBookingTimeSlot(slot) }
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = slot,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isActive) TealPrimary else TextDarkPrimary
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Selection section for consultation models
                        Text("Consultation Mode", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextDarkPrimary)

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val modes = listOf(
                                "In-Clinic Visit" to Icons.Default.LocationOn,
                                "Video Consultation" to Icons.Default.Videocam
                            )
                            modes.forEach { (mode, icon) ->
                                val isActive = mode == consultationType
                                Card(
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, if (isActive) TealPrimary else BorderLight),
                                    colors = CardDefaults.cardColors(containerColor = if (isActive) TealContainerLight else MaterialTheme.colorScheme.surface),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { viewModel.setConsultationType(mode) }
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            tint = if (isActive) TealPrimary else TextDarkSecondary
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = mode,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isActive) TealPrimary else TextDarkPrimary
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Input description field for conditions
                        Text("Patient Condition & Symptoms", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextDarkPrimary)

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = symptoms,
                            onValueChange = { viewModel.setSymptoms(it) },
                            placeholder = { Text("Write symptoms here (e.g., headache, muscle cramps)...") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(95.dp)
                                .testTag("symptoms_input_field"),
                            maxLines = 3,
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TealPrimary)
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // Phone verification section with OTP
                        Text("Mobile Verification (Required for Booking)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextDarkPrimary)
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isPhoneVerified) Color(0xFFF0FDF4) else MaterialTheme.colorScheme.surface
                            ),
                            border = BorderStroke(1.dp, if (isPhoneVerified) Color(0xFF86EFAC) else BorderLight),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().testTag("phone_otp_card")
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                if (isPhoneVerified) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Verified",
                                            tint = Color(0xFF15803D),
                                            modifier = Modifier.size(22.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text("Phone Number Verified", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF15803D))
                                            Text(bookingPhone, fontSize = 11.sp, color = Color(0xFF166534))
                                        }
                                        Spacer(modifier = Modifier.weight(1f))
                                        TextButton(onClick = { viewModel.resetPhoneVerification() }) {
                                            Text("Change", color = TealPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                    }
                                } else {
                                    Text(
                                        text = "Please verify your active mobile number. Click 'Get OTP' to generate a simulated security passcode instantly via app notifications.",
                                        fontSize = 11.sp,
                                        color = TextDarkSecondary,
                                        modifier = Modifier.padding(bottom = 12.dp)
                                    )
                                    
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        OutlinedTextField(
                                            value = bookingPhone,
                                            onValueChange = { viewModel.setBookingPhone(it) },
                                            placeholder = { Text("Enter 10 digit number") },
                                            singleLine = true,
                                            enabled = !otpSent,
                                            shape = RoundedCornerShape(8.dp),
                                            leadingIcon = {
                                                Icon(imageVector = Icons.Default.Smartphone, contentDescription = null, tint = TextDarkSecondary, modifier = Modifier.size(18.dp))
                                            },
                                            label = { Text("Mobile Number", fontSize = 11.sp) },
                                            modifier = Modifier.weight(1.4f).testTag("phone_input_field"),
                                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TealPrimary)
                                        )
                                        
                                        Button(
                                            onClick = { viewModel.sendOtp() },
                                            colors = ButtonDefaults.buttonColors(containerColor = TealPrimary),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(0.9f).height(56.dp).testTag("send_otp_button"),
                                            contentPadding = PaddingValues(horizontal = 4.dp)
                                        ) {
                                            Text(if (otpSent) "Resend OTP" else "Get OTP", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                        }
                                    }
                                    
                                    if (otpSent) {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        HorizontalDivider(color = BorderLight, thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "Enter Code Sent to your Phone",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextDarkPrimary
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            OutlinedTextField(
                                                value = otpCode,
                                                onValueChange = { viewModel.setOtpCode(it) },
                                                placeholder = { Text("e.g. 1234") },
                                                singleLine = true,
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.weight(1.4f).testTag("otp_input_field"),
                                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = TealPrimary)
                                            )
                                            Button(
                                                onClick = { viewModel.verifyOtp() },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.weight(0.9f).height(56.dp).testTag("verify_otp_button")
                                            ) {
                                                Text("Verify OTP", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                            }
                                        }
                                    }
                                    
                                    otpVerificationError?.let { err ->
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(err, color = Color(0xFFD32F2F), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Razorpay Payment Method Selector
                        Text("Select Card or UPI Payment Mode", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextDarkPrimary)
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        val selectedPaymentMethod by viewModel.selectedPaymentMethod.collectAsStateWithLifecycle()
                        
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val paymentMethods = listOf(
                                Triple("any", "Standard", Icons.Default.Payments),
                                Triple("upi", "UPI Pay", Icons.Default.Smartphone),
                                Triple("card", "Card Pay", Icons.Default.CreditCard)
                            )
                            paymentMethods.forEach { (methodId, label, icon) ->
                                val isActive = selectedPaymentMethod == methodId
                                Card(
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, if (isActive) TealPrimary else BorderLight),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isActive) TealContainerLight else MaterialTheme.colorScheme.surface
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { viewModel.setSelectedPaymentMethod(methodId) }
                                        .testTag("pay_method_$methodId")
                                ) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = label,
                                            tint = if (isActive) TealPrimary else TextDarkSecondary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = label,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isActive) TealPrimary else TextDarkPrimary
                                        )
                                    }
                                }
                            }
                        }

                        if (selectedPaymentMethod == "upi") {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "💡 Prefilled with test VPA 'success@razorpay' to bypass emulator setup and pay instantly.",
                                fontSize = 12.sp,
                                color = TealPrimary,
                                fontWeight = FontWeight.Medium
                            )
                        } else if (selectedPaymentMethod == "card") {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "💳 Load standard Razorpay card form instantly. Enter dummy details to pay.",
                                fontSize = 12.sp,
                                color = TextDarkSecondary,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                    }

                    paymentError?.let { error ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = "Warning",
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = error,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    // Bottom confirm command rows
                    HorizontalDivider(color = BorderLight, modifier = Modifier.padding(bottom = 12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Pre-booking Charge", fontSize = 11.sp, color = TextDarkSecondary)
                            Text(doctor.fee, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TealSecondary)
                        }
                        Button(
                            onClick = {
                                if (isPhoneVerified) {
                                    viewModel.confirmAppointment()
                                } else {
                                    viewModel.sendOtp()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isPhoneVerified) TealPrimary else Color(0xFF64748B)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                            modifier = Modifier.testTag("confirm_booking_button")
                        ) {
                            Text(
                                text = if (isPhoneVerified) "Confirm & Register" else "Verify Phone First",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PatientsTable(
    patients: List<com.example.data.Patient>,
    appointments: List<com.example.data.Appointment>,
    modifier: Modifier = Modifier
) {
    var selectedPatientForDetail by remember { mutableStateOf<com.example.data.Patient?>(null) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, BorderLight),
        modifier = modifier.fillMaxWidth().testTag("patients_table_card")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Table Header Title
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.AccountBox,
                    contentDescription = null,
                    tint = TealPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Patient Registry Table",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDarkPrimary
                )
            }

            HorizontalDivider(color = BorderLight, thickness = 1.dp, modifier = Modifier.padding(vertical = 6.dp))

            // Grid Headers
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TealContainerLight, RoundedCornerShape(6.dp))
                    .padding(vertical = 8.dp, horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("No.", modifier = Modifier.weight(0.7f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TealSecondary)
                Text("Name (Click to view)", modifier = Modifier.weight(1.5f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TealSecondary)
                Text("Age", modifier = Modifier.weight(0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TealSecondary, textAlign = TextAlign.Center)
                Text("Sex", modifier = Modifier.weight(0.7f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TealSecondary, textAlign = TextAlign.Center)
                Text("Mobile", modifier = Modifier.weight(1.2f), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TealSecondary)
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (patients.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No registered patients found.", fontSize = 12.sp, color = TextDarkSecondary)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(patients) { patient ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selectedPatientForDetail = patient }
                                .padding(vertical = 10.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = patient.uniqueNumber,
                                modifier = Modifier.weight(0.7f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextDarkPrimary
                            )
                            Text(
                                text = patient.name,
                                modifier = Modifier.weight(1.5f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = TealPrimary, // Highlight clinic-cyan link style to signal clickability
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = patient.age,
                                modifier = Modifier.weight(0.6f),
                                fontSize = 12.sp,
                                color = TextDarkPrimary,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = patient.sex,
                                modifier = Modifier.weight(0.7f),
                                fontSize = 12.sp,
                                color = TextDarkPrimary,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = patient.mobile,
                                modifier = Modifier.weight(1.2f),
                                fontSize = 11.sp,
                                color = TextDarkSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        HorizontalDivider(color = BorderLight.copy(alpha = 0.5f), thickness = 0.5.dp)
                    }
                }
            }
        }
    }

    // Interactive Detail Modal
    selectedPatientForDetail?.let { patient ->
        PatientDetailDialog(
            patient = patient,
            appointments = appointments,
            onDismiss = { selectedPatientForDetail = null }
        )
    }
}

@Composable
fun PatientDetailDialog(
    patient: com.example.data.Patient,
    appointments: List<com.example.data.Appointment>,
    onDismiss: () -> Unit
) {
    val patientHistory = remember(appointments, patient) {
        appointments.filter { app ->
            app.patientName.equals(patient.name, ignoreCase = true) ||
            app.patientUniqueNo == patient.uniqueNumber
        }
    }

    val formattedJoinedDate = remember(patient.timestamp) {
        try {
            val sdf = java.text.SimpleDateFormat("MMM dd, yyyy 'at' hh:mm a", java.util.Locale.getDefault())
            sdf.format(java.util.Date(patient.timestamp))
        } catch (e: Exception) {
            "N/A"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(TealContainerLight),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = null,
                        tint = TealPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = patient.name,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextDarkPrimary
                    )
                    Text(
                        text = "Patient Registry ID: ${patient.uniqueNumber}",
                        fontSize = 11.sp,
                        color = TextDarkSecondary
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Bio / Info Summary Grid
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = SoftBg),
                    border = BorderStroke(1.dp, BorderLight),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Age", fontSize = 10.sp, color = TextDarkSecondary, fontWeight = FontWeight.SemiBold)
                                Text(patient.age, fontSize = 13.sp, color = TextDarkPrimary, fontWeight = FontWeight.Bold)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Sex", fontSize = 10.sp, color = TextDarkSecondary, fontWeight = FontWeight.SemiBold)
                                Text(patient.sex, fontSize = 13.sp, color = TextDarkPrimary, fontWeight = FontWeight.Bold)
                            }
                        }
                        HorizontalDivider(color = BorderLight.copy(alpha = 0.6f))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Contact Mobile", fontSize = 10.sp, color = TextDarkSecondary, fontWeight = FontWeight.SemiBold)
                                Text(patient.mobile, fontSize = 13.sp, color = TextDarkPrimary, fontWeight = FontWeight.Bold)
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Email Address", fontSize = 10.sp, color = TextDarkSecondary, fontWeight = FontWeight.SemiBold)
                                Text(patient.email, fontSize = 13.sp, color = TextDarkPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        HorizontalDivider(color = BorderLight.copy(alpha = 0.6f))
                        Column {
                            Text("Registration Date", fontSize = 10.sp, color = TextDarkSecondary, fontWeight = FontWeight.SemiBold)
                            Text(formattedJoinedDate, fontSize = 12.sp, color = TextDarkPrimary)
                        }
                    }
                }

                // Section Title: Appointment History
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Appointment History",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = TextDarkPrimary
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(TealContainerLight)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${patientHistory.size} total",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = TealSecondary
                        )
                    }
                }

                if (patientHistory.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No recorded clinical or video appointments for this patient.",
                            fontSize = 11.sp,
                            color = TextDarkSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                    ) {
                        items(patientHistory) { app ->
                            Card(
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(0.5.dp, BorderLight),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = app.doctorName,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = TextDarkPrimary
                                            )
                                            Text(
                                                text = "${app.doctorSpecialty} • ${app.consultationType}",
                                                fontSize = 10.sp,
                                                color = TextDarkSecondary
                                            )
                                        }
                                        StatusBadge(status = app.status)
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${app.date} • ${app.timeSlot}",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TealSecondary
                                        )
                                        Text(
                                            text = "Fee: ${app.consultingFee}",
                                            fontSize = 10.sp,
                                            color = TextDarkSecondary
                                        )
                                    }
                                    if (app.symptoms.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "Symptoms: ${app.symptoms}",
                                            fontSize = 9.sp,
                                            color = TextDarkSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = TealPrimary)
            ) {
                Text("Close", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
fun NotificationToastOverlay(
    viewModel: MediBookViewModel,
    modifier: Modifier = Modifier
) {
    val toasts by viewModel.notifications.collectAsStateWithLifecycle()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            toasts.forEach { toast ->
                key(toast.id) {
                    NotificationToastItem(
                        toast = toast,
                        onDismiss = { viewModel.dismissNotification(toast.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun NotificationToastItem(
    toast: com.example.ui.CustomToast,
    onDismiss: () -> Unit
) {
    val cardColor = if (toast.isRegistration) Color(0xFFE0F2F1) else Color(0xFFE0F7FA) // mint light for registration, clinic light sky-teal for appointment bookings
    val textColor = if (toast.isRegistration) Color(0xFF004D40) else Color(0xFF006064)
    val accentColor = if (toast.isRegistration) Color(0xFF0D9488) else Color(0xFF0284C7)
    val icon = if (toast.isRegistration) Icons.Default.PersonAdd else Icons.Default.NotificationsActive

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 450.dp)
            .animateContentSize()
            .testTag("notification_toast_${toast.id}")
            .clickable { onDismiss() }
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = toast.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = toast.message,
                    fontSize = 11.sp,
                    color = textColor.copy(alpha = 0.85f),
                    lineHeight = 15.sp
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = textColor.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
