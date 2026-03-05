package com.example.riceshopbilling

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface BillDao {
    @Insert
    suspend fun insertBill(bill: Bill)

    @Query("SELECT * FROM bills ORDER BY date DESC")
    suspend fun getAllBills(): List<Bill>
}