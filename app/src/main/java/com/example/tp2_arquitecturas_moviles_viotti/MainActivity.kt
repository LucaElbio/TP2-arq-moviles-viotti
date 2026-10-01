@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.tp2_arquitecturas_moviles_viotti

import android.app.Application
import android.app.DatePickerDialog
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.os.bundleOf
import androidx.lifecycle.AndroidViewModel
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * Tengo Parcial — MVP
 * Agenda de parciales y entregas para estudiantes universitarios.
 *
 * El TP pide un único MainActivity.kt, así que las capas de MVVM + Clean
 * están separadas por secciones dentro del archivo. En un proyecto real cada
 * sección va en su propio paquete (domain, data, ui).
 *
 *   Dominio        → TipoEvento, Evento, Fechas
 *   Datos          → AuthRepositorio (Firebase Auth), EventosRepositorio (Firestore)
 *   Medición       → Metricas (Firebase Analytics)
 *   Presentación   → AgendaViewModel (estado) + pantallas Compose
 */

// ═════════════════════════════ DOMINIO ═════════════════════════════

enum class TipoEvento(val etiqueta: String) {
    PARCIAL("Parcial"),
    ENTREGA("Entrega TP"),
    FINAL("Final"),
    RECUPERATORIO("Recuperatorio")
}

data class Evento(
    val id: String,
    val materia: String,
    val tipo: TipoEvento,
    val fechaMillis: Long, // medianoche UTC del día elegido
    val hecho: Boolean
)

/** Cálculo de días sin java.time, para que funcione desde API 24 sin desugaring. */
object Fechas {
    private const val DIA_MS = 86_400_000L
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    /** Un día del calendario (año, mes 0-11, día) guardado como medianoche UTC. */
    fun utcMillis(anio: Int, mes: Int, dia: Int): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(anio, mes, dia)
        }.timeInMillis

    /** Hoy (fecha local del teléfono) expresado como medianoche UTC. */
    fun hoyUtcMillis(): Long {
        val local = Calendar.getInstance()
        return utcMillis(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }

    /** Año, mes y día de una fecha guardada, para abrir el selector en ese día. */
    fun partes(fechaMillis: Long): Triple<Int, Int, Int> {
        val c = Calendar.getInstance(utc).apply { timeInMillis = fechaMillis }
        return Triple(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
    }

    fun diasHasta(fechaMillis: Long): Int = ((fechaMillis - hoyUtcMillis()) / DIA_MS).toInt()

    fun formatear(fechaMillis: Long): String =
        SimpleDateFormat("EEE d 'de' MMM", Locale.forLanguageTag("es-AR"))
            .apply { timeZone = utc }
            .format(Date(fechaMillis))
}

// ═════════════════════════════ DATOS ═════════════════════════════

class AuthRepositorio(private val auth: FirebaseAuth = FirebaseAuth.getInstance()) {

    val usuarioActual: FirebaseUser? get() = auth.currentUser

    fun observar(alCambiar: (FirebaseUser?) -> Unit): FirebaseAuth.AuthStateListener {
        val listener = FirebaseAuth.AuthStateListener { alCambiar(it.currentUser) }
        auth.addAuthStateListener(listener)
        return listener
    }

    fun dejarDeObservar(listener: FirebaseAuth.AuthStateListener) =
        auth.removeAuthStateListener(listener)

    fun ingresar(email: String, clave: String, ok: () -> Unit, error: (Exception) -> Unit) {
        auth.signInWithEmailAndPassword(email, clave)
            .addOnSuccessListener { ok() }
            .addOnFailureListener { error(it) }
    }

    fun registrar(email: String, clave: String, ok: () -> Unit, error: (Exception) -> Unit) {
        auth.createUserWithEmailAndPassword(email, clave)
            .addOnSuccessListener { ok() }
            .addOnFailureListener { error(it) }
    }

    fun salir() = auth.signOut()
}

/**
 * Estructura en Firestore:
 *   usuarios/{uid}                 → email, aperturas, ultimaApertura (para medir retención)
 *   usuarios/{uid}/eventos/{id}    → materia, tipo, fechaMillis, hecho, creadoEn
 * Firestore trae caché offline activada por defecto en Android.
 */
class EventosRepositorio(private val db: FirebaseFirestore = FirebaseFirestore.getInstance()) {

    private fun usuario(uid: String) = db.collection("usuarios").document(uid)
    private fun eventos(uid: String) = usuario(uid).collection("eventos")

    fun escuchar(
        uid: String,
        alCambiar: (List<Evento>) -> Unit,
        alFallar: (Exception) -> Unit
    ): ListenerRegistration =
        eventos(uid).orderBy("fechaMillis").addSnapshotListener { snap, error ->
            if (error != null) {
                alFallar(error)
                return@addSnapshotListener
            }
            val lista = snap?.documents?.mapNotNull { doc ->
                val materia = doc.getString("materia") ?: return@mapNotNull null
                val fecha = doc.getLong("fechaMillis") ?: return@mapNotNull null
                val tipo = runCatching { TipoEvento.valueOf(doc.getString("tipo") ?: "") }
                    .getOrDefault(TipoEvento.PARCIAL)
                Evento(doc.id, materia, tipo, fecha, doc.getBoolean("hecho") ?: false)
            } ?: emptyList()
            alCambiar(lista)
        }

    fun agregar(uid: String, materia: String, tipo: TipoEvento, fechaMillis: Long) {
        eventos(uid).add(
            mapOf(
                "materia" to materia,
                "tipo" to tipo.name,
                "fechaMillis" to fechaMillis,
                "hecho" to false,
                "creadoEn" to FieldValue.serverTimestamp()
            )
        )
    }

    fun marcarHecho(uid: String, id: String, hecho: Boolean) {
        eventos(uid).document(id).update("hecho", hecho)
    }

    fun borrar(uid: String, id: String) {
        eventos(uid).document(id).delete()
    }

    /** Cuenta aperturas por usuario: permite ver retención en la consola de Firestore sin esperar a Analytics. */
    fun registrarApertura(uid: String, email: String?) {
        usuario(uid).set(
            mapOf(
                "email" to email,
                "aperturas" to FieldValue.increment(1),
                "ultimaApertura" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        )
    }
}

// ═════════════════════════════ MEDICIÓN ═════════════════════════════

/** Cada evento responde a una hipótesis del MVP (ver informe, sección 3). */
class Metricas(app: Application) {
    private val analytics = FirebaseAnalytics.getInstance(app)

    fun registro() = analytics.logEvent(
        FirebaseAnalytics.Event.SIGN_UP, bundleOf(FirebaseAnalytics.Param.METHOD to "email")
    )

    fun login() = analytics.logEvent(
        FirebaseAnalytics.Event.LOGIN, bundleOf(FirebaseAnalytics.Param.METHOD to "email")
    )

    // H1 y H2: ¿cargan fechas? ¿cuánto tardan?
    fun eventoCreado(tipo: TipoEvento, diasHasta: Int, segundosCarga: Long) = analytics.logEvent(
        "evento_creado",
        bundleOf("tipo" to tipo.name, "dias_hasta" to diasHasta.toLong(), "segundos_carga" to segundosCarga)
    )

    // H2: abrir el formulario y cancelar indica fricción en la carga
    fun cargaAbandonada(segundos: Long) =
        analytics.logEvent("carga_abandonada", bundleOf("segundos" to segundos))

    // H3: ¿vuelven a la app?
    fun agendaVista(cantidad: Int) =
        analytics.logEvent("agenda_vista", bundleOf("cantidad" to cantidad.toLong()))

    fun eventoCompletado(diasHasta: Int) =
        analytics.logEvent("evento_completado", bundleOf("dias_hasta" to diasHasta.toLong()))

    fun eventoBorrado() = analytics.logEvent("evento_borrado", null)
}

// ═════════════════════════════ PRESENTACIÓN: VIEWMODEL ═════════════════════════════

data class UiState(
    val uid: String? = null,
    val email: String? = null,
    val cargandoAuth: Boolean = false,
    val errorAuth: String? = null,
    val eventos: List<Evento> = emptyList(),
    val errorDatos: String? = null
)

class AgendaViewModel(app: Application) : AndroidViewModel(app) {

    private val authRepo = AuthRepositorio()
    private val eventosRepo = EventosRepositorio()
    private val metricas = Metricas(app)

    private val _estado = MutableStateFlow(
        UiState(uid = authRepo.usuarioActual?.uid, email = authRepo.usuarioActual?.email)
    )
    val estado: StateFlow<UiState> = _estado.asStateFlow()

    private var escucha: ListenerRegistration? = null
    private var aperturaRegistrada = false
    private var agendaVistaRegistrada = false
    private var inicioCarga = 0L

    // Va al final para que todas las propiedades ya estén inicializadas cuando llegue el primer aviso.
    private val authListener = authRepo.observar { alCambiarUsuario(it) }

    private fun alCambiarUsuario(usuario: FirebaseUser?) {
        escucha?.remove()
        escucha = null

        if (usuario == null) {
            _estado.update { UiState() }
            aperturaRegistrada = false
            agendaVistaRegistrada = false
            return
        }

        _estado.update {
            it.copy(uid = usuario.uid, email = usuario.email, cargandoAuth = false, errorAuth = null)
        }

        if (!aperturaRegistrada) {
            eventosRepo.registrarApertura(usuario.uid, usuario.email)
            aperturaRegistrada = true
        }

        escucha = eventosRepo.escuchar(
            uid = usuario.uid,
            alCambiar = { lista ->
                // Pendientes primero (por fecha), después los hechos.
                val ordenada = lista.sortedWith(compareBy<Evento> { it.hecho }.thenBy { it.fechaMillis })
                _estado.update { it.copy(eventos = ordenada, errorDatos = null) }
                if (!agendaVistaRegistrada) {
                    metricas.agendaVista(lista.size)
                    agendaVistaRegistrada = true
                }
            },
            alFallar = { e ->
                _estado.update { it.copy(errorDatos = "No se pudieron cargar tus fechas (${e.localizedMessage}).") }
            }
        )
    }

    fun ingresar(email: String, clave: String) = autenticar(email, clave, registrar = false)

    fun registrarse(email: String, clave: String) = autenticar(email, clave, registrar = true)

    private fun autenticar(email: String, clave: String, registrar: Boolean) {
        val mail = email.trim()
        if (mail.isEmpty() || clave.isEmpty()) {
            _estado.update { it.copy(errorAuth = "Completá email y contraseña.") }
            return
        }
        _estado.update { it.copy(cargandoAuth = true, errorAuth = null) }

        // El cambio de pantalla lo dispara el AuthStateListener; acá solo medimos.
        val ok = { if (registrar) metricas.registro() else metricas.login() }
        val error = { e: Exception ->
            _estado.update { it.copy(cargandoAuth = false, errorAuth = mensajeDeError(e)) }
        }
        if (registrar) authRepo.registrar(mail, clave, ok, error)
        else authRepo.ingresar(mail, clave, ok, error)
    }

    private fun mensajeDeError(e: Exception): String = when (e) {
        is FirebaseAuthWeakPasswordException -> "La contraseña tiene que tener al menos 6 caracteres."
        is FirebaseAuthUserCollisionException -> "Ya existe una cuenta con ese email. Probá ingresar."
        is FirebaseAuthInvalidUserException -> "No encontramos una cuenta con ese email."
        is FirebaseAuthInvalidCredentialsException ->
            if (e.errorCode == "ERROR_INVALID_EMAIL") "El email no tiene un formato válido."
            else "Email o contraseña incorrectos."
        is FirebaseNetworkException -> "Sin conexión. Revisá internet e intentá de nuevo."
        else -> e.localizedMessage ?: "Ocurrió un error. Intentá de nuevo."
    }

    // ── Carga de fechas (se mide el tiempo desde que abre el formulario) ──

    fun abrirCarga() {
        inicioCarga = SystemClock.elapsedRealtime()
    }

    private fun segundosDeCarga() = (SystemClock.elapsedRealtime() - inicioCarga) / 1000

    fun cancelarCarga() = metricas.cargaAbandonada(segundosDeCarga())

    fun guardar(materia: String, tipo: TipoEvento, fechaMillis: Long) {
        val uid = _estado.value.uid ?: return
        eventosRepo.agregar(uid, materia.trim(), tipo, fechaMillis)
        metricas.eventoCreado(tipo, Fechas.diasHasta(fechaMillis), segundosDeCarga())
    }

    fun alternarHecho(evento: Evento) {
        val uid = _estado.value.uid ?: return
        eventosRepo.marcarHecho(uid, evento.id, !evento.hecho)
        if (!evento.hecho) metricas.eventoCompletado(Fechas.diasHasta(evento.fechaMillis))
    }

    fun borrar(evento: Evento) {
        val uid = _estado.value.uid ?: return
        eventosRepo.borrar(uid, evento.id)
        metricas.eventoBorrado()
    }

    fun salir() {
        // Se corta la escucha antes del signOut para que Firestore no reporte "permission denied".
        escucha?.remove()
        escucha = null
        authRepo.salir()
    }

    override fun onCleared() {
        escucha?.remove()
        authRepo.dejarDeObservar(authListener)
    }
}

// ═════════════════════════════ PRESENTACIÓN: UI ═════════════════════════════

class MainActivity : ComponentActivity() {

    private val vm: AgendaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val estado by vm.estado.collectAsState()
                if (estado.uid == null) {
                    PantallaAcceso(estado, onIngresar = vm::ingresar, onRegistrarse = vm::registrarse)
                } else {
                    PantallaAgenda(estado, vm)
                }
            }
        }
    }
}

@Composable
fun PantallaAcceso(
    estado: UiState,
    onIngresar: (String, String) -> Unit,
    onRegistrarse: (String, String) -> Unit
) {
    var email by rememberSaveable { mutableStateOf("") }
    var clave by rememberSaveable { mutableStateOf("") }
    var modoRegistro by rememberSaveable { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Tengo Parcial",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Todos tus parciales y entregas en una lista, ordenados por lo que se viene.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = clave,
                onValueChange = { clave = it },
                label = { Text(if (modoRegistro) "Contraseña (mínimo 6)" else "Contraseña") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )

            estado.errorAuth?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { if (modoRegistro) onRegistrarse(email, clave) else onIngresar(email, clave) },
                enabled = !estado.cargandoAuth,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                if (estado.cargandoAuth) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (modoRegistro) "Crear cuenta" else "Ingresar")
                }
            }
            TextButton(onClick = { modoRegistro = !modoRegistro }) {
                Text(if (modoRegistro) "Ya tengo cuenta" else "No tengo cuenta, crear una")
            }
        }
    }
}

@Composable
fun PantallaAgenda(estado: UiState, vm: AgendaViewModel) {
    var cargando by remember { mutableStateOf(false) }
    var seleccionado by remember { mutableStateOf<Evento?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tengo Parcial") },
                actions = { TextButton(onClick = vm::salir) { Text("Salir") } }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = {
                vm.abrirCarga()
                cargando = true
            }) { Text("+  Agregar fecha") }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            estado.errorDatos?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (estado.eventos.isEmpty()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Todavía no cargaste nada", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Agregá tu próximo parcial o entrega y te mostramos cuántos días te quedan.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { Resumen(estado.eventos) }
                    items(estado.eventos, key = { it.id }) { ev ->
                        TarjetaEvento(
                            evento = ev,
                            onCheck = { vm.alternarHecho(ev) },
                            onClick = { seleccionado = ev }
                        )
                    }
                }
            }
        }
    }

    if (cargando) {
        DialogoNuevoEvento(
            onGuardar = { materia, tipo, fecha ->
                vm.guardar(materia, tipo, fecha)
                cargando = false
            },
            onCancelar = {
                vm.cancelarCarga()
                cargando = false
            }
        )
    }

    seleccionado?.let { ev ->
        AlertDialog(
            onDismissRequest = { seleccionado = null },
            title = { Text(ev.materia) },
            text = { Text("${ev.tipo.etiqueta} · ${Fechas.formatear(ev.fechaMillis)}") },
            confirmButton = {
                TextButton(onClick = {
                    vm.alternarHecho(ev)
                    seleccionado = null
                }) { Text(if (ev.hecho) "Marcar pendiente" else "Marcar como hecho") }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.borrar(ev)
                    seleccionado = null
                }) { Text("Borrar", color = MaterialTheme.colorScheme.error) }
            }
        )
    }
}

@Composable
fun Resumen(eventos: List<Evento>) {
    val pendientes = eventos.filter { !it.hecho && Fechas.diasHasta(it.fechaMillis) >= 0 }
    val texto = when {
        pendientes.isEmpty() -> "No tenés nada pendiente."
        else -> {
            val dias = Fechas.diasHasta(pendientes.first().fechaMillis)
            val cuando = when (dias) {
                0 -> "hoy"
                1 -> "mañana"
                else -> "en $dias días"
            }
            val cantidad = if (pendientes.size == 1) "1 pendiente" else "${pendientes.size} pendientes"
            "$cantidad · el próximo es $cuando"
        }
    }
    Text(
        texto,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
fun TarjetaEvento(evento: Evento, onCheck: () -> Unit, onClick: () -> Unit) {
    val dias = Fechas.diasHasta(evento.fechaMillis)
    val colores = MaterialTheme.colorScheme
    val (texto, color) = when {
        evento.hecho -> "Hecho" to colores.onSurfaceVariant
        dias < 0 -> "Pasó" to colores.onSurfaceVariant
        dias == 0 -> "Hoy" to colores.error
        dias == 1 -> "Mañana" to colores.error
        dias <= 3 -> "En $dias días" to colores.error
        dias <= 7 -> "En $dias días" to colores.tertiary
        else -> "En $dias días" to colores.onSurfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .alpha(if (evento.hecho) 0.55f else 1f)
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = evento.hecho, onCheckedChange = { onCheck() })
            Column(Modifier.weight(1f)) {
                Text(evento.materia, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${evento.tipo.etiqueta} · ${Fechas.formatear(evento.fechaMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colores.onSurfaceVariant
                )
            }
            Text(texto, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun DialogoNuevoEvento(
    onGuardar: (String, TipoEvento, Long) -> Unit,
    onCancelar: () -> Unit
) {
    var materia by rememberSaveable { mutableStateOf("") }
    var tipo by rememberSaveable { mutableStateOf(TipoEvento.PARCIAL) }
    var fecha by rememberSaveable { mutableStateOf<Long?>(null) }
    val contexto = LocalContext.current

    // Selector de fecha nativo de Android: no depende de la versión de Material 3.
    fun abrirSelector() {
        val (anio, mes, dia) = Fechas.partes(fecha ?: Fechas.hoyUtcMillis())
        DatePickerDialog(
            contexto,
            { _, a, m, d -> fecha = Fechas.utcMillis(a, m, d) },
            anio, mes, dia
        ).show()
    }

    AlertDialog(
        onDismissRequest = onCancelar,
        title = { Text("Nueva fecha") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = materia,
                    onValueChange = { materia = it },
                    label = { Text("Materia") },
                    placeholder = { Text("Ej: Arquitecturas Móviles") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // Dos filas de chips para que entren en pantallas angostas.
                TipoEvento.values().toList().chunked(2).forEach { fila ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        fila.forEach { t ->
                            FilterChip(
                                selected = tipo == t,
                                onClick = { tipo = t },
                                label = { Text(t.etiqueta) }
                            )
                        }
                    }
                }
                OutlinedButton(onClick = { abrirSelector() }, modifier = Modifier.fillMaxWidth()) {
                    Text(fecha?.let { Fechas.formatear(it) } ?: "Elegir fecha")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { fecha?.let { onGuardar(materia, tipo, it) } },
                enabled = materia.isNotBlank() && fecha != null
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onCancelar) { Text("Cancelar") } }
    )
}