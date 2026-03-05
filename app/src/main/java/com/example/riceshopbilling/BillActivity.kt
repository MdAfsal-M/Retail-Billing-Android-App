package com.example.riceshopbilling

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*

class BillActivity : AppCompatActivity() {

    private lateinit var sharedPref: SharedPreferences
    private lateinit var productGrid: RecyclerView
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvSubtotal: TextView
    private lateinit var etTaxPercent: EditText
    private lateinit var etDiscount: EditText
    private lateinit var tvGrandTotal: TextView
    private lateinit var tvDate: TextView
    private lateinit var btnPrint: Button
    private lateinit var btnPreview: Button
    private lateinit var etSearch: EditText   // Added

    private val billItems = mutableListOf<BillItem>()
    private lateinit var billAdapter: BillAdapter
    private var subtotal = 0.0
    private var taxPercent = 0.0
    private var discount = 0.0

    private lateinit var db: AppDatabase
    private lateinit var products: List<Product>
    private var allProducts: List<Product> = listOf()   // Full list

    private val REQUEST_BLUETOOTH_PERMISSION = 100
    private val bagSizes = arrayOf(1, 2, 5, 10, 26, 30, 50)  // Added 26 kg

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPref = getSharedPreferences("printer_settings", Context.MODE_PRIVATE)

        initViews()
        setupDate()
        setupRecyclerView()
        setupTaxDiscountListeners()

        db = AppDatabase.getInstance(this)
        loadProducts()

        btnPrint.setOnClickListener { showPrintOptions() }
        btnPreview.setOnClickListener { showBillPreview() }

        requestBluetoothPermissions()
    }

    override fun onResume() {
        super.onResume()
        loadProducts()
    }

    private fun initViews() {
        productGrid = findViewById(R.id.productGrid)
        recyclerView = findViewById(R.id.recyclerView)
        tvSubtotal = findViewById(R.id.tvSubtotal)
        etTaxPercent = findViewById(R.id.etTaxPercent)
        etDiscount = findViewById(R.id.etDiscount)
        tvGrandTotal = findViewById(R.id.tvGrandTotal)
        tvDate = findViewById(R.id.tvDate)
        btnPrint = findViewById(R.id.btnPrint)
        btnPreview = findViewById(R.id.btnPreview)
        etSearch = findViewById(R.id.etSearch)   // Initialize

        productGrid.layoutManager = GridLayoutManager(this, 2)
    }

    private fun setupDate() {
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        tvDate.text = "Date: $date"
    }

    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(this)
        billAdapter = BillAdapter(billItems) { position ->
            billItems.removeAt(position)
            billAdapter.notifyItemRemoved(position)
            calculateTotals()
        }
        recyclerView.adapter = billAdapter
    }

    private fun setupTaxDiscountListeners() {
        val listener = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                calculateGrandTotal()
            }
        }
        etTaxPercent.addTextChangedListener(listener)
        etDiscount.addTextChangedListener(listener)
    }

    private fun loadProducts() {
        CoroutineScope(Dispatchers.IO).launch {
            var productList = db.productDao().getAllProducts()
            if (productList.isEmpty()) {
                db.productDao().insertProduct(Product(name = "Basmati Rice", price = 80.0))
                db.productDao().insertProduct(Product(name = "Ponni Rice", price = 50.0))
                db.productDao().insertProduct(Product(name = "Idly Rice", price = 40.0))
                productList = db.productDao().getAllProducts()
            }
            withContext(Dispatchers.Main) {
                allProducts = productList
                products = productList
                setupProductGrid()

                // Add search listener
                etSearch.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        filterProducts(s.toString())
                    }
                })
            }
        }
    }

    private fun filterProducts(query: String) {
        val filtered = if (query.isEmpty()) {
            allProducts
        } else {
            allProducts.filter { it.name.contains(query, ignoreCase = true) }
        }
        products = filtered
        // Refresh the grid adapter
        val adapter = ProductGridAdapter(products) { product ->
            showProductDialog(product)
        }
        productGrid.adapter = adapter
    }

    private fun setupProductGrid() {
        val adapter = ProductGridAdapter(products) { product ->
            showProductDialog(product)
        }
        productGrid.adapter = adapter
    }

    private fun showProductDialog(product: Product) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_product_input, null)
        val tvProductName = dialogView.findViewById<TextView>(R.id.tvProductName)
        val radioGroup = dialogView.findViewById<RadioGroup>(R.id.radioGroupUnit)
        val radioKg = dialogView.findViewById<RadioButton>(R.id.radioKg)
        val radioBag = dialogView.findViewById<RadioButton>(R.id.radioBag)
        val layoutKg = dialogView.findViewById<LinearLayout>(R.id.layoutKg)
        val layoutBag = dialogView.findViewById<LinearLayout>(R.id.layoutBag)
        val etKgQuantity = dialogView.findViewById<EditText>(R.id.etKgQuantity)
        val etBagCount = dialogView.findViewById<EditText>(R.id.etBagCount)
        val spinnerBagSize = dialogView.findViewById<Spinner>(R.id.spinnerBagSize)
        val tvPriceLabel = dialogView.findViewById<TextView>(R.id.tvPriceLabel)
        val etPrice = dialogView.findViewById<EditText>(R.id.etPrice)
        val tvCalculatedAmount = dialogView.findViewById<TextView>(R.id.tvCalculatedAmount)
        val btnAdd = dialogView.findViewById<Button>(R.id.btnAddToBill)

        tvProductName.text = product.name

        // Setup bag size spinner
        val bagSizeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, bagSizes.map { "$it kg" })
        bagSizeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerBagSize.adapter = bagSizeAdapter

        // Handle unit change
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.radioKg -> {
                    layoutKg.visibility = View.VISIBLE
                    layoutBag.visibility = View.GONE
                    tvPriceLabel.text = "Price per kg (₹)"
                }
                R.id.radioBag -> {
                    layoutKg.visibility = View.GONE
                    layoutBag.visibility = View.VISIBLE
                    tvPriceLabel.text = "Price per bag (₹)"
                }
            }
            updateCalculatedAmount(dialogView)
        }

        // Listeners for input changes
        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateCalculatedAmount(dialogView)
            }
        }
        etKgQuantity.addTextChangedListener(textWatcher)
        etBagCount.addTextChangedListener(textWatcher)
        etPrice.addTextChangedListener(textWatcher)
        spinnerBagSize.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateCalculatedAmount(dialogView)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        btnAdd.setOnClickListener {
            val unit = if (radioKg.isChecked) "kg" else "bag"
            val priceInput = etPrice.text.toString().toDoubleOrNull()
            if (priceInput == null || priceInput <= 0) {
                Toast.makeText(this, "Invalid price", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            var quantityKg = 0.0
            var unitPrice = 0.0
            var bagSize: Double? = null
            var bagPrice: Double? = null

            if (unit == "kg") {
                val qty = etKgQuantity.text.toString().toDoubleOrNull()
                if (qty == null || qty <= 0) {
                    Toast.makeText(this, "Enter valid quantity", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                quantityKg = qty
                unitPrice = priceInput
            } else {
                val bags = etBagCount.text.toString().toIntOrNull()
                if (bags == null || bags <= 0) {
                    Toast.makeText(this, "Enter valid number of bags", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val size = bagSizes[spinnerBagSize.selectedItemPosition]
                bagSize = size.toDouble()
                bagPrice = priceInput
                quantityKg = bags * bagSize
                unitPrice = bagPrice / bagSize
            }

            val amount = quantityKg * unitPrice
            val billItem = BillItem(
                productName = product.name,
                quantity = quantityKg,
                unitPrice = unitPrice,
                amount = amount,
                unit = unit,
                bagSize = bagSize,
                bagPrice = bagPrice
            )

            billItems.add(billItem)
            billAdapter.notifyItemInserted(billItems.size - 1)
            calculateTotals()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun updateCalculatedAmount(dialogView: View) {
        val radioKg = dialogView.findViewById<RadioButton>(R.id.radioKg)
        val etKgQuantity = dialogView.findViewById<EditText>(R.id.etKgQuantity)
        val etBagCount = dialogView.findViewById<EditText>(R.id.etBagCount)
        val etPrice = dialogView.findViewById<EditText>(R.id.etPrice)
        val tvCalculatedAmount = dialogView.findViewById<TextView>(R.id.tvCalculatedAmount)

        val price = etPrice.text.toString().toDoubleOrNull() ?: 0.0
        var total = 0.0

        if (radioKg.isChecked) {
            val qty = etKgQuantity.text.toString().toDoubleOrNull() ?: 0.0
            total = qty * price
        } else {
            val bags = etBagCount.text.toString().toIntOrNull() ?: 0
            total = bags * price
        }

        tvCalculatedAmount.text = "₹%.2f".format(total)
    }

    private fun calculateTotals() {
        subtotal = billItems.sumOf { it.amount }
        tvSubtotal.text = "₹%.2f".format(subtotal)
        calculateGrandTotal()
    }

    private fun calculateGrandTotal() {
        val taxStr = etTaxPercent.text.toString()
        val discountStr = etDiscount.text.toString()
        taxPercent = taxStr.toDoubleOrNull() ?: 0.0
        discount = discountStr.toDoubleOrNull() ?: 0.0

        val taxAmount = subtotal * taxPercent / 100
        val grandTotal = subtotal + taxAmount - discount
        tvGrandTotal.text = "₹%.2f".format(grandTotal)
    }

    private fun showPrintOptions() {
        if (billItems.isEmpty()) {
            Toast.makeText(this, "No items to print", Toast.LENGTH_SHORT).show()
            return
        }
        val options = arrayOf("Bluetooth", "WiFi")
        AlertDialog.Builder(this)
            .setTitle("Select Printer Type")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> printViaBluetooth()
                    1 -> printViaWiFi()
                }
            }
            .show()
    }

    private fun getCopiesCount(): Int {
        val copiesStr = sharedPref.getString("copies", "1") ?: "1"
        return copiesStr.toIntOrNull() ?: 1
    }

    private fun printViaBluetooth() {
        val mac = sharedPref.getString("bluetooth_mac", "") ?: ""
        if (mac.isEmpty()) {
            Toast.makeText(this, "No Bluetooth printer selected. Go to Printer Settings.", Toast.LENGTH_LONG).show()
            return
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT),
                    REQUEST_BLUETOOTH_PERMISSION
                )
                return
            }
        }

        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show()
            return
        }

        val device: BluetoothDevice? = bluetoothAdapter.getRemoteDevice(mac)
        if (device == null) {
            Toast.makeText(this, "Printer not found. Check settings.", Toast.LENGTH_SHORT).show()
            return
        }

        val copies = getCopiesCount()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket: BluetoothSocket = device.createRfcommSocketToServiceRecord(
                    UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                )
                socket.connect()
                val outputStream = socket.outputStream
                val billText = buildBillText()
                for (i in 1..copies) {
                    sendEscPosCommands(outputStream, billText)
                    if (i < copies) delay(500)
                }
                outputStream.close()
                socket.close()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@BillActivity, "Bill printed ($copies copies)", Toast.LENGTH_SHORT).show()
                }
            } catch (e: IOException) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@BillActivity, "Print failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun printViaWiFi() {
        val ip = sharedPref.getString("wifi_ip", "") ?: ""
        val portStr = sharedPref.getString("wifi_port", "9100") ?: "9100"
        if (ip.isEmpty()) {
            Toast.makeText(this, "No WiFi printer IP set. Go to Printer Settings.", Toast.LENGTH_LONG).show()
            return
        }
        val port = portStr.toIntOrNull() ?: 9100
        val copies = getCopiesCount()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val socket = Socket(ip, port)
                val outputStream = socket.getOutputStream()
                val billText = buildBillText()
                for (i in 1..copies) {
                    sendEscPosCommands(outputStream, billText)
                    if (i < copies) delay(500)
                }
                outputStream.close()
                socket.close()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@BillActivity, "Bill printed ($copies copies)", Toast.LENGTH_SHORT).show()
                }
            } catch (e: IOException) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@BillActivity, "WiFi print failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showBillPreview() {
        if (billItems.isEmpty()) {
            Toast.makeText(this, "No items to preview", Toast.LENGTH_SHORT).show()
            return
        }
        val billText = buildBillText()
        AlertDialog.Builder(this)
            .setTitle("Bill Preview")
            .setMessage(billText)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun buildBillText(): String {
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        val sb = StringBuilder()
        sb.appendLine("      Gani Anbu Store")
        sb.appendLine("         Dindigul")
        sb.appendLine("Phone: 9842125936,8870430799")
        sb.appendLine("----------------------")
        sb.appendLine("Date: $date")
        sb.appendLine("----------------------")
        sb.appendLine(String.format("%-16s %8s %8s %8s", "Item", "Qty", "Price", "Amount"))
        sb.appendLine("----------------------")
        for (item in billItems) {
            val itemName = item.productName.take(14)
            var qtyStr = ""
            var priceToShow = 0.0
            if (item.unit == "kg") {
                qtyStr = "%.2f kg".format(item.quantity)
                priceToShow = item.unitPrice
            } else {
                val bags = (item.quantity / (item.bagSize ?: 1.0)).toInt()
                qtyStr = "$bags bags"
                priceToShow = item.bagPrice ?: (item.unitPrice * (item.bagSize ?: 1.0))
            }
            sb.appendLine(String.format("%-16s %8s %8.2f %8.2f",
                itemName,
                qtyStr,
                priceToShow,
                item.amount))
        }
        sb.appendLine("----------------------")
        sb.appendLine(String.format("%-24s %8.2f", "Subtotal:", subtotal))
        val taxAmount = subtotal * taxPercent / 100
        sb.appendLine(String.format("%-24s %8.2f", "Tax (${taxPercent}%):", taxAmount))
        sb.appendLine(String.format("%-24s %8.2f", "Discount:", discount))
        sb.appendLine("----------------------")
        val grandTotal = subtotal + taxAmount - discount
        sb.appendLine(String.format("%-24s %8.2f", "Grand Total:", grandTotal))
        sb.appendLine("----------------------")
        sb.appendLine("  Thank you, visit again!")
        sb.appendLine("\n\n\n")
        return sb.toString()
    }

    private fun sendEscPosCommands(outputStream: OutputStream, text: String) {
        try {
            outputStream.write(byteArrayOf(0x1B, 0x40)) // ESC @
            outputStream.write(byteArrayOf(0x1B, 0x61, 0x01)) // Center align
            outputStream.write(text.toByteArray(Charsets.UTF_8))
            outputStream.write(byteArrayOf(0x1D, 0x56, 0x01)) // Cut paper
            outputStream.flush()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun requestBluetoothPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT),
                    REQUEST_BLUETOOTH_PERMISSION
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Bluetooth permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Bluetooth permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

// BillAdapter (unchanged)
class BillAdapter(
    private val items: MutableList<BillItem>,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<BillAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvProductName: TextView = itemView.findViewById(R.id.tvProductName)
        val tvQuantity: TextView = itemView.findViewById(R.id.tvQuantity)
        val tvPrice: TextView = itemView.findViewById(R.id.tvPrice)
        val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bill_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvProductName.text = item.productName
        val qtyStr = if (item.unit == "kg") {
            "%.2f kg".format(item.quantity)
        } else {
            val bags = (item.quantity / (item.bagSize ?: 1.0)).toInt()
            val bagPrice = item.bagPrice ?: (item.unitPrice * (item.bagSize ?: 1.0))
            "$bags bags @ ₹%.2f".format(bagPrice)
        }
        holder.tvQuantity.text = qtyStr
        holder.tvPrice.text = "₹%.2f".format(item.amount)
        holder.btnDelete.setOnClickListener {
            onDelete(position)
        }
    }

    override fun getItemCount(): Int = items.size
}