package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PatientDao {
    @Query("SELECT * FROM patients ORDER BY id ASC")
    fun getAllPatients(): Flow<List<Patient>>

    @Query("SELECT * FROM patients ORDER BY id ASC")
    suspend fun getAllPatientsDirect(): List<Patient>

    @Query("SELECT COUNT(*) FROM patients")
    suspend fun getPatientCount(): Int

    @Query("SELECT * FROM patients WHERE name = :name AND mobile = :mobile LIMIT 1")
    suspend fun getPatientByNameAndMobile(name: String, mobile: String): Patient?

    @Query("SELECT * FROM patients WHERE name = :name AND age = :age AND sex = :sex LIMIT 1")
    suspend fun getPatientByNameAgeSex(name: String, age: String, sex: String): Patient?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPatient(patient: Patient): Long

    @Query("DELETE FROM patients")
    suspend fun clearAllPatients()
}
