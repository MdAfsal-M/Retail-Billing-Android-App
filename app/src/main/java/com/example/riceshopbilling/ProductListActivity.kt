package com.example.riceshopbilling

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*

class ProductListActivity : AppCompatActivity() {

    private lateinit var etName: EditText
    private lateinit var etPrice: EditText
    private lateinit var btnAdd: Button
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ProductAdapter
    private val products = mutableListOf<Product>()
    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_product_list)

        etName = findViewById(R.id.etProductName)
        etPrice = findViewById(R.id.etProductPrice)
        btnAdd = findViewById(R.id.btnAddProduct)
        recyclerView = findViewById(R.id.recyclerViewProducts)

        db = AppDatabase.getInstance(this)
        recyclerView.layoutManager = LinearLayoutManager(this)

        loadProducts()

        btnAdd.setOnClickListener {
            addProduct()
        }
    }

    private fun loadProducts() {
        CoroutineScope(Dispatchers.IO).launch {
            val productList = db.productDao().getAllProducts()
            withContext(Dispatchers.Main) {
                products.clear()
                products.addAll(productList)
                adapter = ProductAdapter(products) { product ->
                    deleteProduct(product)
                }
                recyclerView.adapter = adapter
            }
        }
    }

    private fun addProduct() {
        val name = etName.text.toString().trim()
        val priceStr = etPrice.text.toString().trim()
        if (name.isEmpty() || priceStr.isEmpty()) {
            Toast.makeText(this, "Enter name and price", Toast.LENGTH_SHORT).show()
            return
        }
        val price = priceStr.toDoubleOrNull()
        if (price == null || price <= 0) {
            Toast.makeText(this, "Invalid price", Toast.LENGTH_SHORT).show()
            return
        }

        val product = Product(name = name, price = price)
        CoroutineScope(Dispatchers.IO).launch {
            db.productDao().insertProduct(product)
            withContext(Dispatchers.Main) {
                etName.text.clear()
                etPrice.text.clear()
                loadProducts()
                Toast.makeText(this@ProductListActivity, "Product added", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteProduct(product: Product) {
        CoroutineScope(Dispatchers.IO).launch {
            db.productDao().deleteProduct(product)
            withContext(Dispatchers.Main) {
                loadProducts()
                Toast.makeText(this@ProductListActivity, "Product deleted", Toast.LENGTH_SHORT).show()
            }
        }
    }
}