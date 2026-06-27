package com.example.riceshopbilling

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
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
    private lateinit var tvBillNumber: TextView
    private lateinit var btnPrint: Button
    private lateinit var btnPreview: Button
    private lateinit var btnSearch: Button

    private val billItems = mutableListOf<BillItem>()
    private lateinit var billAdapter: BillAdapter
    private var subtotal = 0.0
    private var taxPercent = 0.0
    private var discount = 0.0

    private lateinit var db: AppDatabase
    private lateinit var products: List<Product>
    private var allProducts: List<Product> = listOf()

    private val REQUEST_BLUETOOTH_PERMISSION = 100
    private val REQUEST_SEARCH = 200
    private val bagSizes = arrayOf(5, 10, 25, 26, 30, 50)

    private var currentBillNumber = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPref = getSharedPreferences("printer_settings", Context.MODE_PRIVATE)

        initViews()
        setupDate()
        setupBillNumber()
        setupRecyclerView()
        setupTaxDiscountListeners()

        db = AppDatabase.getInstance(this)
        loadProducts()

        btnPrint.setOnClickListener { showPrintOptions() }
        btnPreview.setOnClickListener { showBillPreview() }
        btnSearch.setOnClickListener {
            val intent = Intent(this, SearchActivity::class.java)
            startActivityForResult(intent, REQUEST_SEARCH)
        }

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
        tvBillNumber = findViewById(R.id.tvBillNumber)
        btnPrint = findViewById(R.id.btnPrint)
        btnPreview = findViewById(R.id.btnPreview)
        btnSearch = findViewById(R.id.btnSearch)

        productGrid.layoutManager = GridLayoutManager(this, 2)
    }

    private fun setupDate() {
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        tvDate.text = "Date: $date"
    }

    private fun setupBillNumber() {
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val lastDate = sharedPref.getString("last_bill_date", "")
        val nextNumber = sharedPref.getInt("next_bill_number", 1)

        currentBillNumber = if (today == lastDate) {
            nextNumber
        } else {
            sharedPref.edit().apply {
                putString("last_bill_date", today)
                putInt("next_bill_number", 1)
                apply()
            }
            1
        }
        tvBillNumber.text = "Bill #$currentBillNumber"
    }

    private fun incrementBillNumber() {
        val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val next = sharedPref.getInt("next_bill_number", 1) + 1
        sharedPref.edit().apply {
            putString("last_bill_date", today)
            putInt("next_bill_number", next)
            apply()
        }
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
            }
        }
    }

    private fun setupProductGrid() {
        val adapter = ProductGridAdapter(products) { product ->
            showProductDialog(product)
        }
        productGrid.adapter = adapter
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SEARCH && resultCode == RESULT_OK) {
            val productId = data?.getIntExtra("product_id", -1) ?: -1
            if (productId != -1) {
                val product = allProducts.find { it.id == productId }
                if (product != null) {
                    showProductDialog(product)
                }
            }
        }
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

        val bagSizeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, bagSizes.map { "$it kg" })
        bagSizeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerBagSize.adapter = bagSizeAdapter

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

    // ==================== TAMIL PRINTING SUPPORT ====================

    private fun containsTamil(text: String): Boolean {
        return text.any { it in '\u0B80'..'\u0BFF' }
    }

    private fun textToBitmap(text: String, paint: Paint): Bitmap {
        val bounds = android.graphics.Rect()
        paint.getTextBounds(text, 0, text.length, bounds)

        val bitmap = Bitmap.createBitmap(
            bounds.width() + 10,
            bounds.height() + 10,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawText(text, 5f, -bounds.top.toFloat() + 5f, paint)
        return bitmap
    }

    private fun printBitmap(outputStream: OutputStream, bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height

        val bytesPerLine = (width + 7) / 8
        val data = ByteArray(bytesPerLine * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                val isBlack = Color.red(pixel) < 128
                if (!isBlack) {
                    val byteIndex = y * bytesPerLine + (x / 8)
                    val bitPosition = 7 - (x % 8)
                    data[byteIndex] = (data[byteIndex].toInt() or (1 shl bitPosition)).toByte()
                }
            }
        }

        // ESC/POS command for raster bit image
        outputStream.write(0x1D)
        outputStream.write(0x76)
        outputStream.write(0x30)
        outputStream.write(0x00)
        outputStream.write(bytesPerLine % 256)
        outputStream.write(bytesPerLine / 256)
        outputStream.write(height % 256)
        outputStream.write(height / 256)
        outputStream.write(data)
        outputStream.flush()
    }

    private fun printWithTamilSupport(outputStream: OutputStream) {
        try {
            // Initialize printer
            outputStream.write(byteArrayOf(0x1B, 0x40)) // ESC @

            // Center align
            outputStream.write(byteArrayOf(0x1B, 0x61, 0x01)) // ESC a 1

            // Build bill lines
            val (englishText, tamilLines) = buildBillTextWithTamil()

            // Print English part as normal text
            outputStream.write(englishText.toByteArray(Charsets.UTF_8))

            // Set up paint for Tamil text
            val tamilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 28f
                typeface = Typeface.DEFAULT
            }

            // Print each Tamil line as bitmap
            for (tamilLine in tamilLines) {
                val bitmap = textToBitmap(tamilLine, tamilPaint)
                printBitmap(outputStream, bitmap)
                outputStream.write("\n".toByteArray())
            }

            // Cut paper
            outputStream.write(byteArrayOf(0x1D, 0x56, 0x01)) // GS V 1

            outputStream.flush()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun buildBillTextWithTamil(): Pair<String, List<String>> {
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        val englishLines = mutableListOf<String>()
        val tamilLines = mutableListOf<String>()

        englishLines.add("      Gani Anbu Store")
        englishLines.add("         Dindigul")
        englishLines.add("Phone: 9842125936,8870430799")
        englishLines.add("----------------------")
        englishLines.add("Date: $date")
        englishLines.add("Bill #$currentBillNumber")
        englishLines.add("----------------------")
        englishLines.add(String.format("%-16s %8s %8s %8s", "Item", "Qty", "Price", "Amount"))
        englishLines.add("----------------------")

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

            val line = String.format("%-16s %8s %8.2f %8.2f",
                itemName, qtyStr, priceToShow, item.amount)

            if (containsTamil(line)) {
                tamilLines.add(line)
            } else {
                englishLines.add(line)
            }
        }

        englishLines.add("----------------------")
        englishLines.add(String.format("%-24s %8.2f", "Subtotal:", subtotal))
        val taxAmount = subtotal * taxPercent / 100
        englishLines.add(String.format("%-24s %8.2f", "Tax (${taxPercent}%):", taxAmount))
        englishLines.add(String.format("%-24s %8.2f", "Discount:", discount))
        englishLines.add("----------------------")
        val grandTotal = subtotal + taxAmount - discount
        englishLines.add(String.format("%-24s %8.2f", "Grand Total:", grandTotal))
        englishLines.add("----------------------")
        englishLines.add("  Thank you, visit again!")
        englishLines.add("\n\n\n")

        return Pair(englishLines.joinToString("\n"), tamilLines)
    }

    // ==================== END OF TAMIL SUPPORT ====================

    // ==================== DELIVERY SLIP FUNCTIONS ====================

    private fun hasBagItems(): Boolean {
        return billItems.any { it.unit == "bag" }
    }

    private fun getBagItems(): List<BillItem> {
        return billItems.filter { it.unit == "bag" }
    }

    private fun buildDeliverySlipText(): String {
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        val bagItems = getBagItems()
        val totalBags = bagItems.sumOf { (it.quantity / (it.bagSize ?: 1.0)).toInt() }

        val sb = StringBuilder()

        sb.appendLine("      Gani Anbu Store")
        sb.appendLine("         Dindigul")
        sb.appendLine("----------------------")
        sb.appendLine("📦 Delivery Slip")
        sb.appendLine("Date: $date")
        sb.appendLine("Bill #$currentBillNumber")
        sb.appendLine("----------------------")

        sb.appendLine(String.format("%-20s %5s", "Item", "Bags"))
        sb.appendLine("----------------------")

        for (item in bagItems) {
            val itemName = item.productName.take(18)
            val bags = (item.quantity / (item.bagSize ?: 1.0)).toInt()
            sb.appendLine(String.format("%-20s %5d", itemName, bags))
        }

        sb.appendLine("----------------------")
        sb.appendLine(String.format("%-20s %5d", "Total Bags:", totalBags))
        sb.appendLine("----------------------")
        sb.appendLine("  Thank you!")
        sb.appendLine("\n\n")

        return sb.toString()
    }

    private fun printDeliverySlip(outputStream: OutputStream) {
        if (!hasBagItems()) {
            return
        }

        val deliveryText = buildDeliverySlipText()

        try {
            outputStream.write(byteArrayOf(0x1B, 0x40)) // ESC @
            outputStream.write(byteArrayOf(0x1B, 0x61, 0x01)) // Center align
            outputStream.write(deliveryText.toByteArray(Charsets.UTF_8))
            outputStream.write(byteArrayOf(0x1D, 0x56, 0x01)) // Cut paper
            outputStream.flush()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    // ==================== END OF DELIVERY SLIP FUNCTIONS ====================

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

                for (i in 1..copies) {
                    printWithTamilSupport(outputStream)
                    if (i < copies) delay(500)
                }

                if (hasBagItems()) {
                    printDeliverySlip(outputStream)
                }

                outputStream.close()
                socket.close()

                withContext(Dispatchers.Main) {
                    val message = if (hasBagItems()) {
                        "Bill printed ($copies copies) + Delivery Slip printed"
                    } else {
                        "Bill printed ($copies copies)"
                    }
                    Toast.makeText(this@BillActivity, message, Toast.LENGTH_SHORT).show()
                    incrementBillNumber()
                    goToHome()
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

                for (i in 1..copies) {
                    printWithTamilSupport(outputStream)
                    if (i < copies) delay(500)
                }

                if (hasBagItems()) {
                    printDeliverySlip(outputStream)
                }

                outputStream.close()
                socket.close()

                withContext(Dispatchers.Main) {
                    val message = if (hasBagItems()) {
                        "Bill printed ($copies copies) + Delivery Slip printed"
                    } else {
                        "Bill printed ($copies copies)"
                    }
                    Toast.makeText(this@BillActivity, message, Toast.LENGTH_SHORT).show()
                    incrementBillNumber()
                    goToHome()
                }
            } catch (e: IOException) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@BillActivity, "WiFi print failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun goToHome() {
        val intent = Intent(this, HomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun showBillPreview() {
        if (billItems.isEmpty()) {
            Toast.makeText(this, "No items to preview", Toast.LENGTH_SHORT).show()
            return
        }

        val (englishText, tamilLines) = buildBillTextWithTamil()
        val fullText = englishText + "\n" + tamilLines.joinToString("\n")

        AlertDialog.Builder(this)
            .setTitle("Bill Preview")
            .setMessage(fullText)
            .setPositiveButton("Close", null)
            .show()
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