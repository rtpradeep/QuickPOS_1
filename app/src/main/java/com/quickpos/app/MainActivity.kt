package com.quickpos.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

@Entity(tableName = "invoices")
data class InvoiceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val invoiceNo: String,
    val timestamp: Long,
    val customerPhone: String,
    val itemsSummary: String,
    val totalAmount: Double,
    val paymentMode: String
)

@Dao
interface InvoiceDao {
    @Insert
    suspend fun insertInvoice(invoice: InvoiceEntity)

    @Query("SELECT * FROM invoices ORDER BY timestamp DESC")
    fun getAllInvoices(): Flow<List<InvoiceEntity>>
}

@Database(entities = [InvoiceEntity::class], version = 1, exportSchema = false)
abstract class PosDatabase : RoomDatabase() {
    abstract fun invoiceDao(): InvoiceDao

    companion object {
        @Volatile private var INSTANCE: PosDatabase? = null
        fun get(context: Context): PosDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, PosDatabase::class.java, "quickpos.db")
                    .build().also { INSTANCE = it }
            }
        }
    }
}

data class BillItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val rate: Double,
    val qty: Double
) {
    val amount: Double get() = rate * qty
}

object QrAndPrinterHelper {
    private const val MERCHANT_UPI = "merchant@upi"
    private const val MERCHANT_NAME = "QuickPOS Store"

    fun generateUpiQrBitmap(amount: Double, invoiceNo: String, size: Int = 320): Bitmap {
        val uri = "upi://pay?pa=$MERCHANT_UPI&pn=${MERCHANT_NAME.replace(" ", "%20")}" +
                "&am=${"%.2f".format(Locale.US, amount)}&cu=INR&tn=Bill_$invoiceNo"
        val matrix = QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    fun build80mmReceiptBytes(
        invoiceNo: String,
        items: List<BillItem>,
        total: Double,
        paymentMode: String
    ): ByteArray {
        val sb = StringBuilder()
        val line = "-".repeat(48) + "\n"
        val dateStr = SimpleDateFormat("dd-MMM-yyyy hh:mm a", Locale.US).format(Date())

        sb.append("                 QUICKPOS STORE                 \n")
        sb.append("Bill No: $invoiceNo\n")
        sb.append("Date   : $dateStr\n")
        sb.append(line)
        sb.append(String.format(Locale.US, "%-22s %6s %8s %9s\n", "ITEM", "QTY", "RATE", "AMOUNT"))
        sb.append(line)

        for (item in items) {
            val cleanName = item.name.take(22)
            sb.append(
                String.format(
                    Locale.US,
                    "%-22s %6.1f %8.2f %9.2f\n",
                    cleanName, item.qty, item.rate, item.amount
                )
            )
        }
        sb.append(line)
        sb.append(String.format(Locale.US, "%-30s Rs. %13.2f\n", "GRAND TOTAL ($paymentMode):", total))
        sb.append(line)
        sb.append("            Thank You! Visit Again!             \n\n\n")

        val initCmd = byteArrayOf(0x1B, 0x40)
        val cutCmd = byteArrayOf(0x1D, 0x56, 0x41, 0x10)
        return initCmd + sb.toString().toByteArray(Charsets.UTF_8) + cutCmd
    }

    @SuppressLint("MissingPermission")
    suspend fun printViaBluetooth(context: Context, payload: ByteArray): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val adapter: BluetoothAdapter? = manager.adapter
                val printer = adapter?.bondedDevices?.firstOrNull()
                    ?: return@withContext Result.failure(Exception("No paired Bluetooth printer found"))

                val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                val socket = printer.createRfcommSocketToServiceRecord(uuid)
                socket.connect()
                val out: OutputStream = socket.outputStream
                out.write(payload)
                out.flush()
                socket.close()
                Result.success("Printed on ${printer.name}")
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = PosDatabase.get(this)
        setContent {
            MaterialTheme {
                QuickPosApp(db.invoiceDao())
            }
        }
    }
}

@Composable
fun QuickPosApp(dao: InvoiceDao) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val invoices by dao.getAllInvoices().collectAsState(initial = emptyList())

    var itemName by remember { mutableStateOf("") }
    var itemRate by remember { mutableStateOf("") }
    var itemQty by remember { mutableStateOf("1") }
    var cart by remember { mutableStateOf(listOf<BillItem>()) }
    var customerPhone by remember { mutableStateOf("") }
    var paymentMode by remember { mutableStateOf("UPI") }
    var showReceiptDialog by remember { mutableStateOf(false) }
    var activeInvoiceNo by remember { mutableStateOf("INV-1001") }

    val grandTotal = cart.sumOf { it.amount }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("QuickPOS Billing", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Saved Bills: ${invoices.size}", style = MaterialTheme.typography.bodyMedium)
        }

        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = itemName,
                        onValueChange = { itemName = it },
                        label = { Text("Item Name") },
                        modifier = Modifier.weight(1.5f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = itemRate,
                        onValueChange = { itemRate = it },
                        label = { Text("Rate ₹") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = itemQty,
                        onValueChange = { itemQty = it },
                        label = { Text("Qty") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(0.7f),
                        singleLine = true
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        val r = itemRate.toDoubleOrNull() ?: 0.0
                        val q = itemQty.toDoubleOrNull() ?: 1.0
                        if (r > 0) {
                            val label = itemName.ifBlank { "Item #${cart.size + 1}" }
                            cart = cart + BillItem(name = label, rate = r, qty = q)
                            itemName = ""
                            itemRate = ""
                            itemQty = "1"
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("+ Add to Bill")
                }
            }
        }

        Card(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                items(cart, key = { it.id }) { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.name, fontWeight = FontWeight.SemiBold)
                            Text("${item.qty} x ₹${"%.2f".format(item.rate)}", fontSize = 13.sp)
                        }
                        Text("₹${"%.2f".format(item.amount)}", fontWeight = FontWeight.Bold)
                        TextButton(onClick = { cart = cart.filterNot { it.id == item.id } }) {
                            Text("X")
                        }
                    }
                    HorizontalDivider()
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Grand Total", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("₹${"%.2f".format(grandTotal)}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("CASH", "UPI", "SPLIT").forEach { mode ->
                        FilterChip(
                            selected = paymentMode == mode,
                            onClick = { paymentMode = mode },
                            label = { Text(mode) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        activeInvoiceNo = "INV-${1001 + invoices.size}"
                        showReceiptDialog = true
                    },
                    enabled = cart.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Generate 3\" Receipt & UPI QR")
                }
            }
        }
    }

    if (showReceiptDialog) {
        val qrBitmap = remember(grandTotal, activeInvoiceNo) {
            QrAndPrinterHelper.generateUpiQrBitmap(grandTotal, activeInvoiceNo)
        }

        AlertDialog(
            onDismissRequest = { showReceiptDialog = false },
            title = { Text("80mm Receipt Preview ($activeInvoiceNo)") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(8.dp)
                    ) {
                        Text(
                            text = buildString {
                                append("QUICKPOS STORE\n")
                                append("Invoice: $activeInvoiceNo | Mode: $paymentMode\n")
                                append("--------------------------------\n")
                                cart.forEach {
                                    append("${it.name.take(16)}  ${it.qty}x  ₹${"%.2f".format(it.amount)}\n")
                                }
                                append("--------------------------------\n")
                                append("TOTAL: ₹${"%.2f".format(grandTotal)}")
                            },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Scan to Pay ₹${"%.2f".format(grandTotal)}", fontWeight = FontWeight.Bold)
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "UPI QR",
                        modifier = Modifier.size(180.dp)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val bytes = QrAndPrinterHelper.build80mmReceiptBytes(
                        activeInvoiceNo, cart, grandTotal, paymentMode
                    )
                    scope.launch {
                        dao.insertInvoice(
                            InvoiceEntity(
                                invoiceNo = activeInvoiceNo,
                                timestamp = System.currentTimeMillis(),
                                customerPhone = customerPhone,
                                itemsSummary = cart.joinToString { "${it.name} x${it.qty}" },
                                totalAmount = grandTotal,
                                paymentMode = paymentMode
                            )
                        )
                        val result = QrAndPrinterHelper.printViaBluetooth(context, bytes)
                        Toast.makeText(
                            context,
                            result.fold(onSuccess = { it }, onFailure = { "Saved! (${it.message})" }),
                            Toast.LENGTH_SHORT
                        ).show()
                        cart = emptyList()
                        showReceiptDialog = false
                    }
                }) {
                    Text("Save & Print (80mm)")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReceiptDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}
