package com.joao.extratorapk

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { ExtratorScreen(vm) } }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF7FD8B4))
        else -> lightColorScheme(primary = Color(0xFF1E6B52))
    }
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}

private object IconCache {
    private val map = ConcurrentHashMap<String, ImageBitmap>()
    fun load(context: Context, pkg: String): ImageBitmap? = map[pkg] ?: runCatching {
        val px = (40 * context.resources.displayMetrics.density).toInt().coerceAtLeast(48)
        context.packageManager.getApplicationIcon(pkg).toBitmap(px, px).asImageBitmap()
    }.getOrNull()?.also { map[pkg] = it }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtratorScreen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var extractAfterPick by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, flags) }
            vm.setFolder(uri)
            if (extractAfterPick) vm.extractSelected()
        }
        extractAfterPick = false
    }
    val downloadsHint: Uri? = remember {
        if (Build.VERSION.SDK_INT >= 26) {
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")
        } else null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Extrator de APKs") },
                actions = {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Escolher pasta de destino") },
                            onClick = { menuOpen = false; pickFolder.launch(state.folder ?: downloadsHint) },
                        )
                        DropdownMenuItem(
                            text = { Text("Atualizar lista") },
                            onClick = { menuOpen = false; vm.reload() },
                        )
                        DropdownMenuItem(
                            text = { Text("Sobre") },
                            onClick = { menuOpen = false; showAbout = true },
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (state.selected.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = vm::clearSelection) { Text("Limpar") }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = {
                            if (vm.folderStillValid()) vm.extractSelected()
                            else {
                                extractAfterPick = true
                                Toast.makeText(ctx, "Escolha a pasta onde salvar os arquivos", Toast.LENGTH_SHORT).show()
                                pickFolder.launch(downloadsHint)
                            }
                        }) { Text("Extrair (${state.selected.size})") }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("Buscar app ou pacote") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Default.Close, "Limpar busca") }
                    }
                },
                singleLine = true,
            )
            val visible = state.visible
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.showSystem,
                    onClick = { vm.setShowSystem(!state.showSystem) },
                    label = { Text("Apps do sistema") },
                    leadingIcon = if (state.showSystem) {
                        { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                    } else null,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = vm::selectAllVisible, enabled = visible.isNotEmpty()) { Text("Selecionar todos") }
            }
            Text(
                "${visible.size} apps" + (if (state.selected.isNotEmpty()) " · ${state.selected.size} selecionados" else ""),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            HorizontalDivider()
            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(visible, key = { it.packageName }) { app ->
                        AppRow(app, app.packageName in state.selected) { vm.toggle(app.packageName) }
                    }
                }
            }
        }
    }

    state.progress?.let { p ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Extraindo ${p.index} de ${p.total}") },
            text = {
                Column {
                    Text(p.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.size(12.dp))
                    LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = vm::cancel) { Text("Cancelar") } },
        )
    }

    state.report?.let { r -> ReportDialog(r, onDismiss = vm::dismissReport) }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("Sobre") },
            text = {
                Text(
                    "Copia o APK dos apps instalados, sem root.\n\n" +
                        "• App comum → arquivo .apk, que instala direto.\n" +
                        "• App dividido (marcado \"dividido\") → arquivo .apks com todas as partes. " +
                        "Instale com o SAI (Split APKs Installer).\n\n" +
                        "Os dados do app e os arquivos OBB não são copiados.",
                )
            },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("OK") } },
        )
    }
}

@Composable
private fun AppRow(app: AppEntry, checked: Boolean, onToggle: () -> Unit) {
    val ctx = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, app.packageName) {
        value = withContext(Dispatchers.IO) { IconCache.load(ctx, app.packageName) }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .heightIn(min = 64.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Box(Modifier.size(40.dp)) {
            icon?.let { Image(it, null, Modifier.fillMaxSize()) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (app.isSplit) Badge("dividido")
                if (app.isSystem) Badge("sistema")
            }
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(app.versionName.takeIf { it.isNotBlank() }?.let { "v$it" }, Formatter.formatShortFileSize(ctx, app.sizeBytes))
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Badge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(start = 6.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun ReportDialog(r: ExtractionReport, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val title = when {
        r.cancelled -> "Extração cancelada"
        r.files.isEmpty() -> "Nada foi extraído"
        r.errors.isEmpty() -> "Pronto!"
        else -> "Concluído com erros"
    }
    val single = r.files.singleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (r.files.isNotEmpty()) {
                    Text("${r.files.size} arquivo(s) salvos:", fontWeight = FontWeight.Medium)
                    r.files.forEach { Text("• ${it.name}", style = MaterialTheme.typography.bodySmall) }
                }
                if (r.files.any { it.isApks }) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "Arquivos .apks são apps divididos: instale com o SAI (Split APKs Installer).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (r.errors.isNotEmpty()) {
                    Spacer(Modifier.size(8.dp))
                    Text("Erros:", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.error)
                    r.errors.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            Row {
                if (single != null && !single.isApks) {
                    TextButton(onClick = { install(ctx, single.uri) }) { Text("Instalar") }
                }
                if (r.files.isNotEmpty()) {
                    TextButton(onClick = { share(ctx, r.files) }) { Text("Compartilhar") }
                }
                TextButton(onClick = onDismiss) { Text("Fechar") }
            }
        },
    )
}

private fun share(ctx: Context, files: List<ExtractedFile>) {
    val intent = if (files.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, files[0].uri)
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files.map { it.uri }))
    }
    intent.type = if (files.all { !it.isApks }) "application/vnd.android.package-archive" else "*/*"
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(intent, "Compartilhar"))
}

private fun install(ctx: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        ctx.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "Nenhum instalador encontrado", Toast.LENGTH_SHORT).show()
    }
}
