package gay.benjifox.foxyphotos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import uniffi.foxy_codec.CommitToken
import uniffi.foxy_codec.decodeCommitToken
import uniffi.foxy_codec.decodeCommittedMediaKey
import uniffi.foxy_codec.decodeExistingMediaKey
import uniffi.foxy_codec.encodeCommitRequest
import uniffi.foxy_codec.encodeUploadTokenRequest
import uniffi.foxy_codec.hashCheckCodec

/** Minimal surface for checking the shared Rust core on a real device. */
class RustCoreTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { RustCoreScreen() }
        }
    }
}

private fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it) }

private fun varint(value: Long): ByteArray {
    val out = mutableListOf<Byte>()
    var remaining = value
    while (remaining and 0x7fL.inv() != 0L) {
        out.add(((remaining and 0x7f) or 0x80).toByte())
        remaining = remaining ushr 7
    }
    out.add(remaining.toByte())
    return out.toByteArray()
}

private fun bytesField(number: Int, value: ByteArray): ByteArray =
    varint((number shl 3).toLong() or 2L) + varint(value.size.toLong()) + value

private fun varintField(number: Int, value: Long): ByteArray =
    varint((number shl 3).toLong()) + varint(value)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RustCoreScreen() {
    var result by remember { mutableStateOf("tap an action") }

    Scaffold(topBar = { TopAppBar(title = { Text("rust core") }) }) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("shared codec via uniffi", style = MaterialTheme.typography.titleMedium)
                Text("one implementation, both platforms", style = MaterialTheme.typography.bodySmall)
            }
            item {
                ActionCard("encode upload token", "123") {
                    result = hex(encodeUploadTokenRequest(123u))
                }
            }
            item {
                ActionCard("hash-check request", "20-byte sha1") {
                    result = hex(hashCheckCodec(ByteArray(20) { it.toByte() }))
                }
            }
            item {
                ActionCard("commit request", "token + sha1") {
                    val token = CommitToken(42u, byteArrayOf(1, 2))
                    val bytes = encodeCommitRequest(
                        token = token,
                        fileName = "photo.jpg",
                        sha1 = ByteArray(20) { it.toByte() },
                        modifiedEpochSeconds = 1_700_000_000u,
                    )
                    result = "${bytes.size} bytes: ${hex(bytes)}"
                }
            }
            item {
                ActionCard("decode commit token", "42 / 01 02") {
                    val response = varintField(1, 42) + bytesField(2, byteArrayOf(1, 2))
                    val token = decodeCommitToken(response)
                    result = "${token.number} / ${hex(token.opaque)}"
                }
            }
            item {
                ActionCard("decode existing media key", "empty response") {
                    result = "key=${decodeExistingMediaKey(byteArrayOf())}"
                }
            }
            item {
                ActionCard("decode committed media key", "wrong envelope") {
                    result = try {
                        decodeCommittedMediaKey(bytesField(4, "key".encodeToByteArray()))
                    } catch (e: Exception) {
                        "rejected: ${e.message}"
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("result", style = MaterialTheme.typography.labelMedium)
                        Text(result, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionCard(title: String, hint: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(hint, style = MaterialTheme.typography.bodySmall)
            Button(onClick = onClick) { Text("run") }
        }
    }
}
