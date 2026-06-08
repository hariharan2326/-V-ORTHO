package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "patients")
data class Patient(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val uniqueNumber: String, // e.g., "0001", "0002"
    val name: String,
    val age: String,
    val sex: String,
    val mobile: String,
    val email: String,
    val timestamp: Long = System.currentTimeMillis()
)
