package com.example.data

import kotlinx.coroutines.flow.Flow

class AppointmentRepository(
    private val appointmentDao: AppointmentDao,
    private val patientDao: PatientDao
) {
    val allAppointments: Flow<List<Appointment>> = appointmentDao.getAllAppointments()
    val allPatients: Flow<List<Patient>> = patientDao.getAllPatients()

    suspend fun insert(appointment: Appointment) {
        appointmentDao.insertAppointment(appointment)
    }

    suspend fun getAppointmentsDirect(): List<Appointment> {
        return appointmentDao.getAppointmentsDirect()
    }

    suspend fun delete(id: Int) {
        appointmentDao.deleteAppointment(id)
    }

    suspend fun clear() {
        appointmentDao.clearAllAppointments()
    }

    // Patient repository methods
    suspend fun insertPatient(patient: Patient): Long {
        return patientDao.insertPatient(patient)
    }

    suspend fun getAllPatientsDirect(): List<Patient> {
        return patientDao.getAllPatientsDirect()
    }

    suspend fun getPatientCount(): Int {
        return patientDao.getPatientCount()
    }

    suspend fun getPatientByNameAndMobile(name: String, mobile: String): Patient? {
        return patientDao.getPatientByNameAndMobile(name, mobile)
    }

    suspend fun getPatientByNameAgeSex(name: String, age: String, sex: String): Patient? {
        return patientDao.getPatientByNameAgeSex(name, age, sex)
    }

    suspend fun clearAllPatients() {
        patientDao.clearAllPatients()
    }
}
