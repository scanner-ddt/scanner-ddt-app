package it.scannerddt.app

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Errore restituito dallo script (es. PIN errato, ticket duplicato): non va ritentato. */
class ErroreServer(msg: String) : Exception(msg)

class MainActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    private var pdfScansione: File? = null
    private var pagineScansione = 0
    private var sigla = ""

    private lateinit var tvSigla: TextView
    private lateinit var tvInfo: TextView
    private lateinit var tvConteggio: TextView
    private lateinit var boxRisultato: LinearLayout
    private lateinit var imgTicket: ImageView
    private lateinit var tvPagine: TextView
    private lateinit var etProgressivo: EditText
    private lateinit var tvOcr: TextView
    private lateinit var tvNome: TextView
    private lateinit var btnCarica: Button
    private lateinit var llLista: LinearLayout
    private lateinit var tvVuoto: TextView
    private lateinit var btnInviaOra: Button
    private lateinit var tvStato: TextView

    private val scanner = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res -> scansioneCompletata(res) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("ddt", MODE_PRIVATE)
        sigla = prefs.getString("sigla", "") ?: ""

        tvSigla = findViewById(R.id.tvSigla)
        tvInfo = findViewById(R.id.tvInfo)
        tvConteggio = findViewById(R.id.tvConteggio)
        boxRisultato = findViewById(R.id.boxRisultato)
        imgTicket = findViewById(R.id.imgTicket)
        tvPagine = findViewById(R.id.tvPagine)
        etProgressivo = findViewById(R.id.etProgressivo)
        tvOcr = findViewById(R.id.tvOcr)
        tvNome = findViewById(R.id.tvNome)
        btnCarica = findViewById(R.id.btnCarica)
        llLista = findViewById(R.id.llLista)
        tvVuoto = findViewById(R.id.tvVuoto)
        btnInviaOra = findViewById(R.id.btnInviaOra)
        tvStato = findViewById(R.id.tvStato)

        findViewById<Button>(R.id.btnScansiona).setOnClickListener { avviaScanner() }
        findViewById<Button>(R.id.btnImpostazioni).setOnClickListener { apriImpostazioni() }
        findViewById<Button>(R.id.btnAggiorna).setOnClickListener { aggiorna() }
        btnCarica.setOnClickListener { carica() }
        btnInviaOra.setOnClickListener { confermaInvio() }
        etProgressivo.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { aggiornaNome() }
        })

        tvSigla.text = sigla.ifEmpty { "—" }
        if (!configurata()) apriImpostazioni()
    }

    override fun onResume() {
        super.onResume()
        aggiorna()
    }

    // ---------------------------------------------------------------- scanner

    private fun avviaScanner() {
        val opzioni = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(10)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(opzioni)
            .getStartScanIntent(this)
            .addOnSuccessListener { sender ->
                scanner.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { e ->
                avviso("Scanner non disponibile: ${e.message}. Controlla che Google Play Services sia aggiornato.")
            }
    }

    private fun scansioneCompletata(res: ActivityResult) {
        if (res.resultCode != Activity.RESULT_OK) return
        val risultato = GmsDocumentScanningResult.fromActivityResultIntent(res.data)
        val pdf = risultato?.pdf
        val pagine = risultato?.pages
        if (pdf == null || pagine.isNullOrEmpty()) {
            avviso("Scansione non riuscita, riprova.")
            return
        }
        try {
            val f = File(cacheDir, "scansione.pdf")
            contentResolver.openInputStream(pdf.uri)?.use { inp ->
                f.outputStream().use { out -> inp.copyTo(out) }
            } ?: throw IOException("PDF non leggibile")
            pdfScansione = f
            pagineScansione = pdf.pageCount
        } catch (e: Exception) {
            avviso("Non riesco a leggere la scansione: ${e.message}")
            return
        }
        val ticket = pagine.last().imageUri
        boxRisultato.visibility = View.VISIBLE
        tvPagine.text = if (pagineScansione == 1)
            "1 pagina: serve anche il ticket come ultima pagina!"
        else "$pagineScansione pagine (DDT + ticket)"
        mostraMiniatura(ticket)
        etProgressivo.setText("")
        leggiProgressivo(ticket)
        aggiornaNome()
    }

    private fun mostraMiniatura(uri: Uri) {
        try {
            val opt = BitmapFactory.Options().apply { inSampleSize = 8 }
            contentResolver.openInputStream(uri)?.use {
                imgTicket.setImageBitmap(BitmapFactory.decodeStream(it, null, opt))
            }
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------- lettura N. Progressivo

    private fun leggiProgressivo(uri: Uri) {
        tvOcr.text = "Leggo il N. Progressivo…"
        val immagine = try {
            InputImage.fromFilePath(this, uri)
        } catch (e: Exception) {
            tvOcr.text = "Non riesco a leggere il ticket: scrivi il numero a mano."
            return
        }
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            .process(immagine)
            .addOnSuccessListener { testo ->
                val n = Progressivo.trova(testo)
                if (n.isNotEmpty()) {
                    etProgressivo.setText(n)
                    tvOcr.text = "Letto: $n. Controlla che sia giusto."
                } else {
                    tvOcr.text = "Non trovo \"N. Progressivo\" nell'ultima pagina: scrivilo a mano."
                }
            }
            .addOnFailureListener {
                tvOcr.text = "Lettura non riuscita: scrivi il numero a mano."
            }
    }

    private fun codice(): String =
        etProgressivo.text.toString().trim().uppercase().replace(Regex("[^A-Z0-9-]"), "")

    private fun aggiornaNome() {
        val c = codice()
        val pronto = pdfScansione != null && c.isNotEmpty() && sigla.length == 2
        tvNome.visibility = if (pdfScansione != null) View.VISIBLE else View.GONE
        tvNome.text = "${sigla.ifEmpty { "??" }}_${c.ifEmpty { "…" }}.pdf"
        btnCarica.isEnabled = pronto
    }

    // ------------------------------------------------------------ caricamento

    private fun carica() {
        val f = pdfScansione ?: return
        val nome = "${sigla}_${codice()}.pdf"
        val pagine = pagineScansione
        btnCarica.isEnabled = false
        btnCarica.text = "Carico…"
        io.execute {
            val bytes = f.readBytes()
            try {
                caricaPdf(nome, bytes, pagine)
                ui.post {
                    toast("$nome messo in coda")
                    azzera()
                    aggiorna()
                }
            } catch (e: ErroreServer) {
                ui.post {
                    btnCarica.text = "Salva e metti in coda"
                    aggiornaNome()
                    avviso(e.message ?: "Errore")
                }
            } catch (e: Exception) {
                // nessuna connessione: lo salvo sul telefono e lo carico appena possibile
                File(cartellaCoda(), nome).writeBytes(bytes)
                ui.post {
                    avviso("Nessuna connessione: $nome è salvato sul telefono e verrà caricato appena possibile.")
                    azzera()
                    aggiorna()
                }
            }
        }
    }

    private fun caricaPdf(nome: String, bytes: ByteArray, pagine: Int) {
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        chiama("caricaPdf", nome, b64, pagine)
    }

    private fun azzera() {
        pdfScansione = null
        pagineScansione = 0
        etProgressivo.setText("")
        tvOcr.text = ""
        imgTicket.setImageDrawable(null)
        boxRisultato.visibility = View.GONE
        btnCarica.text = "Salva e metti in coda"
        aggiornaNome()
    }

    private fun cartellaCoda(): File = File(filesDir, "coda").apply { mkdirs() }

    /** Prova a caricare i PDF rimasti sul telefono. Va chiamata nel thread io. */
    private fun svuotaCoda(): Int {
        var caricati = 0
        cartellaCoda().listFiles()?.forEach { f ->
            try {
                caricaPdf(f.name, f.readBytes(), 0)
                f.delete()
                caricati++
            } catch (e: ErroreServer) {
                if ((e.message ?: "").contains("Esiste già")) f.delete()
            } catch (_: Exception) {
                return caricati // ancora senza rete
            }
        }
        return caricati
    }

    // ---------------------------------------------------------- stato e lista

    private fun aggiorna() {
        if (!configurata()) {
            tvInfo.text = "Configura le impostazioni per collegare l'app"
            return
        }
        tvStato.text = "Aggiorno…"
        io.execute {
            try {
                val dalTelefono = svuotaCoda()
                val st = chiama("stato") as JSONObject
                ui.post {
                    mostraStato(st)
                    if (dalTelefono > 0) toast("$dalTelefono DDT caricati dal telefono")
                }
            } catch (e: Exception) {
                val inCoda = cartellaCoda().listFiles()?.size ?: 0
                ui.post {
                    tvStato.text = "Non collegato: ${e.message}" +
                        if (inCoda > 0) "\n$inCoda DDT salvati sul telefono, in attesa di rete." else ""
                }
            }
        }
    }

    private fun mostraStato(st: JSONObject) {
        val imp = st.getJSONObject("impostazioni")
        sigla = imp.optString("sigla", "")
        prefs.edit().putString("sigla", sigla).apply()
        val ora = imp.optString("ora", "")
        val dest = imp.optJSONArray("dest") ?: JSONArray()
        val elencoDest = (0 until dest.length()).joinToString(", ") { dest.getString(it) }
        tvSigla.text = sigla.ifEmpty { "—" }
        tvInfo.text = "Invio automatico ogni giorno alle $ora\na ${elencoDest.ifEmpty { "nessun destinatario" }}"

        val oggi = st.optJSONArray("oggi") ?: JSONArray()
        tvConteggio.text = oggi.length().toString()
        llLista.removeAllViews()
        for (i in 0 until oggi.length()) {
            val o = oggi.getJSONObject(i)
            llLista.addView(riga(o.optString("nome"), o.optString("ora"), o.optInt("kb")))
        }
        tvVuoto.visibility = if (oggi.length() == 0) View.VISIBLE else View.GONE
        btnInviaOra.isEnabled = oggi.length() > 0
        val inCoda = cartellaCoda().listFiles()?.size ?: 0
        tvStato.text = "Collegato" + if (inCoda > 0) " · $inCoda DDT ancora sul telefono" else ""
        aggiornaNome()
    }

    private fun riga(nome: String, ora: String, kb: Int): View {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 18, 0, 18)
        }
        val testo = TextView(this).apply {
            text = "$nome\n$ora · $kb KB"
            textSize = 16f
            setTextColor(getColor(R.color.inchiostro))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val elimina = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Elimina"
            setOnClickListener { confermaElimina(nome) }
        }
        v.addView(testo)
        v.addView(elimina)
        return v
    }

    private fun confermaElimina(nome: String) {
        AlertDialog.Builder(this)
            .setTitle("Eliminare $nome?")
            .setMessage("Il PDF non verrà inviato.")
            .setPositiveButton("Elimina") { _, _ ->
                io.execute {
                    try {
                        chiama("eliminaPdf", nome)
                        ui.post { toast("$nome eliminato"); aggiorna() }
                    } catch (e: Exception) {
                        ui.post { avviso(e.message ?: "Errore") }
                    }
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun confermaInvio() {
        AlertDialog.Builder(this)
            .setTitle("Inviare subito i DDT in attesa?")
            .setMessage("Partirà la mail con i PDF ai destinatari impostati.")
            .setPositiveButton("Invia") { _, _ ->
                io.execute {
                    try {
                        val r = chiama("inviaOra") as JSONObject
                        ui.post { toast("Inviati ${r.optInt("inviati")} DDT"); aggiorna() }
                    } catch (e: Exception) {
                        ui.post { avviso(e.message ?: "Errore") }
                    }
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    // ------------------------------------------------------------ impostazioni

    private fun configurata() =
        !prefs.getString("url", "").isNullOrEmpty() && !prefs.getString("pin", "").isNullOrEmpty()

    private fun apriImpostazioni() {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_impostazioni, null)
        val etUrl = v.findViewById<EditText>(R.id.etUrl)
        val etPin = v.findViewById<EditText>(R.id.etPin)
        val etSigla = v.findViewById<EditText>(R.id.etSigla)
        val etDest = v.findViewById<EditText>(R.id.etDest)
        val etOra = v.findViewById<EditText>(R.id.etOra)
        etUrl.setText(prefs.getString("url", ""))
        etPin.setText(prefs.getString("pin", ""))
        etSigla.setText(sigla)
        // i dati dello script li leggo al volo, se l'app è già collegata
        if (configurata()) io.execute {
            try {
                val imp = (chiama("stato") as JSONObject).getJSONObject("impostazioni")
                val d = imp.optJSONArray("dest") ?: JSONArray()
                ui.post {
                    etSigla.setText(imp.optString("sigla"))
                    etDest.setText((0 until d.length()).joinToString("\n") { d.getString(it) })
                    etOra.setText(imp.optString("ora"))
                }
            } catch (_: Exception) {
            }
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Impostazioni")
            .setView(v)
            .setPositiveButton("Salva", null)
            .setNegativeButton("Chiudi", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = etUrl.text.toString().trim()
                val pin = etPin.text.toString().trim()
                if (!Regex("^https://script\\.google\\.com/.+/exec$").matches(url)) {
                    toast("L'indirizzo deve iniziare con https://script.google.com/ e finire con /exec")
                    return@setOnClickListener
                }
                if (pin.isEmpty()) { toast("Inserisci il PIN"); return@setOnClickListener }
                prefs.edit().putString("url", url).putString("pin", pin).apply()
                val s = etSigla.text.toString().trim().uppercase()
                val dest = etDest.text.toString().split(Regex("[\\s,;]+")).filter { it.isNotEmpty() }
                val ora = etOra.text.toString().trim()
                dlg.dismiss()
                if (s.length == 2 && dest.isNotEmpty() && Regex("^\\d\\d:\\d\\d$").matches(ora)) {
                    io.execute {
                        try {
                            val imp = JSONObject()
                                .put("sigla", s)
                                .put("dest", JSONArray(dest))
                                .put("ora", ora)
                            chiama("salvaImpostazioni", imp)
                            ui.post { toast("Impostazioni salvate"); aggiorna() }
                        } catch (e: Exception) {
                            ui.post { avviso("Collegamento salvato, ma le impostazioni dello script no: ${e.message}") }
                        }
                    }
                } else {
                    aggiorna()
                }
            }
        }
        dlg.show()
    }

    // ------------------------------------------------- comunicazione script

    /** Chiama una funzione dello script (doPost). Il PIN viene aggiunto come primo argomento. */
    private fun chiama(fn: String, vararg argomenti: Any): Any? {
        val args = JSONArray().put(prefs.getString("pin", ""))
        argomenti.forEach { args.put(it) }
        val corpo = JSONObject().put("fn", fn).put("args", args).toString().toByteArray(Charsets.UTF_8)

        var c = URL(prefs.getString("url", "")).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.instanceFollowRedirects = false
        c.connectTimeout = 20000
        c.readTimeout = 90000
        c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        c.outputStream.use { it.write(corpo) }

        // Apps Script risponde con un reindirizzamento: la risposta si legge con un GET
        var salti = 0
        while (c.responseCode in 301..303 || c.responseCode == 307 || c.responseCode == 308) {
            val dove = c.getHeaderField("Location") ?: break
            c.disconnect()
            c = URL(dove).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20000
            c.readTimeout = 90000
            if (++salti > 5) throw IOException("Troppi reindirizzamenti")
        }
        if (c.responseCode != 200) throw IOException("Risposta ${c.responseCode} dallo script")
        val testo = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        c.disconnect()
        val j = try {
            JSONObject(testo)
        } catch (e: Exception) {
            throw ErroreServer("Risposta non valida: controlla l'indirizzo dello script e che il deployment sia aggiornato.")
        }
        if (!j.optBoolean("ok")) throw ErroreServer(j.optString("error", "Errore dello script"))
        return j.opt("data")
    }

    // ------------------------------------------------------------- utilità

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun avviso(m: String) {
        AlertDialog.Builder(this).setMessage(m).setPositiveButton("OK", null).show()
    }
}

/** Ricerca del N. Progressivo nel testo riconosciuto, tollerante agli errori tipici dell'OCR. */
object Progressivo {
    private val chiave = Regex("pr[o0uq]?g\\s*r|gre\\s*ss|ss\\s*iv[o0]|pr[o0]g\\b", RegexOption.IGNORE_CASE)
    private val numero = Regex("\\d{3,8}")

    fun trova(t: Text): String {
        val righe = mutableListOf<Pair<String, Rect?>>()
        for (b in t.textBlocks) for (l in b.lines) righe.add(l.text to l.boundingBox)

        for ((testo, box) in righe) {
            val m = chiave.find(testo) ?: continue
            // 1) numero sulla stessa riga, dopo la parola
            numero.find(testo.substring(m.range.last + 1))?.let { return it.value }
            if (box == null) continue
            // 2) numero su una riga separata alla stessa altezza, a destra
            val stessaAltezza = righe.filter { (tx, b) ->
                b != null && b.left >= box.left && b !== box &&
                    b.centerY() in box.top..box.bottom && numero.containsMatchIn(tx)
            }.minByOrNull { it.second!!.left - box.right }
            if (stessaAltezza != null) return numero.find(stessaAltezza.first)!!.value
            // 3) numero subito sotto
            val sotto = righe.filter { (tx, b) ->
                b != null && b.top >= box.bottom && b.top - box.bottom < box.height() * 2 &&
                    b.right > box.left && b.left < box.right + box.width() && numero.containsMatchIn(tx)
            }.minByOrNull { it.second!!.top }
            if (sotto != null) return numero.find(sotto.first)!!.value
        }
        return trovaNelTesto(t.text)
    }

    fun trovaNelTesto(t: String): String {
        val righe = t.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        for ((i, r) in righe.withIndex()) {
            val m = chiave.find(r) ?: continue
            val n = numero.find(r.substring(m.range.last + 1))
                ?: righe.getOrNull(i + 1)?.let { numero.find(it) }
            if (n != null) return n.value
        }
        return ""
    }
}
