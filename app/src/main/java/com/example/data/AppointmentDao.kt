package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppointmentDao {
    @Query("SELECT * FROM appointments ORDER BY timestamp DESC")
    fun getAllAppointments(): Flow<List<Appointment>>

    @Query("SELECT * FROM appointments ORDER BY timestamp DESC")
    suspend fun getAppointmentsDirect(): List<Appointment>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAppointment(appointment: Appointment)

    @Query("DELETE FROM appointments WHERE id = :id")
    suspend fun deleteAppointment(id: Int)

    @Query("DELETE FROM appointments")
    suspend fun clearAllAppointments()
}
