package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "appointments")
data class Appointment(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val doctorId: Int,
    val doctorName: String,
    val doctorSpecialty: String,
    val clinicName: String,
    val consultingFee: String,
    val date: String,             // e.g., "Mon, Jun 2" or "2026-06-02"
    val timeSlot: String,         // e.g., "10:00 AM"
    val consultationType: String, // e.g., "In-Clinic Visit" or "Video Consultation"
    val symptoms: String = "",
    val paymentId: String = "",
    val status: String = "Confirmed", // "Pending", "Confirmed", "Completed", "Cancelled"
    val patientName: String = "Alex Kim",
    val patientUniqueNo: String = "0001",
    val patientAge: String = "25",
    val patientSex: String = "Male",
    val patientMobile: String = "N/A",
    val patientEmail: String = "N/A",
    val timestamp: Long = System.currentTimeMillis()
)
