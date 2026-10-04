@file:OptIn(ExperimentalMaterial3Api::class)

package com.xmcam.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.xmcam.App
import com.xmcam.protocol.XmEvents
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Historial de alarmas recibidas (con miniatura si la regla tomó una foto). */
@Composable
fun EventsScreen(back: () -> Unit) {
    val app = App.instance
    val events by app.events.collectAsState()
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()) }

    Scaffold(topBar = { Bar("Eventos", back) { TextButton({ app.clearEvents() }) { Text("Borrar") } } }) { pad ->
        if (events.isEmpty()) Text("Aún no hay eventos.", Modifier.padding(pad).padding(24.dp))
        LazyColumn(Modifier.padding(pad).padding(horizontal = 12.dp)) {
            items(events) { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    val bmp = remember(e.snapshot) {
                        e.snapshot?.let { BitmapFactory.decodeFile(it, BitmapFactory.Options().apply { inSampleSize = 4 }) }
                    }
                    if (bmp != null) {
                        Image(bmp.asImageBitmap(), null, Modifier.size(80.dp).padding(end = 10.dp), contentScale = ContentScale.Crop)
                    }
                    Column {
                        Text("${XmEvents.label(e.event)} · ${e.cameraName}", style = MaterialTheme.typography.titleSmall)
                        Text(fmt.format(Date(e.time)), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
