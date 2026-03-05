package com.example.riceshopbilling

data class BillItem(
    val productName: String,
    val quantity: Double,          // total kg
    val unitPrice: Double,         // price per kg
    val amount: Double,            // total price
    val unit: String,              // "kg" or "bag"
    val bagSize: Double? = null,   // size of one bag in kg (if bag)
    val bagPrice: Double? = null   // price per bag (if bag)
)